package uk.andam.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import kotlinx.coroutines.launch
import uk.andam.app.net.AdminApi
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * CEO admin panel inside the app (admins only): overview, activation codes and users.
 * Standard mobile layout: one column, single-line filters, compact cards, and full pages
 * (with a fixed action bar) instead of pop-ups for creating a code or editing a user.
 * Every list shows everything — no caps.
 */

private enum class AdminTab(val label: String, val icon: ImageVector) {
    Overview("Overview", Icons.Filled.AdminPanelSettings),
    Codes("Codes", Icons.Filled.Key),
    Users("Users", Icons.Filled.Group),
}

private val SECTION_LABEL = linkedMapOf("live" to "Live TV", "movies" to "Movies", "series" to "Series")

/** Sub-page shown over the tabs. */
private sealed interface Page {
    data object Tabs : Page
    data object Create : Page
    data class Created(val code: String) : Page
    data class EditUser(val id: String) : Page
}

@Composable
fun AdminScreen(startOnCodes: Boolean, openCreate: Boolean, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(if (startOnCodes) AdminTab.Codes else AdminTab.Overview) }
    var page by remember { mutableStateOf<Page>(if (openCreate) Page.Create else Page.Tabs) }
    var reload by remember { mutableIntStateOf(0) }
    BackHandler { if (page != Page.Tabs) page = Page.Tabs else onBack() }

    when (val p = page) {
        Page.Create -> CreateCodePage(
            onCancel = { page = Page.Tabs },
            onCreated = { code -> reload++; tab = AdminTab.Codes; page = Page.Created(code) },
        )
        is Page.Created -> CreatedPage(p.code) { page = Page.Tabs }
        is Page.EditUser -> UserPage(p.id, onClose = { reload++; page = Page.Tabs })
        Page.Tabs -> Column(Modifier.fillMaxSize()) {
            TabBar(tab, onPick = { tab = it }, onRefresh = { reload++ })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    AdminTab.Overview -> OverviewTab(reload, onCreate = { page = Page.Create })
                    AdminTab.Codes -> CodesTab(reload, onCreate = { page = Page.Create })
                    AdminTab.Users -> UsersTab(reload, onOpen = { page = Page.EditUser(it) })
                }
            }
        }
    }
}

@Composable
private fun TabBar(tab: AdminTab, onPick: (AdminTab) -> Unit, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(C.Surface).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            AdminTab.entries.forEach { t ->
                val on = t == tab
                var focused by remember { mutableStateOf(false) }
                Row(
                    Modifier.weight(1f)
                        .onFocusChanged { focused = it.isFocused }
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (on) C.Ember else if (focused) C.Surface3 else Color.Transparent)
                        .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(11.dp)) else Modifier)
                        .clickable { onPick(t) }
                        .padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(t.icon, null, tint = if (on) Color.White else C.Muted, modifier = Modifier.size(16.dp))
                    Text(
                        t.label, color = if (on) Color.White else C.Muted, fontSize = 13.sp, maxLines = 1,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 5.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        RoundIcon(Icons.Filled.Refresh, "Refresh", C.Text, onClick = onRefresh)
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, tint: Color, size: Int = 40, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).tvFocus(CircleShape).clip(CircleShape).background(tint.copy(alpha = 0.12f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size((size * 0.48f).dp)) }
}

@Composable
private fun <T> Loaded(state: T?, error: String?, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when {
        state != null -> content(state)
        error != null -> ErrorBox(error, onRetry)
        else -> Busy()
    }
}

// ------------------------------------------------------------------ Overview

@Composable
private fun OverviewTab(reload: Int, onCreate: () -> Unit) {
    var data by remember { mutableStateOf<AdminApi.Overview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var again by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload, again) {
        error = null
        runCatching { AdminApi.overview() }.onSuccess { data = it }.onFailure { error = it.message ?: "Could not load" }
    }
    Loaded(data, error, { again++ }) { o ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatCard(Icons.Filled.Dns, C.Ember, "${o.activeProviders}/${o.totalProviders}", "Providers", Modifier.weight(1f))
                        StatCard(Icons.Filled.People, Sky, big(o.totalUsers), "Users", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatCard(
                            Icons.Filled.Router, if (o.relay.ok) Teal else C.Ember,
                            if (o.relay.ok) "${o.relay.ms} ms" else "Down", "Relay", Modifier.weight(1f),
                        )
                        StatCard(
                            Icons.Filled.ErrorOutline, if (o.errors.isEmpty()) Teal else Amber,
                            "${o.errors.size}", "Recent errors", Modifier.weight(1f),
                        )
                    }
                }
            }
            item {
                SettingsGroup(null) {
                    SettingsRow(Icons.Filled.Key, C.Gold, "Create activation code", "Unlock sections for a viewer", onClick = onCreate)
                }
            }
            item {
                SettingsGroup("Providers") {
                    if (o.providers.isEmpty()) SettingsBlock { Text("No providers yet.", color = C.Muted, fontSize = 13.sp) }
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
                    if (o.logins.isEmpty()) SettingsBlock { Text("No sign-ins recorded.", color = C.Muted, fontSize = 13.sp) }
                    o.logins.forEach { l ->
                        SettingsRow(Icons.AutoMirrored.Filled.Login, Violet, l.email.ifBlank { "Unknown" }, "${deviceOf(l.device)} · ${ago(l.at)}", chevron = false)
                    }
                }
            }
            item {
                SettingsGroup("Recent errors") {
                    if (o.errors.isEmpty()) SettingsBlock { Text("No playback errors.", color = C.Muted, fontSize = 13.sp) }
                    o.errors.forEach { e ->
                        SettingsRow(
                            Icons.Filled.ErrorOutline, Amber, e.message.ifBlank { "Playback error" },
                            listOf(e.kind, if (e.status > 0) "HTTP ${e.status}" else "", ago(e.at)).filter { it.isNotBlank() }.joinToString(" · "),
                            chevron = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(icon: ImageVector, tint: Color, value: String, label: String, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(C.Surface).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint, 32)
        Column(Modifier.padding(start = 10.dp)) {
            Text(value, color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------ Codes

@Composable
private fun CodesTab(reload: Int, onCreate: () -> Unit) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<AdminApi.Codes?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var local by remember { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var q by rememberSaveable { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<AdminApi.Code?>(null) }

    LaunchedEffect(reload, local) {
        error = null
        runCatching { AdminApi.codes() }.onSuccess { data = it }.onFailure { error = it.message ?: "Could not load codes" }
    }
    fun act(msg: String, block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onSuccess { note = msg; local++ }.onFailure { note = it.message ?: "Failed" } }
    }

    Loaded(data, error, { local++ }) { d ->
        val counts = d.codes.groupingBy { it.status }.eachCount()
        val shown = d.codes.filter { c ->
            (filter == "all" || c.status == filter) &&
                (q.isBlank() || c.code.contains(q, true) || c.note.contains(q, true) ||
                    c.provider.contains(q, true) || c.redeemedBy.any { it.email.contains(q, true) })
        }
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { Box(Modifier.padding(horizontal = 16.dp)) { CreateCta(onCreate) } }
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterPill("All", d.codes.size, filter == "all") { filter = "all" }
                    FilterPill("Active", counts["active"] ?: 0, filter == "active") { filter = "active" }
                    FilterPill("Used", counts["used"] ?: 0, filter == "used") { filter = "used" }
                    FilterPill("Expired", counts["expired"] ?: 0, filter == "expired") { filter = "expired" }
                    FilterPill("Revoked", counts["revoked"] ?: 0, filter == "revoked") { filter = "revoked" }
                }
            }
            if (d.codes.size > 4) item {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    if (LocalTv.current) TvSearchButton(q, { q = it }, "Search codes or emails")
                    else SearchField(q, { q = it }, "Search codes or emails")
                }
            }
            note?.let { n -> item { Text(n, color = Teal, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp)) } }
            if (shown.isEmpty()) item {
                Text(
                    if (d.codes.isEmpty()) "No codes yet." else "No codes here.",
                    color = C.Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
            items(shown, key = { it.id }) { c ->
                Box(Modifier.padding(horizontal = 16.dp)) {
                    CodeCard(
                        c,
                        onRenew = { act("${c.code} renewed for 30 days") { AdminApi.renewCode(c.id, 30, if (c.status == "used") 1 else 0) } },
                        onRevoke = { act("${c.code} revoked") { AdminApi.revokeCode(c.id) } },
                        onDelete = { confirmDelete = c },
                    )
                }
            }
        }
    }

    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = C.Surface,
            title = { Text("Delete code?", fontSize = 18.sp) },
            text = {
                Text(
                    c.code + "\n\n" + if (c.uses > 0) "Viewers who redeemed it lose the sections it unlocked." else "It has not been used yet.",
                    color = C.Muted, fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; act("${c.code} deleted") { AdminApi.deleteCode(c.id) } }) {
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
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(C.Ember, Color(0xFFB0213A))))
            .then(if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(16.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Text(
            "New activation code", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        Icon(Icons.Filled.Key, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun FilterPill(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.tvFocus(RoundedCornerShape(50)).clip(RoundedCornerShape(50))
            .background(if (selected) C.Text else C.Surface)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (selected) C.Bg else C.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(
            "$count", color = if (selected) C.Bg.copy(alpha = 0.6f) else C.Faint, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

@Composable
private fun CodeCard(c: AdminApi.Code, onRenew: () -> Unit, onRevoke: () -> Unit, onDelete: () -> Unit) {
    val clip = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val (statusText, statusColor) = when (c.status) {
        "active" -> "Active" to Teal
        "used" -> "Used up" to Sky
        "expired" -> "Expired" to Amber
        else -> "Revoked" to C.Ember
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    c.code, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    c.sections.joinToString(" · ") { SECTION_LABEL[it] ?: it } + "  ·  " + c.provider,
                    color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            SettingsPill(statusText, statusColor)
            Box {
                RoundIcon(Icons.Filled.MoreVert, "More", C.Muted, 36) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = C.Surface2) {
                    DropdownMenuItem(
                        text = { Text("Renew 30 days") }, leadingIcon = { Icon(Icons.Filled.History, null, tint = Amber) },
                        onClick = { menu = false; onRenew() },
                    )
                    if (c.status == "active") DropdownMenuItem(
                        text = { Text("Revoke") }, leadingIcon = { Icon(Icons.Filled.Block, null, tint = C.Muted) },
                        onClick = { menu = false; onRevoke() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = C.Ember) }, leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, tint = C.Ember) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Used ${c.uses}/${c.maxUses}  ·  " + (c.expiresAt?.let { "Expires ${date(it)}" } ?: "No expiry"),
                color = C.Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            SmallAction(Icons.Filled.ContentCopy, if (copied) "Copied" else "Copy", Teal) { clip.setText(AnnotatedString(c.code)); copied = true }
            Spacer(Modifier.width(6.dp))
            SmallAction(Icons.Filled.Share, "Share", Sky) { shareCode(context, c.code) }
            Spacer(Modifier.width(8.dp))
        }
        val who = c.redeemedBy.joinToString(", ") { it.email }
        if (c.note.isNotBlank() || who.isNotBlank()) {
            Text(
                listOf(c.note, if (who.isNotBlank()) "Redeemed by $who" else "").filter { it.isNotBlank() }.joinToString("\n"),
                color = C.Muted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.tvFocus(RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(tint.copy(alpha = 0.13f))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
    }
}

private fun shareCode(context: android.content.Context, code: String) {
    val text = "Your Andam activation code: $code\n\nOpen the Andam app, go to Live TV and enter this code to unlock it."
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, "Share code").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ---------------- create page

@Composable
private fun CreateCodePage(onCancel: () -> Unit, onCreated: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<List<AdminApi.ProviderRef>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { AdminApi.codes().providers }.onSuccess { providers = it } }

    var live by remember { mutableStateOf(true) }
    var movies by remember { mutableStateOf(true) }
    var series by remember { mutableStateOf(true) }
    var provider by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("30") }
    var uses by remember { mutableStateOf("1") }
    var customUses by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val sections = listOfNotNull("live".takeIf { live }, "movies".takeIf { movies }, "series".takeIf { series })
    val count = uses.toIntOrNull() ?: 0
    val ready = sections.isNotEmpty() && count > 0 && !busy

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("New activation code", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 4.dp))

            SettingsGroup("Unlocks") {
                SettingsSwitch(Icons.Filled.LiveTv, sectionColor("live"), "Live TV", null, live) { live = it }
                SettingsSwitch(Icons.Filled.Movie, sectionColor("movies"), "Movies", null, movies) { movies = it }
                SettingsSwitch(Icons.Filled.Tv, sectionColor("series"), "Series", null, series) { series = it }
            }

            SettingsGroup("Details") {
                SettingsPicker(
                    Icons.Filled.Dns, C.Ember, "Provider",
                    listOf("" to "All providers") + providers.map { it.id to it.name }, provider,
                ) { provider = it }
                SettingsPicker(
                    Icons.Filled.DateRange, Amber, "Valid for",
                    listOf("1" to "1 day", "7" to "7 days", "30" to "30 days", "90" to "3 months", "180" to "6 months", "365" to "1 year", "0" to "No expiry"),
                    days,
                ) { days = it }
                val useOptions = listOf("1", "2", "3", "5", "10", "20", "50", "100", "500", "1000")
                SettingsPicker(
                    Icons.Filled.People, Sky, "Viewers",
                    useOptions.map { it to (if (it == "1") "1 viewer" else "$it viewers") } +
                        listOf("custom" to (if (customUses || uses !in useOptions) "Custom: $uses" else "Custom…")),
                    if (customUses || uses !in useOptions) "custom" else uses,
                ) { if (it == "custom") customUses = true else { customUses = false; uses = it } }
                if (customUses) SettingsBlock {
                    FormField(uses, { v -> uses = v.filter { it.isDigit() }.take(7) }, "Number of viewers", KeyboardType.Number)
                }
            }

            SettingsGroup("Note", footer = "Only you see the note — e.g. the customer's name or phone.") {
                SettingsBlock { FormField(note, { note = it.take(200) }, "Optional") }
            }

            error?.let { Text(it, color = C.Ember, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp)) }
            Spacer(Modifier.height(8.dp))
        }

        // Fixed action bar.
        Row(
            Modifier.fillMaxWidth().background(C.Bg).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.weight(1f).height(48.dp).tvFocus(RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                    .background(C.Surface).clickable(enabled = !busy, onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) { Text("Cancel", color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
            Box(
                Modifier.weight(1.5f).height(48.dp).tvFocus(RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                    .background(if (ready) C.Ember else C.Surface3)
                    .clickable(enabled = ready) {
                        busy = true; error = null
                        scope.launch {
                            runCatching { AdminApi.createCode(provider.ifBlank { null }, sections, count, days.toIntOrNull() ?: 0, note.trim()) }
                                .onSuccess { onCreated(it) }
                                .onFailure { error = it.message ?: "Could not create the code" }
                            busy = false
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Text("Create code", color = if (ready) Color.White else C.Faint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun CreatedPage(code: String, onDone: () -> Unit) {
    val clip = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(Teal.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, tint = Teal, modifier = Modifier.size(34.dp))
            }
            Text("Code created", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Send it to the viewer. They enter it in Live TV.", color = C.Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
            Text(
                code, color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(C.Surface).padding(horizontal = 18.dp, vertical = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallAction(Icons.Filled.ContentCopy, if (copied) "Copied" else "Copy", Teal) { clip.setText(AnnotatedString(code)); copied = true }
                SmallAction(Icons.Filled.Share, "Share", Sky) { shareCode(context, code) }
            }
        }
        Box(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().height(48.dp)
                .tvFocus(RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(C.Ember).clickable(onClick = onDone),
            contentAlignment = Alignment.Center,
        ) { Text("Done", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
    }
}

@Composable
private fun FormField(value: String, onChange: (String) -> Unit, placeholder: String, type: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(placeholder, color = C.Faint, fontSize = 14.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = type),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = C.Surface2, unfocusedContainerColor = C.Surface2,
            focusedBorderColor = C.Ember, unfocusedBorderColor = Color.Transparent, cursorColor = C.Ember,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun sectionColor(s: String) = when (s) {
    "live" -> Color(0xFFFF5A6B)
    "movies" -> Amber
    else -> Sky
}

// ------------------------------------------------------------------ Users

@Composable
private fun UsersTab(reload: Int, onOpen: (String) -> Unit) {
    var users by remember { mutableStateOf<List<AdminApi.User>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var local by remember { mutableIntStateOf(0) }
    var q by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(reload, local) {
        error = null
        runCatching { AdminApi.users() }.onSuccess { users = it }.onFailure { error = it.message ?: "Could not load users" }
    }

    Loaded(users, error, { local++ }) { list ->
        val shown = list.filter { q.isBlank() || it.email.contains(q, true) || it.name.contains(q, true) }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                if (LocalTv.current) TvSearchButton(q, { q = it }, "Search users")
                else SearchField(q, { q = it }, "Search users")
            }
            item {
                Text(
                    "${shown.size} users · ${list.count { "live" in it.sections || it.admin }} with Live TV",
                    color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
            items(shown, key = { it.id }) { u -> UserRow(u) { onOpen(u.id) } }
        }
    }
}

@Composable
private fun UserRow(u: AdminApi.User, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(16.dp))
            .background(if (focused) C.Surface3 else C.Surface)
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(16.dp)) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(u.email, 40)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(u.email.ifBlank { u.name.ifBlank { "Unknown" } }, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(accessLine(u), color = if (u.suspended) C.Ember else C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(u.lastLoginAt?.let { ago(it) } ?: "", color = C.Faint, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun accessLine(u: AdminApi.User): String = when {
    u.suspended -> "Suspended"
    u.admin -> "Admin · full access"
    u.sections.isEmpty() -> "IPTV only"
    else -> u.sections.joinToString(" · ") { SECTION_LABEL[it] ?: it }
}

@Composable
private fun UserPage(id: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf<AdminApi.User?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var local by remember { mutableIntStateOf(0) }
    LaunchedEffect(local) {
        runCatching { AdminApi.users().firstOrNull { it.id == id } }
            .onSuccess { user = it; if (it == null) error = "User not found" }
            .onFailure { error = it.message ?: "Could not load" }
    }
    fun act(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onSuccess { error = null; local++ }.onFailure { error = it.message ?: "Failed" } }
    }

    Column(Modifier.fillMaxSize()) {
        val u = user
        if (u == null) {
            Box(Modifier.weight(1f)) { if (error != null) ErrorBox(error!!) { local++ } else Busy() }
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(u.email, 46)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(u.email, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Joined ${date(u.createdAt)} · seen ${u.lastLoginAt?.let { ago(it) } ?: "never"}",
                            color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                SettingsGroup("Unlocked sections") {
                    SettingsSwitch(Icons.Filled.LiveTv, sectionColor("live"), "Live TV", null, "live" in u.sections) { on -> act { AdminApi.setSection(u.id, "live", on) } }
                    SettingsSwitch(Icons.Filled.Movie, sectionColor("movies"), "Movies", null, "movies" in u.sections) { on -> act { AdminApi.setSection(u.id, "movies", on) } }
                    SettingsSwitch(Icons.Filled.Tv, sectionColor("series"), "Series", null, "series" in u.sections) { on -> act { AdminApi.setSection(u.id, "series", on) } }
                }
                SettingsGroup("Account") {
                    SettingsSwitch(Icons.Filled.AdminPanelSettings, C.Gold, "Admin", "Full access to this panel", u.admin) { on -> act { AdminApi.setAdmin(u.id, on) } }
                    SettingsSwitch(Icons.Filled.Block, C.Ember, "Suspended", "Blocks sign-in", u.suspended) { on -> act { AdminApi.setSuspended(u.id, on) } }
                }
                error?.let { Text(it, color = C.Ember, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp)) }
                Spacer(Modifier.height(8.dp))
            }
        }
        Box(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().height(48.dp)
                .tvFocus(RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(C.Surface).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Text("Done", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
    }
}

@Composable
private fun Avatar(email: String, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Brush.linearGradient(listOf(C.Ember, Color(0xFF8E1F2C)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(email.trim().firstOrNull()?.uppercase() ?: "?", color = Color.White, fontSize = (size * 0.4f).sp, fontWeight = FontWeight.Bold)
    }
}

// ------------------------------------------------------------------ helpers

private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

private fun instant(iso: String): Instant? =
    runCatching { Instant.parse(iso) }.getOrNull() ?: runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()

private fun date(iso: String): String = instant(iso)?.let { DAY.format(it.atZone(ZoneId.systemDefault())) } ?: iso.take(10)

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
