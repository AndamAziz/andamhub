package uk.andam.app.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uk.andam.app.Config
import uk.andam.app.net.Api
import uk.andam.app.net.OnlineSub
import uk.andam.app.net.OnlineSubs

/**
 * Online subtitles for the film / episode playing: the list found on OpenSubtitles, the
 * Kurdish (Sorani) auto-translation, and the viewer's preferred language picked automatically
 * (Account → Playback → Subtitles).
 */
class OnlineSubtitles(private val engine: Engine, private val scope: CoroutineScope, private val context: Context) {

    var list by mutableStateOf<OnlineSubs?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    /** 0..1 while the Kurdish translation is being prepared, otherwise null. */
    var kurdishProgress by mutableStateOf<Float?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private var key: String? = null
    private var job: Job? = null
    private var kurdishJob: Job? = null

    /** Online subtitles exist only for films and episodes the server could match on TMDB. */
    fun supported(item: PlayItem?): Boolean =
        item != null && !item.isLive && item.tmdb > 0

    fun onItem(item: PlayItem) {
        val k = if (supported(item)) "${item.kind}:${item.tmdb}:${item.season}:${item.episode}" else null
        if (k == key) return
        key = k
        job?.cancel(); kurdishJob?.cancel()
        list = null; loading = false; kurdishProgress = null; message = null
        if (k == null) return
        job = scope.launch {
            loading = true
            val subs = runCatching {
                Api.subtitles(item.tmdb, if (item.kind == Kind.EPISODE) "episode" else "movie", item.season, item.episode)
            }.getOrNull()
            loading = false
            if (key != k) return@launch
            list = subs
            // The viewer's preferred subtitle language, picked by itself.
            val pref = PlayerPrefs.subLang(context)
            if (subs == null || pref.isBlank()) return@launch
            val match = subs.subs.firstOrNull { it.lang == pref }
            when {
                match != null -> use(match)
                pref == "ku" && subs.kurdishUrl != null -> useKurdish()
            }
        }
    }

    fun use(sub: OnlineSub) {
        message = null
        engine.useSubtitle(Engine.ExternalSub(absolute(sub.url), sub.lang, sub.label))
    }

    fun off() {
        message = null
        engine.useSubtitle(null)
    }

    /** Kurdish (Sorani) translated from the English subtitle; the first time takes a moment. */
    fun useKurdish() {
        val l = list ?: return
        val url = l.kurdishUrl ?: return
        val part = l.kurdishPartUrl ?: return
        if (kurdishProgress != null) return
        val k = key
        kurdishProgress = 0f
        message = null
        kurdishJob = scope.launch {
            runCatching { Api.kurdishSubtitle(url, part) { f -> kurdishProgress = f } }
                .onSuccess { ready ->
                    if (key == k) {
                        engine.useSubtitle(Engine.ExternalSub(absolute(ready), "ku", "Kurdish (auto)"))
                        message = "Kurdish subtitles are on"
                    }
                }
                .onFailure { if (key == k) message = "Kurdish subtitles could not be prepared. Try again." }
            kurdishProgress = null
        }
    }

    private fun absolute(url: String) = if (url.startsWith("http")) url else Config.BASE_URL + url
}
