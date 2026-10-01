package uk.andam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.andam.app.BuildConfig
import uk.andam.app.Diagnostics
import uk.andam.app.Updater

/** Shared between the start-up check and the Account screen. */
object UpdateState {
    var available by mutableStateOf<Updater.Release?>(null)
}

@Composable
fun UpdateSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var upToDate by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    val release = UpdateState.available

    fun run() {
        if (busy) return
        busy = true; note = null
        scope.launch {
            try {
                val r = release ?: Updater.check()
                UpdateState.available = r
                upToDate = r == null
                if (r != null && release != null) {
                    progress = 0f
                    val apk = Updater.download(context, r) { p -> progress = p }
                    progress = null
                    note = "Downloaded. Tap Install on the next screen."
                    Updater.install(context, apk)
                }
            } catch (e: Exception) {
                progress = null
                note = "Couldn't check: ${e.message ?: "network error"}"
            }
            busy = false
        }
    }

    SettingsGroup("App") {
        SettingsRow(
            Icons.Filled.SystemUpdate, if (release != null) C.Ember else Teal,
            title = if (release != null) "Update available" else "App version",
            subtitle = note ?: when {
                busy && progress != null -> "Downloading build ${release?.build}…"
                busy -> "Checking for updates…"
                release != null -> "Build ${release.build} is ready to install"
                upToDate -> "You have the latest version"
                else -> "${BuildConfig.VERSION_NAME} · build ${Updater.currentBuild}"
            },
            trailing = {
                when {
                    busy -> SettingsPill(if (progress != null) "${((progress ?: 0f) * 100).toInt()}%" else "…", C.Muted)
                    release != null -> SettingsPill("Install", C.Ember, filled = true)
                    upToDate -> SettingsPill("Up to date", Teal)
                    else -> SettingsPill("Check", C.Text)
                }
            },
            onClick = { run() },
        )
        progress?.let {
            SettingsBlock {
                LinearProgressIndicator(progress = { it }, color = C.Ember, trackColor = C.Surface3, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Real data per provider / playlist, filled in by "Run test". */
private sealed interface Info {
    data object Busy : Info
    data class Provider(val v: uk.andam.app.net.ProviderInfo) : Info
    data class Playlist(val channels: Int, val groups: Int, val ms: Long) : Info
    data class Failed(val msg: String) : Info
}

@Composable
fun DiagnosticsSettings() {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf<Diagnostics.Result?>(null) }
    var internet by remember { mutableStateOf<Diagnostics.Result?>(null) }
    var stream by remember { mutableStateOf<Diagnostics.Result?>(null) }
    var iptv by remember { mutableStateOf<Diagnostics.Result?>(null) }
    val providerInfo = remember { androidx.compose.runtime.mutableStateMapOf<String, Info>() }
    val playlistInfo = remember { androidx.compose.runtime.mutableStateMapOf<String, Info>() }
    val hevc = remember { Diagnostics.hevcSupported() }

    fun run() {
        if (running) return
        running = true; started = true
        server = null; internet = null; stream = null; iptv = null
        providerInfo.clear(); playlistInfo.clear()
        scope.launch {
            server = Diagnostics.server()
            // Content counts load in parallel with the speed tests.
            Store.providers.forEach { p ->
                providerInfo[p.id] = Info.Busy
                launch {
                    providerInfo[p.id] = runCatching { Info.Provider(uk.andam.app.net.Api.info(p.id)) }
                        .getOrElse { Info.Failed(it.message ?: "Could not read the provider") }
                }
            }
            Store.iptvSources.forEach { src ->
                playlistInfo[src.id] = Info.Busy
                launch {
                    val t0 = System.nanoTime()
                    playlistInfo[src.id] = runCatching {
                        val l = uk.andam.app.net.Api.iptvChannels(src.id)
                        Info.Playlist(l.channels.size, l.groups.size, (System.nanoTime() - t0) / 1_000_000)
                    }.getOrElse { Info.Failed(it.message ?: "Could not read the playlist") }
                }
            }
            internet = Diagnostics.internet()
            stream = Diagnostics.providerRoute()
            iptv = Diagnostics.iptvRoute()
            running = false
        }
    }

    val results = listOfNotNull(server, internet, stream, iptv)
    val failed = results.count { !it.ok }
    SettingsGroup("Connection") {
        SettingsRow(
            Icons.Filled.Speed, Sky, "Server & speed test",
            subtitle = when {
                running -> "Testing… this takes a few seconds"
                !started -> "Check the server, your internet and providers"
                failed == 0 -> "Everything is working"
                else -> "$failed check${if (failed > 1) "s" else ""} need attention"
            },
            trailing = {
                when {
                    running -> SettingsPill("Testing…", C.Muted)
                    !started -> SettingsPill("Run test", Sky, filled = true)
                    failed == 0 -> SettingsPill("All good", Teal)
                    else -> SettingsPill("Run again", C.Ember)
                }
            },
            onClick = { run() },
        )
        if (started) {
            SettingsBlock {
                ResultRow("Andam server", server, running && server == null)
                ResultRow("Internet speed", internet, running && server != null && internet == null)
                ResultRow("Provider stream", stream, running && internet != null && stream == null)
                ResultRow("IPTV stream", iptv, running && stream != null && iptv == null)
                ResultRow("H.265 (HEVC) video", Diagnostics.Result(hevc, if (hevc) "Supported" else "Not supported"), false)
            }
            val blocks = Store.providers.filter { providerInfo[it.id] != null } + Store.iptvSources.filter { playlistInfo[it.id] != null }
            if (blocks.isNotEmpty()) SettingsBlock {
                Store.providers.forEach { p -> providerInfo[p.id]?.let { ProviderBlock(p.name, it) } }
                Store.iptvSources.forEach { s -> playlistInfo[s.id]?.let { PlaylistBlock(s.name, it) } }
            }
        }
    }
}

@Composable
private fun ProviderBlock(name: String, info: Info) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(C.Surface2, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.Dns, C.Ember)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = C.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val sub = when (info) {
                    is Info.Provider -> info.v.server.ifBlank { "Provider" } + " · answered in ${info.v.ms} ms"
                    is Info.Busy -> "Reading provider…"
                    is Info.Failed -> info.msg
                    else -> ""
                }
                Text(sub, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (info is Info.Provider) {
                val v = info.v
                val active = v.reachable && (v.status.isBlank() || v.status.equals("Active", true))
                StatusChip(if (!v.reachable) "Offline" else v.status.ifBlank { "Online" }, active)
            }
        }
        when (info) {
            is Info.Busy -> LinearProgressIndicator(color = C.Ember, trackColor = C.Surface3, modifier = Modifier.fillMaxWidth())
            is Info.Provider -> {
                val v = info.v
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(Icons.Filled.LiveTv, "Live", v.live, v.liveCategories, Color(0xFFFF5A6B), Modifier.weight(1f))
                    StatTile(Icons.Filled.Movie, "Movies", v.vod, v.vodCategories, Color(0xFFFFB547), Modifier.weight(1f))
                    StatTile(Icons.Filled.Tv, "Series", v.series, v.seriesCategories, Color(0xFF5AA9FF), Modifier.weight(1f))
                }
                val facts = listOfNotNull(
                    v.expires.takeIf { it.isNotBlank() }?.let { "Expires " + it.take(10) },
                    if (v.maxConnections > 0) "Connections ${v.activeConnections}/${v.maxConnections}" else null,
                    if (v.trial) "Trial" else null,
                )
                if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), color = C.Faint, fontSize = 12.sp)
            }
            else -> {}
        }
    }
}

@Composable
private fun PlaylistBlock(name: String, info: Info) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(C.Surface2, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(Icons.Filled.Wifi, Color(0xFF38E1C6))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = C.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                when (info) {
                    is Info.Playlist -> "IPTV playlist · ${info.groups} categories · ${info.ms} ms"
                    is Info.Busy -> "Reading playlist…"
                    is Info.Failed -> info.msg
                    else -> ""
                },
                color = C.Muted, fontSize = 12.sp,
            )
        }
        if (info is Info.Playlist) {
            Column(horizontalAlignment = Alignment.End) {
                Text(big(info.channels), color = C.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("channels", color = C.Faint, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun StatTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: Int, cats: Int, tint: Color, modifier: Modifier) {
    Column(
        modifier
            .background(C.Surface3, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        androidx.compose.material3.Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(8.dp))
        Text(if (count >= 0) big(count) else "—", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1)
        Text(label, color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (cats >= 0) Text("$cats categories", color = C.Faint, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun IconBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(36.dp).background(tint.copy(alpha = 0.16f), RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun StatusChip(text: String, ok: Boolean) {
    val c = if (ok) Color(0xFF38E1C6) else C.Ember
    Text(
        text,
        color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(c.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private fun big(n: Int): String = java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(n)

@Composable
private fun ResultRow(label: String, result: Diagnostics.Result?, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Dot(
            when {
                result == null -> if (active) C.Gold else C.Faint
                result.ok -> Teal
                else -> C.Ember
            },
        )
        Text(label, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp).weight(1f))
        Text(
            result?.value ?: if (active) "Testing…" else "Waiting",
            color = if (result != null && !result.ok) C.Ember else C.Muted, fontSize = 13.sp,
            maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.padding(start = 12.dp).widthIn(max = 220.dp),
        )
    }
}

/** Slim banner shown above the tabs when a newer build exists. */
@Composable
fun UpdateBanner(onOpen: () -> Unit) {
    val r = UpdateState.available ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(C.EmberDim, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("New version (build ${r.build}) is ready", color = C.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Button(
            onClick = onOpen,
            colors = ButtonDefaults.buttonColors(containerColor = C.Ember),
            shape = RoundedCornerShape(10.dp),
        ) { Text("Update", fontSize = 13.sp) }
    }
}
