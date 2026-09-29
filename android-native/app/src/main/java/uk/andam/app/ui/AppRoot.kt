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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
          Box(Modifier.weight(1f).fillMaxWidth()) {
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
        }
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
            Button(onClick = onSignIn, colors = ButtonDefaults.buttonColors(containerColor = C.Ember), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Sign in") }
        } else {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        Session.signOut()
                        Api.clearCache()
                        onChanged()
                    }
                },
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("Sign out", color = C.Text) }
        }
        UpdateCard()
        DiagnosticsCard()
        OutlinedButton(
            onClick = { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(Config.BASE_URL)) },
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text("Open andam.uk", color = C.Muted) }
        Text("Andam ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = C.Faint, modifier = Modifier.padding(top = 8.dp).width(300.dp))
    }
}
