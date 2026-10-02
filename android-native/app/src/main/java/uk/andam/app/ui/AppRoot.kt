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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
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
    // CEO admin panel (admins only): "" = closed, "overview" or "create" (opens a new code).
    var admin by rememberSaveable { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(user, refreshKey) {
        Store.refresh(context)
        ready = true
    }
    // Welcome screen on launch: stays until the app has loaded and its short intro has played,
    // then fades into the app. Not shown again on rotation or when returning to the app.
    var introDone by rememberSaveable { mutableStateOf(false) }
    val launchedAt = remember { android.os.SystemClock.elapsedRealtime() }
    LaunchedEffect(ready) {
        if (ready && !introDone) {
            kotlinx.coroutines.delay((1300L - (android.os.SystemClock.elapsedRealtime() - launchedAt)).coerceAtLeast(0L))
            introDone = true
        }
    }
    // Quiet update check once per launch; a banner appears when a newer build exists.
    LaunchedEffect(Unit) {
        runCatching { uk.andam.app.Updater.check() }.getOrNull()?.let { UpdateState.available = it }
    }
    BackHandler(enabled = account || tab != 0) { if (account) account = false else tab = 0 }

    val body: @Composable () -> Unit = {
        if (!ready) {
            Busy()
        } else if (account && admin.isNotEmpty() && Store.access?.admin == true) {
            AdminScreen(startOnCodes = admin == "create", openCreate = admin == "create", onBack = { admin = "" })
        } else if (account) {
            AccountScreen(onSignIn = onSignIn, onChanged = { refreshKey++ }, onAdmin = { admin = it })
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

    val shell: @Composable () -> Unit = {
        if (LocalTv.current) {
            TvShell(tab, account, onTab = { tab = it; account = false; admin = "" }, onAccount = { account = true; admin = "" }, body = body)
        } else {
            Scaffold(
                containerColor = C.Bg,
                topBar = {
                    Row(
                        Modifier.fillMaxWidth().background(C.Bg).statusBarsPadding().padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (account) {
                            IconButton(onClick = { if (admin.isNotEmpty()) admin = "" else account = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                            Text(if (admin.isNotEmpty()) "Admin" else "Account", style = MaterialTheme.typography.titleLarge)
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
    }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        // After a rotation the app shows straight away (with its loading spinner) — no intro again.
        if (ready || introDone) shell()
        androidx.compose.animation.AnimatedVisibility(
            visible = !introDone,
            enter = androidx.compose.animation.EnterTransition.None,
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(600)) +
                androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(600), targetScale = 1.06f),
        ) { WelcomeScreen(user?.email) }
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
private fun AccountScreen(onSignIn: () -> Unit, onChanged: () -> Unit, onAdmin: (String) -> Unit) {
    val context = LocalContext.current
    val user by Session.user.collectAsState()
    val scope = rememberCoroutineScope()
    val access = Store.access
    var confirmOut by remember { mutableStateOf(false) }
    fun open(intent: android.content.Intent) {
        runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Profile
            SettingsGroup(null) {
                ProfileHeader(user?.email, access)
                if (user == null) {
                    SettingsRow(
                        Icons.AutoMirrored.Filled.Login, C.Ember, "Sign in",
                        subtitle = "Unlock Live TV, Movies and Series",
                        trailing = { SettingsPill("Sign in", C.Ember, filled = true) },
                        onClick = onSignIn,
                    )
                } else {
                    SettingsRow(
                        Icons.AutoMirrored.Filled.Logout, C.Ember, "Sign out",
                        titleColor = C.Ember, chevron = false,
                        onClick = { confirmOut = true },
                    )
                }
            }
            if (user != null && access?.live != true) {
                Box(Modifier.fillMaxWidth().height(420.dp)) { LockCard(true, onChanged, onSignIn) }
            }

            if (access?.admin == true) {
                SettingsGroup("Admin") {
                    SettingsRow(
                        Icons.Filled.Key, C.Gold, "Create activation code", "Unlock Live TV, Movies or Series for a viewer",
                        trailing = { SettingsPill("New", C.Ember, filled = true) },
                    ) { onAdmin("create") }
                    SettingsRow(
                        Icons.Filled.AdminPanelSettings, C.Ember, "CEO admin panel", "Codes, users, providers and errors",
                    ) { onAdmin("overview") }
                }
            }

            PlaybackSettings()
            UpdateSettings()
            DiagnosticsSettings()

            SettingsGroup("Help & contact") {
                SettingsRow(
                    Icons.AutoMirrored.Filled.Send, androidx.compose.ui.graphics.Color(0xFF2AABEE), "Telegram", "@AndamAziz",
                ) { open(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://t.me/AndamAziz"))) }
                SettingsRow(Icons.Filled.Email, C.Ember, "Email", "info@andam.uk") {
                    open(android.content.Intent(android.content.Intent.ACTION_SENDTO, Uri.parse("mailto:info@andam.uk")))
                }
            }

            Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BrandMark(34)
                Text("Andam", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                Text("Version ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}", color = C.Faint, fontSize = 12.sp)
            }
        }
    }

    if (confirmOut) {
        AlertDialog(
            onDismissRequest = { confirmOut = false },
            containerColor = C.Surface,
            title = { Text("Sign out?") },
            text = { Text("You can sign in again any time.", color = C.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmOut = false
                    scope.launch {
                        Session.signOut()
                        Api.clearCache()
                        onChanged()
                    }
                }) { Text("Sign out", color = C.Ember, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmOut = false }) { Text("Cancel", color = C.Muted) } },
        )
    }
}

/** Avatar with the account's initial, the e-mail and what this account can watch. */
@Composable
private fun ProfileHeader(email: String?, access: uk.andam.app.net.Access?) {
    val name = email?.ifBlank { null }
    val (badge, color) = when {
        name == null -> "Guest" to C.Muted
        access == null -> "Signed in" to C.Muted
        access.admin -> "Admin · full access" to C.Gold
        access.live -> "Full access" to Teal
        else -> "IPTV only" to Amber
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(listOf(C.EmberDim, C.Surface, C.Surface)),
            )
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(C.Ember, androidx.compose.ui.graphics.Color(0xFF8E1F2C)))),
            contentAlignment = Alignment.Center,
        ) {
            if (name != null) Text(name.first().uppercase(), color = androidx.compose.ui.graphics.Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            else Icon(Icons.Filled.Person, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(30.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                name ?: "Guest", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 6.dp)) { SettingsPill(badge, color) }
        }
    }
}
