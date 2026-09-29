package uk.andam.app.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Forward10
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
        engine.play(first)

        setContent {
            AndamTheme {
                PlayerScreen(engine, ui, onBack = { finish() }, onZap = { zap(it) }, onPick = { pick(it) })
            }
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val zapKeys = keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN ||
            (!ui.controls && !ui.panel && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN))
        if (zapKeys && PlayQueue.current()?.isLive == true) {
            zap(if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                engine.togglePlay(); ui.poke(); return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!ui.controls && !ui.panel) {
                ui.poke(); return true
            }
            KeyEvent.KEYCODE_MENU -> {
                ui.panel = !ui.panel; return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        engine.saveResume()
        engine.player.pause()
    }

    override fun onStart() {
        super.onStart()
        if (::engine.isInitialized && engine.error == null) engine.player.play()
    }

    override fun onDestroy() {
        if (::engine.isInitialized) engine.release()
        super.onDestroy()
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

    fun poke() {
        controls = true
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

    BackHandler(enabled = ui.panel || ui.settings) {
        ui.panel = false
        ui.settings = false
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
                }
            },
            update = { it.resizeMode = ui.resize },
            modifier = Modifier.fillMaxSize(),
        )

        // Tap layer: single tap toggles controls, double tap left/right skips 10 s on VOD.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(engine.live) {
                    detectTapGestures(
                        onTap = {
                            if (ui.panel || ui.settings) {
                                ui.panel = false; ui.settings = false
                            } else if (ui.controls) ui.controls = false else ui.poke()
                        },
                        onDoubleTap = { o ->
                            if (!engine.live) {
                                engine.seekBy(if (o.x < size.width / 2f) -10_000L else 10_000L)
                                ui.poke()
                            }
                        },
                    )
                },
        )

        if (engine.buffering && engine.error == null) {
            CircularProgressIndicator(
                color = C.Ember,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(52.dp),
            )
        }

        engine.status?.let {
            if (engine.error == null) Pill(it, Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
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
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
            modifier = Modifier.fillMaxHeight(),
        ) {
            ChannelPanel(ui, onPick = { onPick(it); ui.panel = false })
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
            if (engine.live) LiveBadge()
            if (PlayQueue.items.size > 1 && engine.live) {
                RoundIcon(Icons.AutoMirrored.Filled.FormatListBulleted, "Channels") { ui.panel = true }
            }
            RoundIcon(Icons.Filled.AspectRatio, "Aspect ratio") {
                val i = resizeModes.indexOfFirst { it.first == ui.resize }
                ui.resize = resizeModes[(i + 1) % resizeModes.size].first
                ui.poke()
            }
            RoundIcon(Icons.Filled.Tune, "Settings") { ui.settings = true }
        }

        // Centre: previous / play-pause / next.
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            if (zappable) RoundIcon(Icons.Filled.SkipPrevious, "Previous") { onZap(-1) }
            else if (!engine.live) RoundIcon(Icons.Filled.Replay10, "Back 10 seconds") { engine.seekBy(-10_000); ui.poke() }
            Box(
                Modifier
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

        // Bottom: timeline for movies and episodes only.
        if (!engine.live && engine.duration > 0) {
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }
            val progress = if (dragging) dragValue else (engine.position.toFloat() / engine.duration).coerceIn(0f, 1f)
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(fmt(if (dragging) (dragValue * engine.duration).toLong() else engine.position), color = Color.White, fontSize = 12.sp)
                Slider(
                    value = progress,
                    onValueChange = { dragging = true; dragValue = it; ui.poke() },
                    onValueChangeFinished = { engine.seekTo((dragValue * engine.duration).toLong()); dragging = false },
                    colors = SliderDefaults.colors(thumbColor = C.Ember, activeTrackColor = C.Ember, inactiveTrackColor = Color(0x4DFFFFFF)),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text(fmt(engine.duration), color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

/** Two columns: categories on the left, the chosen category's channels on the right. */
@Composable
private fun ChannelPanel(ui: PlayerUiState, onPick: (Int) -> Unit) {
    val groups = remember { listOf("" to "All") + PlayQueue.groups.map { it.id to it.name } }
    val items = PlayQueue.items
    val shown = remember(ui.panelGroup) {
        items.indices.filter { ui.panelGroup.isEmpty() || items[it].group == ui.panelGroup }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(ui.panelGroup) {
        val at = shown.indexOf(PlayQueue.index)
        if (at > 2) listState.scrollToItem(at - 2)
    }
    Row(
        Modifier
            .fillMaxHeight()
            .background(Color(0xF20A0B0F))
            .safeDrawingPadding()
            .padding(8.dp),
    ) {
        if (groups.size > 1) {
            LazyColumn(Modifier.width(170.dp).fillMaxHeight()) {
                itemsIndexed(groups) { _, g ->
                    val on = g.first == ui.panelGroup
                    Text(
                        g.second,
                        color = if (on) Color.White else C.Muted,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (on) C.EmberDim else Color.Transparent)
                            .clickable { ui.panelGroup = g.first }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        LazyColumn(state = listState, modifier = Modifier.width(320.dp).fillMaxHeight()) {
            itemsIndexed(shown) { _, idx ->
                val it = items[idx]
                val on = idx == PlayQueue.index
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) C.EmberDim else Color.Transparent)
                        .clickable { onPick(idx) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = it.logo.ifBlank { null }, contentDescription = null,
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(C.Surface2).padding(3.dp),
                    )
                    Text(
                        it.title, color = if (on) Color.White else C.Text, fontSize = 14.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(engine: Engine, ui: PlayerUiState) {
    // Re-read tracks whenever ExoPlayer reports a change.
    val version = engine.tracksVersion
    val audio = remember(version) { TrackMenu.options(engine.player, androidx.media3.common.C.TRACK_TYPE_AUDIO) }
    val text = remember(version) { TrackMenu.options(engine.player, androidx.media3.common.C.TRACK_TYPE_TEXT) }
    val video = remember(version) { TrackMenu.options(engine.player, androidx.media3.common.C.TRACK_TYPE_VIDEO) }
    var speed by remember { mutableFloatStateOf(engine.player.playbackParameters.speed) }

    LazyColumn(
        Modifier
            .width(340.dp)
            .fillMaxHeight()
            .background(C.Surface)
            .safeDrawingPadding()
            .padding(16.dp),
    ) {
        item { Text("Player settings", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        if (audio.size > 1) {
            item { Section("Audio") }
            itemsIndexed(audio) { _, o -> Option(o.label, o.selected) { TrackMenu.select(engine.player, o) } }
        }
        item { Section("Subtitles") }
        item { Option("Off", text.none { it.selected }) { TrackMenu.disable(engine.player, androidx.media3.common.C.TRACK_TYPE_TEXT) } }
        itemsIndexed(text) { _, o -> Option(o.label, o.selected) { TrackMenu.select(engine.player, o) } }
        if (video.size > 1) {
            item { Section("Quality") }
            item { Option("Auto", !TrackMenu.hasOverride(engine.player, androidx.media3.common.C.TRACK_TYPE_VIDEO)) { TrackMenu.auto(engine.player, androidx.media3.common.C.TRACK_TYPE_VIDEO) } }
            itemsIndexed(video) { _, o -> Option(o.label, false) { TrackMenu.select(engine.player, o) } }
        }
        item { Section("Picture") }
        itemsIndexed(resizeModes) { _, m -> Option(m.second, ui.resize == m.first) { ui.resize = m.first } }
        if (!engine.live) {
            item { Section("Speed") }
            itemsIndexed(listOf(0.75f, 1f, 1.25f, 1.5f)) { _, s ->
                Option("${s}×", speed == s) { engine.player.setPlaybackSpeed(s); speed = s }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), color = C.Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.White else C.Text,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) C.EmberDim else C.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    )
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, big: Boolean = false, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(if (big) 56.dp else 46.dp).clip(CircleShape).background(Color(0x590A0B0F)),
    ) {
        Icon(icon, contentDescription = label, tint = Color.White)
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
