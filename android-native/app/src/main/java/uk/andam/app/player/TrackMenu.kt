package uk.andam.app.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

/** Audio / subtitle / quality choices read from ExoPlayer's current tracks. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object TrackMenu {
    data class Option(val label: String, val selected: Boolean, val group: TrackGroup, val index: Int, val type: Int)

    fun options(player: ExoPlayer, type: Int): List<Option> {
        val out = ArrayList<Option>()
        player.currentTracks.groups.forEach { g: Tracks.Group ->
            if (g.type != type) return@forEach
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                out.add(Option(label(g.getTrackFormat(i), type, out.size + 1), g.isTrackSelected(i), g.mediaTrackGroup, i, type))
            }
        }
        if (type == C.TRACK_TYPE_VIDEO) out.sortByDescending { it.group.getFormat(it.index).height }
        return out
    }

    private fun label(f: Format, type: Int, n: Int): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
            Locale(it).getDisplayLanguage(Locale.ENGLISH).ifBlank { it }
        }
        return when (type) {
            C.TRACK_TYPE_VIDEO -> listOfNotNull(if (f.height > 0) "${f.height}p" else "Track $n", mbps(f.bitrate)).joinToString(" · ")
            C.TRACK_TYPE_AUDIO -> listOfNotNull(f.label ?: lang ?: "Track $n", channels(f.channelCount)).joinToString(" · ")
            else -> f.label ?: lang ?: "Subtitle $n"
        }
    }

    fun mbps(b: Int): String? = if (b > 0) "%.1f Mbps".format(Locale.US, b / 1_000_000f) else null

    fun codec(mime: String?): String? = when (mime) {
        null -> null
        "video/avc" -> "H.264"
        "video/hevc" -> "HEVC (H.265)"
        "video/mpeg2" -> "MPEG-2"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "audio/mp4a-latm" -> "AAC"
        "audio/ac3" -> "Dolby AC-3"
        "audio/eac3", "audio/eac3-joc" -> "Dolby E-AC-3"
        "audio/mpeg", "audio/mpeg-L2" -> "MPEG audio"
        "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
        "audio/opus" -> "Opus"
        else -> mime.substringAfter('/').uppercase()
    }

    /** One-line description of what is playing right now, e.g. "1920×1080 · H.264 · 25 fps". */
    fun currentHeight(player: ExoPlayer): Int = player.videoFormat?.height ?: 0

    fun videoInfo(player: ExoPlayer): String? {
        val f = player.videoFormat ?: return null
        return listOfNotNull(
            if (f.width > 0 && f.height > 0) "${f.width}×${f.height}" else null,
            codec(f.sampleMimeType),
            if (f.frameRate > 0) "${f.frameRate.toInt()} fps" else null,
            mbps(f.bitrate),
        ).joinToString(" · ").ifBlank { null }
    }

    fun audioInfo(player: ExoPlayer): String? {
        val f = player.audioFormat ?: return null
        return listOfNotNull(
            codec(f.sampleMimeType),
            channels(f.channelCount),
            if (f.sampleRate > 0) "${f.sampleRate / 1000} kHz" else null,
        ).joinToString(" · ").ifBlank { null }
    }

    private fun channels(c: Int): String? = when {
        c <= 0 -> null
        c == 1 -> "Mono"
        c == 2 -> "Stereo"
        c == 6 -> "5.1"
        c == 8 -> "7.1"
        else -> "$c ch"
    }

    fun select(player: ExoPlayer, o: Option) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(o.type, false)
            .setOverrideForType(TrackSelectionOverride(o.group, o.index))
            .build()
    }

    fun disable(player: ExoPlayer, type: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(type)
            .setTrackTypeDisabled(type, true)
            .build()
    }

    fun auto(player: ExoPlayer, type: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(type)
            .setTrackTypeDisabled(type, false)
            .build()
    }

    fun hasOverride(player: ExoPlayer, type: Int): Boolean =
        player.trackSelectionParameters.overrides.values.any { it.type == type }
}
