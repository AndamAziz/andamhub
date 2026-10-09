package uk.andam.app.player

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.andam.app.ui.AndamTheme
import uk.andam.app.ui.C

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var engine: Engine
    private val ui = PlayerUiState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        engine = Engine(this, lifecycleScope)
        val first = PlayQueue.current()
        if (first == null) {
            finish()
            return
        }
        ui.panelGroup = first.group
        ui.tv = uk.andam.app.ui.isTvDevice(this)
        // Series: when an episode ends, the next one starts by itself.
        engine.onEnded = {
            val cur = PlayQueue.current()
            val nextIndex = PlayQueue.index + 1
            if (cur?.kind == Kind.EPISODE && nextIndex < PlayQueue.items.size) {
                pick(nextIndex)
            }
        }
        ui.subSize = PlayerPrefs.subSize(this)
        ui.background = PlayerPrefs.backgroundAudio(this)
        engine.play(first)

        ui.onSleep = { min -> setSleep(min) }

        // Lock screen / notification / Bluetooth controls, and sound with the screen off.
        PlayerHolder.player = engine.player
        if (ui.background) runCatching { startService(Intent(this, PlaybackService::class.java)) }

        setContent {
            AndamTheme {
                PlayerScreen(engine, ui, onBack = { finish() }, onZap = { zap(it) }, onPick = { pick(it) })
            }
        }
    }

    private var sleepJob: kotlinx.coroutines.Job? = null

    /** Sleep timer: pause and close the player after [minutes] (works with the screen off too). */
    private fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        ui.sleepMinutes = minutes
        ui.sleepAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        if (minutes <= 0) return
        sleepJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(minutes * 60_000L)
            engine.player.pause()
            finish()
        }
    }

    private fun pick(index: Int) {
        val item = PlayQueue.items.getOrNull(index) ?: return
        PlayQueue.index = index
        engine.play(item)
        ui.poke()
    }

    private fun zap(dir: Int) {
        val items = PlayQueue.items
        if (items.size < 2) return
        // Stay inside the category the viewer is browsing, when there is one.
        val group = ui.panelGroup
        val pool = items.indices.filter { group.isEmpty() || items[it].group == group }.ifEmpty { items.indices.toList() }
        val at = pool.indexOf(PlayQueue.index)
        val next = pool[((if (at < 0) 0 else at) + dir).mod(pool.size)]
        pick(next)
    }

    /**
     * Remote control. With nothing on screen the arrows act directly on playback (zap / seek) and
     * OK opens the channel guide on live TV or pauses a film. With a panel or the controls open,
     * the arrows move between buttons as usual. Media and channel keys work everywhere.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val live = PlayQueue.current()?.isLive == true
        val code = event.keyCode
        when (code) {
            KeyEvent.KEYCODE_CHANNEL_UP -> if (live) { zap(-1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> if (live) { zap(1); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                engine.togglePlay(); ui.poke(); return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (engine.canSeek) { engine.seekBy(30_000); ui.poke(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (engine.canSeek) { engine.seekBy(-30_000); ui.poke(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                ui.panel = false; ui.settings = !ui.settings; return true
            }
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV_INPUT, KeyEvent.KEYCODE_INFO -> if (live && PlayQueue.items.size > 1) {
                ui.settings = false; ui.panel = !ui.panel; return true
            }
        }
        val overlay = ui.controls || ui.panel || ui.settings
        if (!overlay) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (live && PlayQueue.items.size > 1) ui.panel = true
                    else { engine.togglePlay(); ui.poke() }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> { if (live) zap(-1) else ui.poke(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (live) zap(1) else ui.poke(); return true }
                // Films, and live channels whose stream keeps a window: ±10 s.
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (engine.canSeek) engine.seekBy(-10_000); ui.poke(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (engine.canSeek) engine.seekBy(10_000); ui.poke(); return true }
            }
        } else if (ui.controls) {
            ui.keep()
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        engine.saveResume()
        // "Background audio": the sound keeps going with the screen locked or another app open.
        val keepPlaying = PlayerPrefs.backgroundAudio(this) && !isFinishing
        if (!keepPlaying) engine.player.pause()
    }

    /** Home button while watching: shrink to a floating window (phones/tablets). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (ui.tv || !PlayerPrefs.pip(this) || android.os.Build.VERSION.SDK_INT < 26) return
        if (!engine.player.isPlaying) return
        val f = engine.player.videoFormat
        val ratio = if (f != null && f.width > 0 && f.height > 0) android.util.Rational(f.width, f.height) else android.util.Rational(16, 9)
        runCatching {
            enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(ratio.coerceRatio())
                    .build(),
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ui.pip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            ui.controls = false; ui.panel = false; ui.settings = false
        } else if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            // The floating window was closed: stop watching.
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        if (::engine.isInitialized && engine.error == null) engine.player.play()
    }

    override fun onDestroy() {
        runCatching { stopService(Intent(this, PlaybackService::class.java)) }
        PlayerHolder.player = null
        if (::engine.isInitialized) engine.release()
        super.onDestroy()
    }
}

/** Android only accepts picture-in-picture ratios between 1:2.39 and 2.39:1. */
private fun android.util.Rational.coerceRatio(): android.util.Rational {
    val v = toFloat()
    return when {
        v > 2.39f -> android.util.Rational(239, 100)
        v < 1 / 2.39f -> android.util.Rational(100, 239)
        else -> this
    }
}

/** Overlay visibility and panel state, owned by the activity so D-pad keys can drive it. */
class PlayerUiState {
    var controls by mutableStateOf(true)
    var panel by mutableStateOf(false)
    var settings by mutableStateOf(false)
    var panelGroup by mutableStateOf("")
    var resize by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    var pokes by mutableIntStateOf(0)
    /** Remote-control layout (Android TV, Fire TV, TV boxes). */
    var tv = false
    /** Floating picture-in-picture window: no overlays. */
    var pip by mutableStateOf(false)
    /** Subtitle size 0..3 (PlayerPrefs). */
    var subSize by mutableIntStateOf(1)
    /** Sound keeps playing with the screen off. */
    var background by mutableStateOf(true)
    /** Sleep timer: minutes chosen (0 = off) and when it fires (ms since boot clock). */
    var sleepMinutes by mutableIntStateOf(0)
    var sleepAt by mutableStateOf(0L)
    /** Set by the activity: starts / cancels the sleep timer (minutes, 0 = off). */
    var onSleep: (Int) -> Unit = {}

    fun poke() {
        controls = true
        pokes++
    }

    /** Keep the controls up a little longer (a key was pressed inside them). */
    fun keep() {
        pokes++
    }
}

private val resizeModes = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Fill",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
)

@OptIn(UnstableApi::class)
@Composable
private fun PlayerScreen(
    engine: Engine,
    ui: PlayerUiState,
    onBack: () -> Unit,
    onZap: (Int) -> Unit,
    onPick: (Int) -> Unit,
) {
    val zappable = PlayQueue.items.size > 1 && (engine.live || PlayQueue.current()?.kind == Kind.EPISODE)

    // Auto-hide after 3.5 s of no interaction while playing.
    LaunchedEffect(ui.pokes, ui.controls, engine.playing, ui.panel, ui.settings) {
        if (ui.controls && engine.playing && !ui.panel && !ui.settings) {
            delay(3500)
            ui.controls = false
        }
    }

    // On TV, Back first closes whatever is on screen (guide, settings, then the controls).
    BackHandler(enabled = ui.panel || ui.settings || (ui.tv && ui.controls)) {
        if (ui.panel || ui.settings) {
            ui.panel = false
            ui.settings = false
        } else {
            ui.controls = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = engine.player
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    resizeMode = ui.resize
                    // The remote is handled by the activity and the Compose overlay, never the view.
                    isFocusable = false
                    descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            update = { v ->
                v.resizeMode = ui.resize
                // Readable subtitles: white with a soft dark box, size from Settings.
                v.subtitleView?.apply {
                    setStyle(
                        androidx.media3.ui.CaptionStyleCompat(
                            android.graphics.Color.WHITE, android.graphics.Color.argb(140, 0, 0, 0),
                            android.graphics.Color.TRANSPARENT, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            android.graphics.Color.BLACK, null,
                        ),
                    )
                    setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * listOf(0.8f, 1f, 1.3f, 1.6f)[ui.subSize.coerceIn(0, 3)])
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Tap layer: single tap toggles controls, double tap left/right skips 10 s on VOD.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(engine.canSeek) {
                    detectTapGestures(
                        onTap = {
                            if (ui.panel || ui.settings) {
                                ui.panel = false; ui.settings = false
                            } else if (ui.controls) ui.controls = false else ui.poke()
                        },
                        onDoubleTap = { o ->
                            if (engine.canSeek) {
                                engine.seekBy(if (o.x < size.width / 2f) -10_000L else 10_000L)
                                ui.poke()
                            }
                        },
                    )
                },
        )

        // Loading ring: same inset-aware centre as the controls, so it wraps the play button exactly.
        // The ring only appears when loading lasts (short hiccups stay invisible).
        var showRing by remember { mutableStateOf(false) }
        LaunchedEffect(engine.buffering) {
            showRing = false
            if (engine.buffering) { delay(900); showRing = true }
        }
        if (showRing && engine.buffering && engine.error == null) {
            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = C.Ember,
                    trackColor = Color(0x33FFFFFF),
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(78.dp),
                )
            }
        }

        engine.online.kurdishProgress?.let { p ->
            if (!ui.pip) Pill("Preparing Kurdish subtitles · ${(p * 100).toInt()}%", Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        }
        engine.status?.let {
            if (engine.error == null && !ui.pip) Pill(it, Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
        }

        engine.error?.let { msg ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(C.Surface)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(msg, color = C.Text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { engine.retryFromUser() }) { Text("Retry", color = C.Ember) }
                    if (zappable) TextButton(onClick = { onZap(1) }) { Text("Next channel", color = C.Text) }
                    TextButton(onClick = onBack) { Text("Close", color = C.Muted) }
                }
            }
        }

        AnimatedVisibility(
            visible = ui.controls && !ui.panel && !ui.settings,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Controls(engine, ui, zappable, onBack, onZap)
        }

        AnimatedVisibility(
            visible = ui.panel,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            ChannelPanel(ui, onPick = { onPick(it); ui.panel = false }, onClose = { ui.panel = false })
        }

        AnimatedVisibility(
            visible = ui.settings,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        ) {
            SettingsPanel(engine, ui)
        }
    }
}

@Composable
private fun Controls(engine: Engine, ui: PlayerUiState, zappable: Boolean, onBack: () -> Unit, onZap: (Int) -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xB3000000), 0.3f to Color.Transparent,
                    0.62f to Color.Transparent, 1f to Color(0xC7000000),
                ),
            )
            .safeDrawingPadding(),
    ) {
        // Top bar: back, logo + title, live badge, actions.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
            if (engine.logo.isNotBlank()) {
                AsyncImage(
                    model = engine.logo, contentDescription = null,
                    modifier = Modifier.padding(start = 6.dp).size(34.dp).clip(RoundedCornerShape(8.dp)).background(C.Surface2).padding(3.dp),
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(engine.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (engine.subtitle.isNotBlank()) Text(engine.subtitle, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (engine.live || engine.catchup) LivePill(engine) { ui.poke() }
        }

        // Centre: previous / play-pause / next.
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            if (zappable) RoundIcon(Icons.Filled.SkipPrevious, "Previous") { onZap(-1) }
            else if (!engine.live) RoundIcon(Icons.Filled.Replay10, "Back 10 seconds") { engine.seekBy(-10_000); ui.poke() }
            val playFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { if (ui.tv) { delay(60); runCatching { playFocus.requestFocus() } } }
            Box(
                Modifier
                    .focusRequester(playFocus)
                    .tvRing(CircleShape)
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color(0x8C0A0B0F))
                    .border(1.dp, Color(0x38FFFFFF), CircleShape)
                    .clickable { engine.togglePlay(); ui.poke() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (engine.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play or pause", tint = Color.White, modifier = Modifier.size(36.dp),
                )
            }
            if (zappable) RoundIcon(Icons.Filled.SkipNext, "Next") { onZap(1) }
            else if (!engine.live) RoundIcon(Icons.Filled.Forward10, "Forward 10 seconds") { engine.seekBy(10_000); ui.poke() }
        }

        // Bottom bar: timeline (movies/episodes) and, in the bottom-right corner, channels + settings.
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (engine.live && engine.liveSeekable) {
                LiveTimeline(engine, ui)
            } else if (!engine.live && engine.duration > 0) {
                var dragging by remember { mutableStateOf(false) }
                var dragValue by remember { mutableFloatStateOf(0f) }
                val progress = if (dragging) dragValue else (engine.position.toFloat() / engine.duration).coerceIn(0f, 1f)
                Text(fmt(if (dragging) (dragValue * engine.duration).toLong() else engine.position), color = Color.White, fontSize = 12.sp)
                Slider(
                    value = progress,
                    onValueChange = { dragging = true; dragValue = it; ui.poke() },
                    onValueChangeFinished = { engine.seekTo((dragValue * engine.duration).toLong()); dragging = false },
                    colors = SliderDefaults.colors(thumbColor = C.Ember, activeTrackColor = C.Ember, inactiveTrackColor = Color(0x4DFFFFFF)),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text(fmt(engine.duration), color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Channels with a provider archive: rewind further than the stream itself allows.
                if ((engine.live || engine.catchup) && PlayQueue.current()?.archive == true) CatchupButton(engine, ui)
                if (PlayQueue.items.size > 1 && engine.live) {
                    RoundIcon(Icons.AutoMirrored.Filled.FormatListBulleted, "Channels") { ui.panel = true }
                }
                RoundIcon(Icons.Filled.Tune, "Settings") { ui.settings = true }
            }
        }
    }
}

/**
 * Full-screen channel guide over the video (TV-style): a big "TV CHANNELS" list in the middle,
 * and a round arrow that slides a "CATEGORIES" column in from the left.
 */
@Composable
private fun ChannelPanel(ui: PlayerUiState, onPick: (Int) -> Unit, onClose: () -> Unit) {
    val groups = remember { listOf("" to "All") + PlayQueue.groups.map { it.id to it.name } }
    val items = PlayQueue.items
    var showCats by remember { mutableStateOf(ui.tv) }
    val currentFocus = remember { FocusRequester() }
    val shown = remember(ui.panelGroup) {
        items.indices.filter { ui.panelGroup.isEmpty() || items[it].group == ui.panelGroup }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(ui.panelGroup) {
        val at = shown.indexOf(PlayQueue.index)
        listState.scrollToItem(if (at > 1) at - 1 else 0)
    }
    // Remote: land on the channel that is playing (or the first one of this category).
    LaunchedEffect(Unit) { if (ui.tv) { delay(80); runCatching { currentFocus.requestFocus() } } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(Color(0xEB000000), Color(0xA6000000), Color(0x73000000))))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
    ) {
        Row(
            Modifier
                .fillMaxHeight()
                .align(if (showCats) Alignment.CenterStart else Alignment.Center)
                .safeDrawingPadding()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (groups.size > 1) {
                AnimatedVisibility(
                    visible = showCats,
                    enter = expandHorizontally() + fadeIn(),
                    exit = shrinkHorizontally() + fadeOut(),
                ) {
                    Column(Modifier.width(230.dp).fillMaxHeight()) {
                        GuideHeader("CATEGORIES", Alignment.Start)
                        LazyColumn(Modifier.fillMaxHeight()) {
                            itemsIndexed(groups) { _, g ->
                                val on = g.first == ui.panelGroup
                                Text(
                                    g.second,
                                    color = Color.White.copy(alpha = if (on) 1f else 0.88f),
                                    fontSize = 15.sp,
                                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                    lineHeight = 20.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .tvRing(RoundedCornerShape(14.dp))
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (on) Color(0x3DFFFFFF) else Color.Transparent)
                                        .clickable { ui.panelGroup = g.first }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
                Box(
                    Modifier
                        .padding(horizontal = 14.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                        .clickable { showCats = !showCats },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (showCats) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (showCats) "Hide categories" else "Show categories",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Column(Modifier.width(400.dp).fillMaxHeight()) {
                GuideHeader("TV CHANNELS", Alignment.CenterHorizontally)
                LazyColumn(state = listState, modifier = Modifier.fillMaxHeight()) {
                    itemsIndexed(shown) { _, idx ->
                        val ch = items[idx]
                        val on = idx == PlayQueue.index
                        val target = on || (shown.indexOf(PlayQueue.index) < 0 && idx == shown.first())
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .then(if (target) Modifier.focusRequester(currentFocus) else Modifier)
                                .tvRing(RoundedCornerShape(14.dp))
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (on) Color(0x3DFFFFFF) else Color.Transparent)
                                .clickable { onPick(idx) }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${idx + 1}", color = Color(0x99FFFFFF), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(34.dp),
                            )
                            // Small, neat logo tile: the whole logo fits inside, never cropped.
                            Box(
                                Modifier
                                    .size(width = 52.dp, height = 36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0x1FFFFFFF))
                                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                var logoOk by remember(ch.logo) { mutableStateOf(false) }
                                if (!logoOk) Text(ch.title.trim().take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                if (ch.logo.isNotBlank()) {
                                    AsyncImage(
                                        model = ch.logo, contentDescription = null,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                        onState = { logoOk = it is coil.compose.AsyncImagePainter.State.Success },
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 4.dp),
                                    )
                                }
                            }
                            Text(
                                ch.title, color = Color.White.copy(alpha = if (on) 1f else 0.9f), fontSize = 15.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 14.dp).weight(1f),
                            )
                            if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(C.Ember))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideHeader(text: String, align: Alignment.Horizontal) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Text(
            text,
            color = Color(0xE6FFFFFF),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 3.sp,
            modifier = Modifier.padding(top = 14.dp, bottom = 12.dp, start = 18.dp),
        )
    }
}

@Composable
private fun SettingsPanel(engine: Engine, ui: PlayerUiState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val VIDEO = androidx.media3.common.C.TRACK_TYPE_VIDEO
    val AUDIO = androidx.media3.common.C.TRACK_TYPE_AUDIO
    val TEXT = androidx.media3.common.C.TRACK_TYPE_TEXT
    // Re-read tracks whenever ExoPlayer reports a change.
    val version = engine.tracksVersion
    val audio = remember(version) { TrackMenu.options(engine.player, AUDIO) }
    val text = remember(version) { TrackMenu.options(engine.player, TEXT) }
    val video = remember(version) { TrackMenu.options(engine.player, VIDEO) }
    var speed by remember { mutableFloatStateOf(engine.player.playbackParameters.speed) }
    var manualQuality by remember(version) { mutableStateOf(TrackMenu.hasOverride(engine.player, VIDEO)) }
    var subsOff by remember(version) { mutableStateOf(text.none { it.selected }) }
    // Live stream info (resolution, codec, bitrate) refreshed every 2 s while the panel is open.
    var vInfo by remember { mutableStateOf(TrackMenu.videoInfo(engine.player)) }
    var aInfo by remember { mutableStateOf(TrackMenu.audioInfo(engine.player)) }
    LaunchedEffect(version) {
        while (true) {
            vInfo = TrackMenu.videoInfo(engine.player)
            aInfo = TrackMenu.audioInfo(engine.player)
            delay(2000)
        }
    }
    val nowHeight = remember(version, vInfo) { TrackMenu.currentHeight(engine.player) }

    LazyColumn(
        Modifier
            .width(340.dp)
            .fillMaxHeight()
            .background(C.Surface)
            .safeDrawingPadding()
            .padding(16.dp),
    ) {
        item { Text("Player settings", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 4.dp)) }

        // Quality: always shown. Auto adapts to the connection; a fixed choice locks that resolution.
        item { Section("Video quality") }
        item {
            val autoLabel = if (nowHeight > 0) "Auto (${nowHeight}p)" else "Auto"
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { if (ui.tv) { delay(120); runCatching { first.requestFocus() } } }
            Box(Modifier.focusRequester(first)) {
            Option(autoLabel, !manualQuality, hint = if (video.size > 1) "Best for your connection" else null) {
                TrackMenu.auto(engine.player, VIDEO); manualQuality = false
            }
            }
        }
        if (video.size > 1) {
            itemsIndexed(video) { _, o ->
                Option(o.label, manualQuality && o.selected) { TrackMenu.select(engine.player, o); manualQuality = true }
            }
        } else {
            item {
                Text(
                    if (video.size == 1) "This channel sends a single quality (${video[0].label})." else "Quality options appear when the stream offers them.",
                    color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }

        if (audio.isNotEmpty()) {
            item { Section(if (audio.size > 1) "Audio language (${audio.size})" else "Audio") }
            itemsIndexed(audio) { _, o -> Option(o.label, o.selected) { TrackMenu.select(engine.player, o) } }
        }

        // Online subtitles (films / episodes): OpenSubtitles + Kurdish auto-translation.
        val online = engine.online
        if (!engine.live && online.supported(PlayQueue.current())) {
            item { Section("Online subtitles") }
            val list = online.list
            val ext = engine.externalSub
            if (online.loading) item { Note("Finding subtitles…") }
            else if (list == null) item { Note("Online subtitles are not available right now.") }
            else {
                item { Option("Off", ext == null) { online.off() } }
                itemsIndexed(list.subs) { _, sub ->
                    Option(sub.label, ext != null && ext.lang == sub.lang && ext.label == sub.label) { online.use(sub) }
                }
                if (list.kurdishUrl != null) item {
                    val p = online.kurdishProgress
                    Option(
                        "Kurdish (Sorani) · auto",
                        ext?.label == "Kurdish (auto)",
                        hint = if (p != null) "Preparing… ${(p * 100).toInt()}%" else "Machine-translated from the ${SubPrep.label(list.kurdishFrom)} subtitle",
                    ) { online.useKurdish() }
                }
                if (list.subs.isEmpty() && list.kurdishUrl == null) item { Note("No online subtitles found for this title.") }
            }
            online.message?.let { m -> item { Note(m) } }
        }

        if (text.isNotEmpty()) {
            item { Section("Subtitles") }
            item { Option("Off", subsOff) { TrackMenu.disable(engine.player, TEXT); subsOff = true } }
            itemsIndexed(text) { _, o -> Option(o.label, !subsOff && o.selected) { TrackMenu.select(engine.player, o); subsOff = false } }
        }

        item { Section("Picture") }
        itemsIndexed(resizeModes) { _, m -> Option(m.second, ui.resize == m.first) { ui.resize = m.first } }

        if (!engine.live) {
            item { Section("Speed") }
            itemsIndexed(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)) { _, s ->
                Option(if (s == 1f) "Normal" else "${s}×", speed == s) { engine.player.setPlaybackSpeed(s); speed = s }
            }
        }

        if (text.isNotEmpty()) {
            item { Section("Subtitle size") }
            itemsIndexed(listOf("Small", "Normal", "Large", "Extra large")) { i, label ->
                Option(label, ui.subSize == i) { ui.subSize = i; PlayerPrefs.setSubSize(ctx, i) }
            }
        }

        item { Section("Sleep timer") }
        itemsIndexed(listOf(0, 15, 30, 60, 90)) { _, m ->
            val left = if (ui.sleepAt > 0 && ui.sleepMinutes == m) ((ui.sleepAt - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1) else 0
            Option(
                if (m == 0) "Off" else "$m minutes",
                ui.sleepMinutes == m,
                hint = if (left > 0) "Stops in $left min" else null,
            ) { ui.onSleep(m) }
        }

        item { Section("Background") }
        item {
            Option(
                "Keep sound with screen off",
                ui.background,
                hint = if (ui.background) "On: locks screen, sound continues" else "Off: pauses when you leave",
            ) {
                ui.background = !ui.background
                PlayerPrefs.setBackgroundAudio(ctx, ui.background)
                if (ui.background) runCatching { ctx.startService(Intent(ctx, PlaybackService::class.java)) }
            }
        }

        item { Section("Stream info") }
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(C.Surface2)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                InfoLine("Video", vInfo ?: "—")
                InfoLine("Audio", aInfo ?: "—")
                InfoLine("Type", if (engine.live) "Live" else "On demand")
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = C.Faint, fontSize = 12.sp, modifier = Modifier.width(56.dp))
        Text(value, color = C.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), color = C.Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun Option(label: String, selected: Boolean, hint: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .tvRing(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) C.EmberDim else C.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = if (selected) Color.White else C.Text,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
            )
            if (hint != null) Text(hint, color = C.Faint, fontSize = 11.sp)
        }
        if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, big: Boolean = false, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.tvRing(CircleShape).size(if (big) 56.dp else 46.dp).clip(CircleShape).background(Color(0x590A0B0F)),
    ) {
        Icon(icon, contentDescription = label, tint = Color.White)
    }
}

/**
 * LIVE pill. Filled red at the live edge; behind it, it shows how far ("-02:15") and a tap jumps
 * back to the live edge. During a catch-up recording a tap returns to the live channel.
 */
@Composable
private fun LivePill(engine: Engine, onAction: () -> Unit) {
    val atEdge = !engine.catchup && (!engine.liveSeekable || engine.liveBehind < 6_000)
    if (atEdge && !engine.liveSeekable) { LiveBadge(); return }
    Row(
        Modifier
            .padding(end = 6.dp)
            .tvRing(RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(if (atEdge) C.Ember else Color(0x590A0B0F))
            .border(1.dp, C.Ember, RoundedCornerShape(50))
            .clickable(enabled = !atEdge) { engine.goLive(); onAction() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (atEdge) Color.White else C.Ember))
        Spacer(Modifier.width(6.dp))
        val text = when {
            engine.catchup -> "LIVE"
            atEdge -> "LIVE"
            else -> "-${fmt(engine.liveBehind)}  LIVE"
        }
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

/** Timeline for a live channel with a window: drag inside it, ±10 s, and how far behind live. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.LiveTimeline(engine: Engine, ui: PlayerUiState) {
    val span = engine.duration.coerceAtLeast(1)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(1f) }
    val progress = if (dragging) dragValue else (engine.position.toFloat() / span).coerceIn(0f, 1f)
    RoundIcon(Icons.Filled.Replay10, "Back 10 seconds") { engine.seekBy(-10_000); ui.poke() }
    Slider(
        value = progress,
        onValueChange = { dragging = true; dragValue = it; ui.poke() },
        onValueChangeFinished = { engine.seekTo((dragValue * span).toLong()); dragging = false },
        colors = SliderDefaults.colors(thumbColor = C.Ember, activeTrackColor = C.Ember, inactiveTrackColor = Color(0x4DFFFFFF)),
        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
    )
    RoundIcon(Icons.Filled.Forward10, "Forward 10 seconds") { engine.seekBy(10_000); ui.poke() }
    Text(
        if (engine.liveBehind >= 6_000) "-${fmt(engine.liveBehind)}" else "LIVE",
        color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, end = 8.dp),
    )
}

/** "Rewind further" through the provider's archive: programme start, or 30 min / 1 h / 2 h ago. */
@Composable
private fun CatchupButton(engine: Engine, ui: PlayerUiState) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundIcon(Icons.Filled.History, "Rewind (catch-up)") { open = true; ui.keep() }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf<Pair<String, Int?>>(
                "Start of this programme" to null, "30 minutes ago" to 30, "1 hour ago" to 60, "2 hours ago" to 120,
            ).forEach { (label, minutes) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { open = false; engine.catchUp(minutes); ui.poke() },
                )
            }
        }
    }
}

@Composable
private fun LiveBadge() {
    Row(
        Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50)).background(C.Ember).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
        Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier) {
    Text(
        text, color = Color.White, fontSize = 13.sp,
        modifier = modifier.clip(RoundedCornerShape(50)).background(Color(0xCC15161C)).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}


/** White focus ring + slight zoom for the remote control (invisible on touch screens). */
private fun Modifier.tvRing(shape: androidx.compose.ui.graphics.Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .graphicsLayer { val z = if (focused) 1.06f else 1f; scaleX = z; scaleY = z }
        .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
}
