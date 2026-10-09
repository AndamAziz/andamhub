package uk.andam.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uk.andam.app.net.Access
import uk.andam.app.net.Api
import uk.andam.app.net.IptvSource
import uk.andam.app.net.Provider

/** App-wide choices shared by the tabs: entitlements, active provider, active playlist. */
object Store {
    var access by mutableStateOf<Access?>(null)
    var providers by mutableStateOf<List<Provider>>(emptyList())
    /** A series picked on Home (hero) that the Series tab should open straight away. */
    var pendingSeries by mutableStateOf<uk.andam.app.net.SeriesItem?>(null)
    /** Film to open on the Movies tab (e.g. from the home hero). */
    var pendingMovie by mutableStateOf<uk.andam.app.net.VodItem?>(null)
    var provider by mutableStateOf("")
    var iptvSources by mutableStateOf<List<IptvSource>>(emptyList())
    var iptvSource by mutableStateOf("")

    private fun prefs(c: Context) = c.getSharedPreferences("andam_ui", Context.MODE_PRIVATE)

    suspend fun refresh(c: Context) {
        access = runCatching { Api.access() }.getOrNull() ?: access
        if (access?.live == true) {
            providers = runCatching { Api.providers() }.getOrDefault(emptyList())
            val saved = prefs(c).getString("provider", null)
            provider = providers.firstOrNull { it.id == saved }?.id ?: providers.firstOrNull()?.id.orEmpty()
        } else {
            providers = emptyList()
            provider = ""
        }
        iptvSources = runCatching { Api.iptvSources() }.getOrDefault(emptyList())
        val savedIptv = prefs(c).getString("iptv", null)
        iptvSource = iptvSources.firstOrNull { it.id == savedIptv }?.id ?: iptvSources.firstOrNull()?.id.orEmpty()
    }

    fun pickProvider(c: Context, id: String) {
        provider = id
        prefs(c).edit().putString("provider", id).apply()
    }

    fun pickIptv(c: Context, id: String) {
        iptvSource = id
        prefs(c).edit().putString("iptv", id).apply()
    }
}

/** Minimal loading state for screens. */
sealed interface Load<out T> {
    data object Busy : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}
