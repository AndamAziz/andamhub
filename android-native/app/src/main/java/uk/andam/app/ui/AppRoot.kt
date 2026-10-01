package uk.andam.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.andam.app.BuildConfig
import uk.andam.app.Config
import uk.andam.app.auth.Session
import uk.andam.app.net.Api

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Home", Icons.Filled.Home),
    Tab("Live TV", Icons.Filled.LiveTv),
    Tab("Movies", Icons.Filled.Movie),
    Tab("Series", Icons.Filled.Tv),
    Tab("IPTV", Icons.Filled.Wifi),
)

@Composable
fun AppRoot(authMessage: String?) {
    val user by Session.user.collectAsState()
    val guest by Session.guest.collectAsState()
    var forceLogin by remember { mutableStateOf(false) }
    // Returning from Google sign-in in the browser lands here with a fresh user.
    LaunchedEffect(user) { if (user != null) forceLogin = false }

    if ((user == null && !guest) || forceLogin) {
        LoginScreen(authMessage) { forceLogin = false }
        return
    }
    Main(onSignIn = { forceLogin = true })
}

@Composable
private fun Main(onSignIn: () -> Unit) {
    val context = LocalContext.current
    val user by Session.user.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var account by rememberSaveable { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(user, refreshKey) {
        Store.refresh(context)
        ready = true
    }
    // Quiet update check once per launch; a banner appears when a newer build exists.
    LaunchedEffect(Unit) {
        runCatching { uk.andam.app.Updater.check() }.getOrNull()?.let { UpdateState.available = it }
    }
    BackHandler(enabled = account || tab != 0) { if (account) account = false else tab = 0 }

    val body: @Composable () -> Unit = {
        if (!ready) {
            Busy()
        } else if (account) {
            AccountScreen(onSignIn = onSignIn, onChanged = { refreshKey++ })
        } else {
            val live = Store.access?.live == true
            when (tab) {
                0 -> HomeScreen(onTab = { tab = it })
                1 -> if (live) LiveScreen() else LockCard(Session.signedIn, { refreshKey++ }, onSignIn)
                2 -> if (live) MoviesScreen() else LockCard(Session.signedIn, { refreshKey++ }, onSignIn)
                3 -> if (live) SeriesScreen() else LockCard(Session.signedIn, { refreshKey++ }, onSignIn)
                else -> IptvScreen()
            }
        }
    }

    if (LocalTv.current) {
        TvShell(tab, account, onTab = { tab = it; account = false }, onAccount = { account = true }, body = body)
        return
    }

    Scaffold(
        containerColor = C.Bg,
        topBar = {
            Row(
                Modifier.fillMaxWidth().background(C.Bg).statusBarsPadding().padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (account) {
                    IconButton(onClick = { account = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text("Account", style = MaterialTheme.typography.titleLarge)
                } else {
                    BrandMark(30)
                    Text("Andam", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 10.dp))
                }
                Spacer(Modifier.weight(1f))
                if (!account) {
                    when (tab) {
                        1, 2, 3 -> SourcePicker(Store.providers.map { it.id to it.name }, Store.provider) { Store.pickProvider(context, it) }
                        4 -> SourcePicker(Store.iptvSources.map { it.id to it.name }, Store.iptvSource) { Store.pickIptv(context, it) }
                    }
                    IconButton(onClick = { account = true }) { Icon(Icons.Filled.AccountCircle, "Account", tint = C.Muted) }
                }
            }
        },
        bottomBar = {
            if (!account) NavigationBar(containerColor = C.Surface, tonalElevation = 0.dp) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = C.Ember, selectedTextColor = C.Text,
                            unselectedIconColor = C.Faint, unselectedTextColor = C.Faint,
                            indicatorColor = C.EmberDim,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!account) UpdateBanner(onOpen = { account = true })
            Box(Modifier.weight(1f).fillMaxWidth()) { body() }
        }
    }
}

/**
 * TV layout: a menu down the left side (driven by the remote's arrows), the page on the right,
 * with an overscan-safe margin. Back from a page returns to the menu first.
 */
@Composable
private fun TvShell(tab: Int, account: Boolean, onTab: (Int) -> Unit, onAccount: () -> Unit, body: @Composable () -> Unit) {
    val context = LocalContext.current
    val menuFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var menuHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { menuFocus.requestFocus() } }
    BackHandler(enabled = !menuHasFocus) { runCatching { menuFocus.requestFocus() } }

    Row(Modifier.fillMaxSize().background(C.Bg)) {
        Column(
            Modifier
                .width(220.dp)
                .fillMaxHeight()
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(C.Surface, C.Bg)))
                .padding(start = 28.dp, end = 12.dp, top = 28.dp, bottom = 24.dp)
                .onFocusChanged { menuHasFocus = it.hasFocus },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 22.dp)) {
                BrandMark(40)
                Text("Andam", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
            }
            tabs.forEachIndexed { i, t ->
                TvMenuItem(
                    t.icon, t.label, selected = !account && tab == i,
                    modifier = if (!account && tab == i) Modifier.focusRequester(menuFocus) else Modifier,
                ) { onTab(i) }
            }
            Spacer(Modifier.weight(1f))
            TvMenuItem(
                Icons.Filled.AccountCircle, "Account", selected = account,
                modifier = if (account) Modifier.focusRequester(menuFocus) else Modifier,
                onClick = onAccount,
            )
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp, end = 32.dp, top = 24.dp, bottom = 16.dp)) {
            if (!account) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tabs[tab].label, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    when (tab) {
                        1, 2, 3 -> SourcePicker(Store.providers.map { it.id to it.name }, Store.provider) { Store.pickProvider(context, it) }
                        4 -> SourcePicker(Store.iptvSources.map { it.id to it.name }, Store.iptvSource) { Store.pickIptv(context, it) }
                    }
                }
                UpdateBanner(onOpen = onAccount)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { body() }
        }
    }
}

@Composable
private fun TvMenuItem(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    focused -> C.Text
                    selected -> C.EmberDim
                    else -> androidx.compose.ui.graphics.Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (focused) C.Bg else if (selected) C.Ember else C.Muted)
        Text(
            label,
            color = if (focused) C.Bg else if (selected) C.Text else C.Muted,
            fontWeight = if (selected || focused) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

@Composable
private fun AccountScreen(onSignIn: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val user by Session.user.collectAsState()
    val scope = rememberCoroutineScope()
    val access = Store.access
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().background(C.Surface, RoundedCornerShape(18.dp)).padding(18.dp),
        ) {
            Text(if (user != null) "Signed in" else "Guest", color = C.Faint, style = MaterialTheme.typography.labelMedium)
            Text(user?.email?.ifBlank { "Andam account" } ?: "Not signed in", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
            val sections = when {
                access == null -> "—"
                access.admin -> "Everything (admin)"
                access.live -> "Live TV, Movies, Series, IPTV"
                else -> "IPTV"
            }
            Text("Access: $sections", color = C.Muted, modifier = Modifier.padding(top = 8.dp))
        }
        if (user != null && access?.live != true) {
            Box(Modifier.fillMaxWidth().height(420.dp)) { LockCard(true, onChanged, onSignIn) }
        }
        if (user == null) {
            Button(onClick = onSignIn, colors = ButtonDefaults.buttonColors(containerColor = C.Ember), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(50.dp).tvFocus(RoundedCornerShape(14.dp), 1.02f)) { Text("Sign in") }
        } else {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        Session.signOut()
                        Api.clearCache()
                        onChanged()
                    }
                },
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(50.dp).tvFocus(RoundedCornerShape(14.dp), 1.02f),
            ) { Text("Sign out", color = C.Text) }
        }
        PlayerSettingsCard()
        UpdateCard()
        DiagnosticsCard()
        ContactCard()
        Text("Andam ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = C.Faint, modifier = Modifier.padding(top = 8.dp).width(300.dp))
    }
}

/** Contact details (replaces the old "Open andam.uk" button). */
@Composable
private fun ContactCard() {
    val context = LocalContext.current
    fun open(intent: android.content.Intent) {
        runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    Column(
        Modifier.fillMaxWidth().background(C.Surface, RoundedCornerShape(18.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Contact", color = C.Faint, style = MaterialTheme.typography.labelMedium)
        ContactRow(
            icon = Icons.AutoMirrored.Filled.Send, tint = androidx.compose.ui.graphics.Color(0xFF2AABEE),
            label = "Telegram", value = "@AndamAziz",
        ) { open(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://t.me/AndamAziz"))) }
        ContactRow(
            icon = Icons.Filled.Email, tint = C.Ember,
            label = "Email", value = "info@andam.uk",
        ) { open(android.content.Intent(android.content.Intent.ACTION_SENDTO, Uri.parse("mailto:info@andam.uk"))) }
    }
}

@Composable
private fun ContactRow(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocus(RoundedCornerShape(14.dp), 1.02f)
            .clip(RoundedCornerShape(14.dp))
            .background(C.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(label, color = C.Muted, fontSize = 12.sp)
            Text(value, color = C.Text, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
    }
}
