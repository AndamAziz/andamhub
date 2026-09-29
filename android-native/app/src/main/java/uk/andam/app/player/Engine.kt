package uk.andam.app.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.andam.app.Config
import uk.andam.app.net.Api

/**
 * Playback engine: one ExoPlayer, and a recovery ladder so a channel never sits frozen.
 *
 * For every item the engine builds a list of attempts from the server's opaque tokens:
 *   1. primary stream (live: progressive MPEG-TS; VOD: the file) — container sniffed automatically
 *   2. HLS fallback (live channels that also publish a playlist)
 *   3. the same stream through the server's audio fixer (sound re-encoded to AAC)
 * Network drops reconnect the same attempt with back-off; a stream that stalls for 12 s is
 * reloaded; a live stream that simply ends is reopened. Only when every attempt is exhausted
 * does the viewer see an error, with a Retry button.
 */
@OptIn(UnstableApi::class)
class Engine(private val context: Context, private val scope: CoroutineScope) {

    /** One way of reaching a stream. `headers` is set only for direct (Referer-protected) IPTV links. */
    private data class Attempt(
        val url: String,
        val hls: Boolean,
        val fix: Boolean,
        val headers: Map<String, String> = emptyMap(),
        /** Straight from the channel's own host (IPTV), not through the Andam server. */
        val direct: Boolean = false,
    )

    // ---- observable UI state ----
    var title by mutableStateOf("")
    var subtitle by mutableStateOf("")
    var logo by mutableStateOf("")
    var live by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var tracksVersion by mutableIntStateOf(0)

    val player: ExoPlayer

    private var item: PlayItem? = null
    private var attempts: List<Attempt> = emptyList()
    private var attemptIndex = 0
    private var retries = 0
    private var remints = 0
    private var stallSeconds = 0
    private var stallReloads = 0
    private var playingSince = 0L
    private var audioChecked = false

    /** Headers for the attempt being played; read on ExoPlayer's loader threads. */
    @Volatile
    private var requestHeaders: Map<String, String> = emptyMap()
    private var session = 0
    private var resolveJob: Job? = null
    private var retryJob: Job? = null

    init {
        val renderers = DefaultRenderersFactory(context)
            // FFmpeg audio (AC3 / E-AC3 / DTS / MP2) when the device has no decoder of its own.
            // Hardware decoders first for everything (PREFER also switched the picture to
            // FFmpeg's experimental software video decoder: black/garbled video with sound).
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            // …except Dolby/DTS sound: phones often claim those decoders but play silence,
            // so hide them and let FFmpeg's audio decoder take these formats.
            .setMediaCodecSelector { mimeType, secure, tunneling ->
                if (mimeType in SOFTWARE_AUDIO) emptyList()
                else MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
            }
            .setEnableDecoderFallback(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Every request carries the current attempt's headers (Referer/Origin/User-Agent for
        // protected channels — applied to the playlist and to every segment alike).
        val okhttp = OkHttpDataSource.Factory(Api.media)
        val http = ResolvingDataSource.Factory(okhttp) { spec ->
            val extra = HashMap(requestHeaders)
            if (!extra.containsKey("User-Agent")) extra["User-Agent"] = Config.USER_AGENT
            spec.withAdditionalHeaders(extra)
        }

        // Default TS parsing. The NON_IDR_KEYFRAMES / DETECT_ACCESS_UNITS flags split
        // multi-slice broadcast frames after their first slice: only a thin garbled strip
        // at the top was decoded and the rest stayed black (provider live + series).
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val sources = DefaultMediaSourceFactory(http, extractors)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(3))

        player = ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE && error == null
                if (state == Player.STATE_READY) {
                    stallSeconds = 0
                    status = null
                    if (playingSince == 0L) playingSince = System.currentTimeMillis()
                    duration = player.duration.coerceAtLeast(0)
                }
                if (state == Player.STATE_ENDED) {
                    // A live stream never really ends: the upstream closed the socket. Reopen it.
                    if (live) reconnect("Reconnecting…", 500) else playing = false
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
                val current = attempts.getOrNull(attemptIndex) ?: return
                // Sound present but not decodable on this device → server audio fixer.
                if (tracks.containsType(C.TRACK_TYPE_AUDIO) && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO) && !current.fix) {
                    Log.w(TAG, "audio track not decodable, switching to audio fix")
                    jumpToFix()
                } else if (tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)) {
                    status = "This device cannot decode the picture of this stream (H.265/HEVC)."
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                handleError(e)
            }
        })

        // Heartbeat: position for the UI, stall detection, retry-counter reset after stable playback.
        scope.launch {
            while (isActive) {
                delay(1000)
                position = player.currentPosition.coerceAtLeast(0)
                if (player.duration > 0) duration = player.duration
                val stalled = player.playWhenReady && player.playbackState == Player.STATE_BUFFERING && item != null && error == null
                if (stalled) {
                    stallSeconds++
                    if (stallSeconds >= (if (live) 8 else 12)) {
                        stallSeconds = 0
                        stallReloads++
                        if (stallReloads >= 3) nextAttempt() else reconnect("Reconnecting…", 0)
                    }
                } else {
                    stallSeconds = 0
                }
                // Silent-audio check: the picture plays but no sound was decoded → audio fixer route.
                if (!audioChecked && player.isPlaying && playingSince > 0 && System.currentTimeMillis() - playingSince > 6_000) {
                    audioChecked = true
                    val cur = attempts.getOrNull(attemptIndex)
                    val counters = player.audioDecoderCounters
                    val hasAudio = player.currentTracks.containsType(C.TRACK_TYPE_AUDIO)
                    if (cur != null && !cur.fix && hasAudio && (counters == null || counters.renderedOutputBufferCount == 0)) {
                        Log.w(TAG, "no audio decoded, switching to audio fix")
                        jumpToFix()
                    }
                }
                if (player.isPlaying && playingSince > 0 && System.currentTimeMillis() - playingSince > 15_000) {
                    retries = 0
                    stallReloads = 0
                }
            }
        }
    }

    // ---------------- public API ----------------

    fun play(next: PlayItem) {
        saveResume()
        if (next !== item) remints = 0
        item = next
        session++
        val mine = session
        title = next.title
        subtitle = next.subtitle
        logo = next.logo
        live = next.isLive
        error = null
        status = null
        buffering = true
        position = 0
        duration = 0
        attempts = emptyList()
        attemptIndex = 0
        retries = 0
        stallReloads = 0
        playingSince = 0
        retryJob?.cancel()
        resolveJob?.cancel()
        player.stop()
        player.clearMediaItems()
        resolveJob = scope.launch {
            try {
                val list = ArrayList<Attempt>()
                val tokens: List<String> = when (next.kind) {
                    Kind.LIVE -> Api.play(next.source, "live", next.id).let { listOfNotNull(it.play, it.fallback) }
                    Kind.VOD -> listOf(Api.play(next.source, "vod", next.id, next.ext).play)
                    Kind.EPISODE -> listOf(next.token ?: Api.play(next.source, "series", next.id, next.ext).play)
                    Kind.IPTV -> Api.iptvPlay(next.source, next.id).let { p ->
                        // Referer-protected channel: the phone asks for it directly with the
                        // playlist's headers first (like VLC); the relay routes stay as fallbacks.
                        if (p.directUrl != null) {
                            list.add(Attempt(p.directUrl, hls = p.directUrl.contains(".m3u8", true), fix = false, headers = p.headers, direct = true))
                        }
                        listOf(p.token)
                    }
                }.filter { it.isNotBlank() }
                if (mine != session) return@launch
                if (tokens.isEmpty() && list.isEmpty()) {
                    fail("This stream is not available right now.")
                    return@launch
                }
                tokens.forEachIndexed { i, t -> list.add(Attempt(Api.streamUrl(t), hls = i > 0, fix = false)) }
                if (tokens.isNotEmpty()) list.add(Attempt(Api.streamUrl(tokens[0], fix = true), hls = false, fix = true))
                attempts = list
                start(0)
                if (!next.isLive) {
                    val resume = Resume.get(context, next.resumeKey)
                    if (resume > 0) player.seekTo(resume)
                }
            } catch (e: Exception) {
                if (mine == session) fail(e.message ?: "Could not open this stream.")
            }
        }
    }

    fun retry() {
        val current = item ?: return
        play(current)
    }

    /** Manual retry from the error screen: start the ladder again from the top. */
    fun retryFromUser() {
        remints = 0
        retry()
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun seekBy(ms: Long) {
        if (live) return
        player.seekTo((player.currentPosition + ms).coerceIn(0, maxOf(0, player.duration)))
    }

    fun seekTo(ms: Long) {
        if (!live) player.seekTo(ms)
    }

    fun saveResume() {
        val it = item ?: return
        if (!it.isLive && player.duration > 0) Resume.put(context, it.resumeKey, player.currentPosition, player.duration)
    }

    fun release() {
        saveResume()
        session++
        player.release()
    }

    // ---------------- recovery ladder ----------------

    private fun start(index: Int) {
        val a = attempts.getOrNull(index) ?: return fail("This stream is not responding right now.")
        attemptIndex = index
        stallSeconds = 0
        playingSince = 0
        audioChecked = false
        requestHeaders = a.headers
        val builder = MediaItem.Builder().setUri(a.url)
        if (a.hls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (live) builder.setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(8_000).build(),
        )
        error = null
        player.setMediaItem(builder.build())
        player.prepare()
        player.playWhenReady = true
    }

    private fun reconnect(message: String, delayMs: Long) {
        val mine = session
        status = message
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            if (mine != session) return@launch
            val pos = player.currentPosition
            start(attemptIndex)
            if (!live && pos > 0) player.seekTo(pos)
        }
    }

    private fun nextAttempt() {
        retries = 0
        stallReloads = 0
        val next = attemptIndex + 1
        if (next >= attempts.size) fail("This stream is not responding right now.") else {
            status = "Trying another route…"
            start(next)
        }
    }

    private fun jumpToFix() {
        val fixIndex = attempts.indexOfFirst { it.fix }
        if (fixIndex < 0 || fixIndex == attemptIndex) return
        status = "Fixing the sound…"
        val pos = player.currentPosition
        start(fixIndex)
        if (!live && pos > 0) player.seekTo(pos)
    }

    private fun handleError(e: PlaybackException) {
        Log.w(TAG, "playback error ${e.errorCodeName}", e)
        val current = attempts.getOrNull(attemptIndex) ?: return fail(e.message ?: "Playback error")
        when (e.errorCode) {
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                player.seekToDefaultPosition()
                player.prepare()
            }

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> {
                if (!current.hls) {
                    // The "file" was really a playlist (common for M3U channels): replay it as HLS.
                    attempts = attempts.toMutableList().also { it[attemptIndex] = current.copy(hls = true) }
                    start(attemptIndex)
                } else nextAttempt()
            }

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> {
                if (!current.fix && attempts.any { it.fix }) jumpToFix()
                else fail("This device cannot play this stream's format.")
            }

            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                // A direct link refused us: move on to the relay route.
                // From our own server: the token or channel is gone — re-mint once, then move on.
                if (current.direct) nextAttempt() else if (remints++ < 1) retry() else nextAttempt()
            }

            else -> {
                val limit = if (live) 5 else 3
                if (retries < limit) {
                    retries++
                    val wait = (1000L shl (retries - 1)).coerceAtMost(8000)
                    reconnect("Reconnecting…", wait)
                } else nextAttempt()
            }
        }
    }

    private fun fail(message: String) {
        retryJob?.cancel()
        player.stop()
        buffering = false
        status = null
        error = message
    }

    companion object {
        private const val TAG = "AndamEngine"
        private val SOFTWARE_AUDIO = setOf(
            MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_TRUEHD,
        )
    }
}
