package uk.andam.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.launch
import uk.andam.app.auth.Session
import uk.andam.app.net.Api
import uk.andam.app.ui.AndamTheme
import uk.andam.app.ui.AppRoot

class MainActivity : ComponentActivity() {
    private val authMessage = mutableStateOf<String?>(null)
    private var playUpdateFlowStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        handle(intent)
        setContent {
            val tv = remember { uk.andam.app.ui.isTvDevice(this) }
            AndamTheme {
                CompositionLocalProvider(uk.andam.app.ui.LocalTv provides tv) { AppRoot(authMessage.value) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (BuildConfig.FLAVOR == "play") checkForPlayUpdate()
    }

    /** Google Play owns installation for the Play Store variant; no sideloading permission is used. */
    private fun checkForPlayUpdate() {
        if (playUpdateFlowStarted || isFinishing || isDestroyed) return
        val manager = AppUpdateManagerFactory.create(this)
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                val resuming = info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                if ((available && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) || resuming) {
                    playUpdateFlowStarted = true
                    runCatching {
                        manager.startUpdateFlowForResult(info, AppUpdateType.IMMEDIATE, this, 9001)
                    }.onFailure { playUpdateFlowStarted = false }
                }
            }
            .addOnFailureListener { playUpdateFlowStarted = false }
    }

    @Deprecated("Play Core immediate update result is delivered through the activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9001) {
            playUpdateFlowStarted = false
            if (resultCode == RESULT_OK) recreate()
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
