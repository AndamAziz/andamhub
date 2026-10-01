package uk.andam.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import uk.andam.app.net.AdminApi
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * CEO admin panel inside the app (admins only): overview, activation codes and users.
 * Same data and actions as ip.andam.uk/admin. Every list shows everything — no caps.
 */

private enum class AdminTab(val label: String, val icon: ImageVector) {
    Overview("Overview", Icons.Filled.AdminPanelSettings),
    Codes("Codes", Icons.Filled.Key),
    Users("Users", Icons.Filled.Group),
}

private val SECTION_LABEL = linkedMapOf("live" to "Live TV", "movies" to "Movies", "series" to "Series")

@Composable
fun AdminScreen(startOnCodes: Boolean, openCreate: Boolean, onBack: () -> Unit) {
    BackHandler { onBack() }
    var tab by rememberSaveable { mutableStateOf(if (startOnCodes) AdminTab.Codes else AdminTab.Overview) }
    var reload by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        AdminHeader(onRefresh = { reload++ })
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(16.dp)).background(C.Surface).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AdminTab.entries.forEach { t ->
                val on = t == tab
                var focused by remember { mutableStateOf(false) }
                Row(
                    Modifier.weight(1f)
                        .onFocusChanged { focused = it.isFocused }
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) C.Ember else if (focused) C.Surface3 else Color.Transparent)
                        .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
                        .clickable { tab = t }
                        .padding(vertical = 11.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(t.icon, null, tint = if (on) Color.White else C.Muted, modifier = Modifier.size(18.dp))
                    Text(
                        t.label, color = if (on) Color.White else C.Muted, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                AdminTab.Overview -> OverviewTab(reload, onCreate = { tab = AdminTab.Codes })
                AdminTab.Codes -> CodesTab(reload, openCreate)
                AdminTab.Users -> UsersTab(reload)
            }
        }
    }
}

@Composable
private fun AdminHeader(onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3A0E16), C.Surface, C.Surface)))
            .border(1.dp, C.Hair, RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(C.Ember, Color(0xFF8E1F2C)))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.AdminPanelSettings, null, tint = Color.White, modifier = Modifier.size(26.dp)) }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text("CEO ADMIN", color = C.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
            Text("Control centre", color = C.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        IconBtn(Icons.Filled.Refresh, "Refresh", onRefresh)
    }
}

@Composable
private fun IconBtn(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).tvFocus(CircleShape).clip(CircleShape).background(C.Surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = C.Text, modifier = Modifier.size(20.dp)) }
}

// ------------------------------------------------------------------ Overview

@Composable
private fun OverviewTab(reload: Int, onCreate: () -> Unit) {
    var data by remember { mutableStateOf<AdminApi.Overview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) {
        error = null
        runCatching { AdminApi.overview() }.onSuccess { data = it }.onFailure { error = it.message ?: "Could not load" }
    }
    val o = data
    if (o == null) { if (error != null) ErrorBox(error!!) else Busy(); return }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard(Icons.Filled.Dns, C.Ember, "${o.activeProviders}", "Active providers", "${o.totalProviders} total", Modifier.weight(1f))
                    StatCard(Icons.Filled.Group, Sky, big(o.totalUsers), "Registered users", "All accounts", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard(
                        Icons.Filled.Router, if (o.relay.ok) Teal else C.Ember,
                        if (o.relay.ok) "Healthy" else "Down", "Relay proxy",
                        if (o.relay.ok) "${o.relay.ms} ms" else o.relay.detail.take(40), Modifier.weight(1f),
                    )
                    StatCard(
                        Icons.Filled.ErrorOutline, if (o.errors.isEmpty()) Teal else Amber,
                        "${o.errors.size}", "Recent errors", "Last ${o.errors.size} logged", Modifier.weight(1f),
                    )
                }
            }
        }
        item {
            SettingsGroup(null) {
                SettingsRow(
                    Icons.Filled.Key, C.Gold, "Create activation code", "Unlock Live TV, Movies or Series for a viewer",
                    trailing = { SettingsPill("New", C.Ember, filled = true) }, onClick = onCreate,
                )
            }
        }
        item {
            SettingsGroup("Providers") {
                if (o.providers.isEmpty()) SettingsBlock { Text("No providers yet.", color = C.Muted) }
                o.providers.forEach { p ->
                    SettingsRow(
                        Icons.Filled.Dns, if (p.active) Teal else C.Faint, p.name, chevron = false,
                        trailing = { SettingsPill(if (p.active) "Active" else "Off", if (p.active) Teal else C.Muted) },
                    )
                }
            }
        }
        item {
            SettingsGroup("Recent sign-ins") {
                if (o.logins.isEmpty()) SettingsBlock { Text("No sign-ins recorded.", color = C.Muted) }
                o.logins.forEach { l ->
                    SettingsRow(Icons.AutoMirrored.Filled.Login, Violet, l.email.ifBlank { "Unknown" }, deviceOf(l.device), value = ago(l.at), chevron = false)
                }
            }
        }
        item {
            SettingsGroup("Recent errors") {
                if (o.errors.isEmpty()) SettingsBlock { Text("No playback errors. Everything is running.", color = C.Muted) }
                o.errors.forEach { e ->
                    SettingsRow(
                        Icons.Filled.ErrorOutline, Amber,
                        e.message.ifBlank { "Playback error" },
                        listOf(e.kind, if (e.status > 0) "HTTP ${e.status}" else "").filter { it.isNotBlank() }.joinToString(" · "),
                        value = ago(e.at), chevron = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(icon: ImageVector, tint: Color, value: String, label: String, sub: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(C.Surface).border(1.dp, C.Hair, RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        IconTile(icon, tint, 36)
        Spacer(Modifier.height(12.dp))
        Text(value, color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = C.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(sub, color = C.Faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ Codes

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodesTab(reload: Int, openCreate: Boolean) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<AdminApi.Codes?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var local by remember { mutableIntStateOf(0) }
    var creating by rememberSaveable { mutableStateOf(openCreate) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var q by rememberSaveable { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<AdminApi.Code?>(null) }

    LaunchedEffect(reload, local) {
        error = null
        runCatching { AdminApi.codes() }.onSuccess { data = it }.onFailure { error = it.message ?: "Could not load codes" }
    }
    fun act(msg: String, block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onSuccess { note = msg; local++ }.onFailure { note = it.message ?: "Failed" }
        }
    }

    val d = data
    if (d == null) { if (error != null) ErrorBox(error!!) { local++ } else Busy() }
    else {
        val counts = d.codes.groupingBy { it.status }.eachCount()
        val shown = d.codes.filter { c ->
            (filter == "all" || c.status == filter) &&
                (q.isBlank() || c.code.contains(q, true) || c.note.contains(q, true) ||
                    c.provider.contains(q, true) || c.redeemedBy.any { it.email.contains(q, true) })
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { CreateCta { creating = true } }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("All ${d.codes.size}", filter == "all") { filter = "all" }
                    Chip("Active ${counts["active"] ?: 0}", filter == "active") { filter = "active" }
                    Chip("Used ${counts["used"] ?: 0}", filter == "used") { filter = "used" }
                    Chip("Expired ${counts["expired"] ?: 0}", filter == "expired") { filter = "expired" }
                    Chip("Revoked ${counts["revoked"] ?: 0}", filter == "revoked") { filter = "revoked" }
                }
            }
            item {
                if (LocalTv.current) TvSearchButton(q, { q = it }, "Search codes, notes or emails")
                else SearchField(q, { q = it }, "Search codes, notes or emails")
            }
            note?.let { n -> item { Text(n, color = Teal, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp)) } }
            if (shown.isEmpty()) item {
                Text(
                    if (d.codes.isEmpty()) "No codes yet — create the first one above." else "No codes match.",
                    color = C.Muted, modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center,
                )
            }
            items(shown, key = { it.id }) { c ->
                CodeCard(
                    c,
                    onRenew = { act("Renewed ${c.code} for 30 days") { AdminApi.renewCode(c.id, 30, if (c.status == "used") 1 else 0) } },
                    onRevoke = { act("Revoked ${c.code}") { AdminApi.revokeCode(c.id) } },
                    onDelete = { confirmDelete = c },
                )
            }
        }
        if (creating) CreateCodeDialog(d.providers, onDismiss = { creating = false }, onCreated = { local++ })
    }

    confirmDelete?.let { c ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = C.Surface,
            title = { Text("Delete ${c.code}?") },
            text = {
                Text(
                    if (c.uses > 0) "Viewers who redeemed this code lose the sections it unlocked." else "This code has not been used yet.",
                    color = C.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; act("Deleted ${c.code}") { AdminApi.deleteCode(c.id) } }) {
                    Text("Delete", color = C.Ember, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel", color = C.Muted) } },
        )
    }
}

@Composable
private fun CreateCta(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(C.Ember, Color(0xFFB0213A))))
            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(20.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text("Create activation code", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Choose sections, duration and uses", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        }
        Icon(Icons.Filled.Key, null, tint = Color.White.copy(alpha = 0.85f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodeCard(c: AdminApi.Code, onRenew: () -> Unit, onRevoke: () -> Unit, onDelete: () -> Unit) {
    val (statusText, statusColor) = when (c.status) {
        "active" -> "Active" to Teal
        "used" -> "Used up" to Sky
        "expired" -> "Expired" to Amber
        else -> "Revoked" to C.Ember
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Surface)
            .border(1.dp, C.Hair, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                c.code, color = C.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp, modifier = Modifier.weight(1f), maxLines = 1,
            )
            SettingsPill(statusText, statusColor)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            c.sections.forEach { s -> Tag(SECTION_LABEL[s] ?: s, sectionColor(s)) }
            Tag(c.provider, C.Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Fact("Uses", "${c.uses} / ${c.maxUses}")
            Fact("Expires", c.expiresAt?.let { date(it) } ?: "Never")
            Fact("Created", date(c.createdAt))
        }
        if (c.note.isNotBlank()) Text(c.note, color = C.Muted, fontSize = 13.sp)
        if (c.redeemedBy.isNotEmpty()) {
            Text(
                "Redeemed by " + c.redeemedBy.take(3).joinToString(", ") { it.email } +
                    if (c.redeemedBy.size > 3) " +${c.redeemedBy.size - 3} more" else "",
                color = C.Faint, fontSize = 12.sp,
            )
        }
        CodeActions(c.code, c.status, onRenew, onRevoke, onDelete)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodeActions(code: String, status: String, onRenew: () -> Unit, onRevoke: () -> Unit, onDelete: () -> Unit) {
    val clip = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionPill(Icons.Filled.ContentCopy, if (copied) "Copied" else "Copy", Teal) { clip.setText(AnnotatedString(code)); copied = true }
        ActionPill(Icons.Filled.Share, "Share", Sky) { shareCode(context, code) }
        ActionPill(Icons.Filled.History, "Renew 30 days", Amber, onRenew)
        if (status == "active") ActionPill(Icons.Filled.Block, "Revoke", C.Muted, onRevoke)
        ActionPill(Icons.Filled.DeleteOutline, "Delete", C.Ember, onDelete)
    }
}

@Composable
private fun ActionPill(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.tvFocus(RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(tint.copy(alpha = 0.13f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label.uppercase(), color = C.Faint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(value, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun sectionColor(s: String) = when (s) {
    "live" -> Color(0xFFFF5A6B)
    "movies" -> Amber
    else -> Sky
}

private fun shareCode(context: android.content.Context, code: String) {
    val text = "Your Andam activation code: $code\n\nOpen the Andam app, go to Live TV and enter this code to unlock it."
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, "Share code").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ---------------- create dialog

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateCodeDialog(providers: List<AdminApi.ProviderRef>, onDismiss: () -> Unit, onCreated: () -> Unit) {
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf("") }
    var sections by remember { mutableStateOf(setOf("live", "movies", "series")) }
    var days by remember { mutableIntStateOf(30) }
    var uses by remember { mutableStateOf("1") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth()
                .clip(RoundedCornerShape(26.dp)).background(C.Surface)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val done = created
            if (done != null) {
                CreatedView(done) { onCreated(); onDismiss() }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Filled.Key, C.Gold, 40)
                Column(Modifier.padding(start = 12.dp)) {
                    Text("New activation code", color = C.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("The viewer enters it in Live TV", color = C.Muted, fontSize = 12.sp)
                }
            }

            FieldLabel("Unlocks")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple("live", "Live TV", Icons.Filled.LiveTv), Triple("movies", "Movies", Icons.Filled.Movie), Triple("series", "Series", Icons.Filled.Tv))
                    .forEach { (id, label, icon) ->
                        val on = id in sections
                        SectionToggle(label, icon, sectionColor(id), on, Modifier.weight(1f)) {
                            sections = if (on) sections - id else sections + id
                        }
                    }
            }

            FieldLabel("Provider")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("All providers", provider.isEmpty()) { provider = "" }
                providers.forEach { p -> Chip(p.name, provider == p.id) { provider = p.id } }
            }

            FieldLabel("Valid for")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "1 day", 7 to "7 days", 30 to "30 days", 90 to "3 months", 180 to "6 months", 365 to "1 year", 0 to "No expiry")
                    .forEach { (n, label) -> Chip(label, days == n) { days = n } }
            }

            FieldLabel("How many viewers can use it")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("1", "2", "5", "10", "50", "100").forEach { n -> Chip(n, uses == n) { uses = n } }
            }
            AdminField(uses, { v -> uses = v.filter { it.isDigit() }.take(7) }, "Custom number", KeyboardType.Number)

            FieldLabel("Note (optional)")
            AdminField(note, { note = it.take(200) }, "e.g. customer name or phone")

            error?.let { Text(it, color = C.Ember, fontSize = 13.sp) }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f).height(52.dp).tvFocus(RoundedCornerShape(14.dp))) {
                    Text("Cancel", color = C.Muted)
                }
                val count = uses.toIntOrNull() ?: 0
                val ready = sections.isNotEmpty() && count > 0 && !busy
                Box(
                    Modifier.weight(1.4f).height(52.dp).tvFocus(RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                        .background(if (ready) C.Ember else C.Surface3)
                        .clickable(enabled = ready) {
                            busy = true; error = null
                            scope.launch {
                                runCatching { AdminApi.createCode(provider.ifBlank { null }, sections.toList(), count, days, note.trim()) }
                                    .onSuccess { created = it }
                                    .onFailure { error = it.message ?: "Could not create the code" }
                                busy = false
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    else Text("Create code", color = if (ready) Color.White else C.Faint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun CreatedView(code: String, onDone: () -> Unit) {
    val clip = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Teal.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Key, null, tint = Teal, modifier = Modifier.size(32.dp))
        }
        Text("Code created", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            code, color = C.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(C.Surface2).border(1.dp, C.Hair, RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionPill(Icons.Filled.ContentCopy, if (copied) "Copied" else "Copy", Teal) { clip.setText(AnnotatedString(code)); copied = true }
            ActionPill(Icons.Filled.Share, "Share", Sky) { shareCode(context, code) }
        }
        Box(
            Modifier.fillMaxWidth().height(52.dp).tvFocus(RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                .background(C.Ember).clickable(onClick = onDone),
            contentAlignment = Alignment.Center,
        ) { Text("Done", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
    }
}

@Composable
private fun SectionToggle(label: String, icon: ImageVector, tint: Color, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.tvFocus(RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
            .background(if (on) tint.copy(alpha = 0.16f) else C.Surface2)
            .border(1.5.dp, if (on) tint else C.Hair, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (on) tint else C.Faint, modifier = Modifier.size(22.dp))
        Text(label, color = if (on) C.Text else C.Faint, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text.uppercase(), color = C.Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
}

@Composable
private fun AdminField(value: String, onChange: (String) -> Unit, placeholder: String, type: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(placeholder, color = C.Faint) },
        keyboardOptions = KeyboardOptions(keyboardType = type),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = C.Surface2, unfocusedContainerColor = C.Surface2,
            focusedBorderColor = C.Ember, unfocusedBorderColor = C.Hair, cursorColor = C.Ember,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ------------------------------------------------------------------ Users

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsersTab(reload: Int) {
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<AdminApi.User>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var local by remember { mutableIntStateOf(0) }
    var q by rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf<AdminApi.User?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload, local) {
        error = null
        runCatching { AdminApi.users() }
            .onSuccess { list -> users = list; open = open?.let { o -> list.firstOrNull { it.id == o.id } } }
            .onFailure { error = it.message ?: "Could not load users" }
    }
    fun act(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onSuccess { note = null; local++ }.onFailure { note = it.message ?: "Failed" } }
    }

    val list = users
    if (list == null) { if (error != null) ErrorBox(error!!) { local++ } else Busy(); return }
    val shown = list.filter { q.isBlank() || it.email.contains(q, true) || it.name.contains(q, true) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            if (LocalTv.current) TvSearchButton(q, { q = it }, "Search users")
            else SearchField(q, { q = it }, "Search users")
        }
        item {
            Text(
                "${shown.size} of ${list.size} users · ${list.count { "live" in it.sections }} with Live TV",
                color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp),
            )
        }
        note?.let { n -> item { Text(n, color = C.Ember, fontSize = 13.sp) } }
        items(shown, key = { it.id }) { u -> UserCard(u) { open = u } }
    }

    open?.let { u ->
        Dialog(onDismissRequest = { open = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(C.Bg)
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(u.email, 52)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(u.email, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Joined ${date(u.createdAt)} · last seen ${u.lastLoginAt?.let { ago(it) } ?: "never"}", color = C.Muted, fontSize = 12.sp)
                    }
                }
                SettingsGroup("Unlocked sections") {
                    SECTION_LABEL.forEach { (id, label) ->
                        val icon = when (id) { "live" -> Icons.Filled.LiveTv; "movies" -> Icons.Filled.Movie; else -> Icons.Filled.Tv }
                        SettingsSwitch(icon, sectionColor(id), label, null, id in u.sections) { on -> act { AdminApi.setSection(u.id, id, on) } }
                    }
                }
                SettingsGroup("Account") {
                    SettingsSwitch(Icons.Filled.AdminPanelSettings, C.Gold, "Admin", "Full access to this panel", u.admin) { on -> act { AdminApi.setAdmin(u.id, on) } }
                    SettingsSwitch(Icons.Filled.Block, C.Ember, "Suspended", "Blocks sign-in for this account", u.suspended) { on -> act { AdminApi.setSuspended(u.id, on) } }
                }
                note?.let { Text(it, color = C.Ember, fontSize = 13.sp) }
                Box(
                    Modifier.fillMaxWidth().height(50.dp).tvFocus(RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                        .background(C.Surface2).clickable { open = null },
                    contentAlignment = Alignment.Center,
                ) { Text("Close", color = C.Text, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserCard(u: AdminApi.User, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(18.dp))
            .background(if (focused) C.Surface3 else C.Surface)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else C.Hair, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(u.email, 44)
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(u.email.ifBlank { u.name.ifBlank { "Unknown" } }, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (u.admin) Tag("Admin", C.Gold)
                if (u.suspended) Tag("Suspended", C.Ember)
                SECTION_LABEL.forEach { (id, label) -> if (id in u.sections) Tag(label, sectionColor(id)) }
                if (u.sections.isEmpty() && !u.admin) Tag("IPTV only", C.Faint)
            }
        }
        Text(u.lastLoginAt?.let { ago(it) } ?: "—", color = C.Faint, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Avatar(email: String, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Brush.linearGradient(listOf(C.Ember, Color(0xFF8E1F2C)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(email.trim().firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = (size * 0.42f).sp, fontWeight = FontWeight.Bold)
    }
}

// ------------------------------------------------------------------ helpers

private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

private fun date(iso: String): String =
    runCatching { DAY.format(Instant.parse(iso).atZone(ZoneId.systemDefault())) }
        .getOrElse { runCatching { DAY.format(java.time.OffsetDateTime.parse(iso)) }.getOrDefault(iso.take(10)) }

private fun instant(iso: String): Instant? =
    runCatching { Instant.parse(iso) }.getOrNull() ?: runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrNull()

private fun ago(iso: String): String {
    val t = instant(iso) ?: return iso.take(10)
    val d = Duration.between(t, Instant.now())
    return when {
        d.toMinutes() < 1 -> "now"
        d.toMinutes() < 60 -> "${d.toMinutes()}m ago"
        d.toHours() < 24 -> "${d.toHours()}h ago"
        d.toDays() < 30 -> "${d.toDays()}d ago"
        else -> date(iso)
    }
}

private fun deviceOf(agent: String): String = when {
    agent.contains("AndamApp", true) -> "Android app"
    agent.contains("AndamDesktop", true) || agent.contains("Electron", true) -> "Windows app"
    agent.contains("Android", true) -> "Android browser"
    agent.contains("iPhone", true) || agent.contains("iPad", true) -> "iPhone / iPad"
    agent.contains("Windows", true) -> "Windows browser"
    agent.contains("Mac", true) -> "Mac"
    agent.isBlank() -> "Unknown device"
    else -> "Browser"
}

private fun big(n: Int): String = java.text.NumberFormat.getIntegerInstance(Locale.US).format(n)
