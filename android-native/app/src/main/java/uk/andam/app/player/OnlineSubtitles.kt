package uk.andam.app.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uk.andam.app.net.Api
import uk.andam.app.net.OnlineSub
import uk.andam.app.net.OnlineSubs

/**
 * Online subtitles for the film / episode playing: the list found on OpenSubtitles, the
 * Kurdish (Sorani) auto-translation, and the language chosen on the detail page (or the viewer's
 * default from Account → Playback) switched on by itself. Preparation goes through [SubPrep], so
 * a subtitle prepared on the detail page is used at once.
 */
class OnlineSubtitles(private val engine: Engine, private val scope: CoroutineScope, private val context: Context) {

    var list by mutableStateOf<OnlineSubs?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private var item: PlayItem? = null
    private var key: String? = null
    private var job: Job? = null
    private var kurdishJob: Job? = null

    /** 0..1 while the Kurdish translation for this item is being prepared, otherwise null. */
    val kurdishProgress: Float?
        get() = item?.let { (SubPrep.states[SubPrep.key(it, "ku")] as? SubPrep.State.Working)?.progress }

    /** Online subtitles exist only for films and episodes the server could match on TMDB. */
    fun supported(item: PlayItem?): Boolean =
        item != null && !item.isLive && item.catchupOf == null && item.tmdb > 0

    fun onItem(item: PlayItem) {
        val k = if (supported(item)) "${item.kind}:${item.tmdb}:${item.season}:${item.episode}" else null
        if (k == key) return
        key = k
        this.item = if (k != null) item else null
        job?.cancel(); kurdishJob?.cancel()
        list = null; loading = false; message = null
        if (k == null) return
        // The detail page's choice, else the viewer's default language.
        val pref = item.subLang.ifBlank { PlayerPrefs.subLang(context) }
        if (pref.isNotBlank() && pref != "off") SubPrep.ready(SubPrep.key(item, pref))?.let { engine.useSubtitle(it) }
        job = scope.launch {
            loading = true
            val subs = runCatching {
                Api.subtitles(item.tmdb, if (item.kind == Kind.EPISODE) "episode" else "movie", item.season, item.episode)
            }.getOrNull()
            loading = false
            if (key != k) return@launch
            list = subs
            if (subs == null || pref.isBlank() || pref == "off" || engine.externalSub != null) return@launch
            val match = subs.subs.firstOrNull { it.lang == pref }
            when {
                match != null -> use(match)
                pref == "ku" && subs.kurdishUrl != null -> useKurdish()
            }
        }
    }

    fun use(sub: OnlineSub) {
        message = null
        val it = item ?: return
        engine.useSubtitle(Engine.ExternalSub(absolute(sub.url), sub.lang, sub.label))
        SubPrep.prepare(context, SubPrep.key(it, sub.lang))
    }

    fun off() {
        message = null
        engine.useSubtitle(null)
    }

    /** Kurdish (Sorani) made from the English subtitle; the first time takes a moment. */
    fun useKurdish() {
        val it = item ?: return
        val k = SubPrep.key(it, "ku")
        val mine = key
        message = null
        SubPrep.ready(k)?.let { sub -> engine.useSubtitle(sub); return }
        val job = SubPrep.prepare(context, k) ?: return
        kurdishJob?.cancel()
        kurdishJob = scope.launch {
            job.join()
            if (key != mine) return@launch
            when (val st = SubPrep.states[k]) {
                is SubPrep.State.Ready -> { engine.useSubtitle(st.sub); message = "Kurdish subtitles are on" }
                is SubPrep.State.Failed -> message = st.message
                else -> {}
            }
        }
    }

    private fun absolute(url: String) = if (url.startsWith("http")) url else uk.andam.app.Config.BASE_URL + url
}
