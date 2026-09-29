package uk.andam.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import uk.andam.app.auth.Session
import uk.andam.app.net.Api
import uk.andam.app.ui.AndamTheme
import uk.andam.app.ui.AppRoot

class MainActivity : ComponentActivity() {
    private val authMessage = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        handle(intent)
        setContent {
            AndamTheme { AppRoot(authMessage.value) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Google / Apple sign-in comes back as lovable://oauth-callback?... */
    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "lovable" || uri.host != "oauth-callback") return
        lifecycleScope.launch {
            authMessage.value = Session.finishOAuth(uri)
            Api.clearCache()
        }
    }
}
