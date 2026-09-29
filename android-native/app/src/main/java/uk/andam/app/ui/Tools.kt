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
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(C.Surface, RoundedCornerShape(18.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    val release = UpdateState.available

    Card {
        Text("App update", color = C.Faint, style = MaterialTheme.typography.labelMedium)
        Text("Installed: ${BuildConfig.VERSION_NAME} (build ${Updater.currentBuild})", style = MaterialTheme.typography.titleMedium)
        if (release != null) {
            Text("New version available: build ${release.build}", color = Color(0xFF38E1C6), fontWeight = FontWeight.SemiBold)
        }
        note?.let { Text(it, color = C.Muted) }
        progress?.let {
            LinearProgressIndicator(progress = { it }, color = C.Ember, trackColor = C.Surface3, modifier = Modifier.fillMaxWidth())
            Text("Downloading… ${(it * 100).toInt()}%", color = C.Muted, fontSize = 12.sp)
        }
        Button(
            enabled = !busy,
            onClick = {
                busy = true; note = null
                scope.launch {
                    try {
                        val r = release ?: Updater.check()
                        UpdateState.available = r
                        if (r == null) {
                            note = "You have the latest version."
                        } else if (release != null) {
                            progress = 0f
                            val apk = Updater.download(context, r) { p -> progress = p }
                            progress = null
                            note = "Downloaded. Tap Install on the next screen."
                            Updater.install(context, apk)
                        }
                    } catch (e: Exception) {
                        progress = null
                        note = "Update check failed: ${e.message ?: "network error"}"
                    }
                    busy = false
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = C.Ember),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Text(
                when {
                    busy && progress != null -> "Downloading…"
                    busy -> "Checking…"
                    release != null -> "Download & install build ${release.build}"
                    else -> "Check for updates"
                },
            )
        }
    }
}

@Composable
fun DiagnosticsCard() {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf<Diagnostics.Result?>(null) }
    var internet by remember { mutableStateOf<Diagnostics.Result?>(null) }
    var stream by remember { mutableStateOf<Diagnostics.Result?>(null) }
    var iptv by remember { mutableStateOf<Diagnostics.Result?>(null) }
    val hevc = remember { Diagnostics.hevcSupported() }

    Card {
        Text("Server & speed test", color = C.Faint, style = MaterialTheme.typography.labelMedium)
        ResultRow("Andam server", server, running && server == null)
        ResultRow("Internet speed", internet, running && server != null && internet == null)
        ResultRow("Provider stream (relay)", stream, running && internet != null && stream == null)
        ResultRow("IPTV stream", iptv, running && stream != null && iptv == null)
        ResultRow("H.265 (HEVC) video", Diagnostics.Result(hevc, if (hevc) "Supported on this device" else "Not supported on this device"), false)
        Button(
            enabled = !running,
            onClick = {
                running = true; server = null; internet = null; stream = null; iptv = null
                scope.launch {
                    server = Diagnostics.server()
                    internet = Diagnostics.internet()
                    stream = Diagnostics.providerRoute()
                    iptv = Diagnostics.iptvRoute()
                    running = false
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = C.Surface3, contentColor = C.Text),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text(if (running) "Testing…" else "Run test") }
    }
}

@Composable
private fun ResultRow(label: String, result: Diagnostics.Result?, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(
            when {
                result == null -> if (active) C.Gold else C.Faint
                result.ok -> Color(0xFF38E1C6)
                else -> C.Ember
            },
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                result?.value ?: if (active) "Testing…" else "—",
                color = C.Muted, fontSize = 12.sp,
            )
        }
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
