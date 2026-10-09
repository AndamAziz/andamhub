package uk.andam.app.player

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import uk.andam.app.Config
import uk.andam.app.net.Api
import uk.andam.app.net.GoogleTranslate
import uk.andam.app.net.OnlineSubs

/**
 * Prepares an online subtitle before (or while) a film / episode plays, shared by the detail
 * pages (CC chips, "Preparing subtitles…") and the player's settings panel.
 *
 *  - Any language OpenSubtitles has: the file is fetched once through the server (which may
 *    answer with OpenSubtitles' own message, e.g. the daily download limit).
 *  - Kurdish (Sorani): a real Kurdish file when one exists; otherwise it is translated from the
 *    English (else Arabic) one. The default engine is free Google Translate run on this device,
 *    part by part; each finished part is uploaded so every later viewer gets the file at once,
 *    and a run that stops resumes from the parts (and cues) already done. Claude / Gemini run
 *    on the server when the viewer picked them and the server offers them.
 *
 * Nothing here touches playback: when anything fails the title simply plays without subtitles.
 */
object SubPrep {
    data class Key(val tmdb: Int, val type: String, val season: Int, val episode: Int, val lang: String)

    sealed class State {
        data class Working(val progress: Float) : State()
        data class Ready(val sub: Engine.ExternalSub) : State()
        data class Failed(val message: String) : State()
    }

    /** Observable by Compose. */
    val states = mutableStateMapOf<Key, State>()
    private val jobs = HashMap<Key, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun key(item: PlayItem, lang: String) = Key(
        item.tmdb, if (item.kind == Kind.EPISODE) "episode" else "movie", item.season, item.episode, lang,
    )

    /** Language names shown on the CC chips / menus. */
    fun label(lang: String) = when (lang) {
        "ku" -> "Kurdish"
        "en" -> "English"
        "ar" -> "العربية"
        "fa" -> "فارسی"
        "tr" -> "Türkçe"
        "fr" -> "Français"
        "de" -> "Deutsch"
        "es" -> "Español"
        else -> lang.uppercase()
    }

    fun ready(k: Key): Engine.ExternalSub? = (states[k] as? State.Ready)?.sub

    /** Starts (or resumes) preparing [k]; a finished one is kept. */
    fun prepare(context: Context, k: Key): Job? {
        if (k.tmdb <= 0 || states[k] is State.Ready) return null
        jobs[k]?.takeIf { it.isActive }?.let { return it }
        states[k] = State.Working(0f)
        val job = scope.launch {
            val r = runCatching { run(context.applicationContext, k) }
            states[k] = r.fold(
                onSuccess = { State.Ready(it) },
                onFailure = { e ->
                    State.Failed(
                        when {
                            e is GoogleTranslate.BusyException -> "Google is busy — tap again to continue"
                            k.lang == "ku" -> e.message?.takeIf { it.length < 120 } ?: "Kurdish subtitles stopped — tap again to continue"
                            else -> e.message?.takeIf { it.length < 120 } ?: "Subtitles are not available right now"
                        },
                    )
                },
            )
        }
        jobs[k] = job
        return job
    }

    private fun progress(k: Key, f: Float) {
        if (states[k] !is State.Ready) states[k] = State.Working(f.coerceIn(0f, 1f))
    }

    private suspend fun run(context: Context, k: Key): Engine.ExternalSub {
        val list = Api.subtitles(k.tmdb, k.type, k.season, k.episode)
        val real = list.subs.firstOrNull { it.lang == k.lang }
        if (real != null) {
            // Fetching it once makes the server download it (and tells us OpenSubtitles' answer).
            val (code, body) = Api.subtitleCall(real.url)
            if (code != 200) throw IllegalStateException(errorOf(body) ?: "Subtitle download failed ($code)")
            progress(k, 1f)
            return Engine.ExternalSub(absolute(real.url), real.lang, real.label)
        }
        if (k.lang != "ku") throw IllegalStateException("No ${label(k.lang)} subtitles for this title")
        val url = list.kurdishUrl ?: throw IllegalStateException("No subtitle to make Kurdish from")
        val wanted = PlayerPrefs.kurdishEngine(context)
        val engine = if (wanted != "g" && wanted in list.kurdishEngines && list.kurdishPartUrl != null) wanted else "g"
        val ready = if (engine == "g") viaDevice(k, list) else {
            Api.kurdishSubtitle("$url&e=$engine", "${list.kurdishPartUrl}&e=$engine") { progress(k, it) }
        }
        return Engine.ExternalSub(absolute(ready), "ku", "Kurdish (auto)")
    }

    /** Free engine: Google Translate on this device, part by part, each part uploaded. */
    private suspend fun viaDevice(k: Key, list: OnlineSubs): String {
        val done = "${list.kurdishUrl}&e=g"
        val source = list.kurdishSourceUrl ?: throw IllegalStateException("Kurdish is not available")
        val upload = list.kurdishUploadUrl ?: throw IllegalStateException("Kurdish is not available")
        repeat(2) {
            val (code, body) = Api.subtitleCall(done)
            if (code == 200) return done
            val state = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
            if (code != 202) throw IllegalStateException(state.optString("error").ifBlank { "Kurdish subtitles failed ($code)" })
            val parts = state.optInt("parts", 1).coerceAtLeast(1)
            val missing = state.optJSONArray("missing").ints()
            var finished = parts - missing.size
            progress(k, finished.toFloat() / parts)
            for (n in missing) {
                val (sc, sb) = Api.subtitleCall("$source&n=$n")
                if (sc != 200) throw IllegalStateException(errorOf(sb) ?: "Could not read the subtitle ($sc)")
                val lines = JSONObject(sb).optJSONArray("lines").strings()
                val out = GoogleTranslate.translate("$source#$n", lines) { f -> progress(k, (finished + f) / parts) }
                val (uc, ub) = Api.subtitleCall("$upload&n=$n", JSONObject().put("lines", JSONArray(out)))
                if (uc != 200) throw IllegalStateException(errorOf(ub) ?: "Could not save the Kurdish subtitle ($uc)")
                finished++
                progress(k, finished.toFloat() / parts)
            }
        }
        val (code, _) = Api.subtitleCall(done)
        if (code == 200) return done
        throw IllegalStateException("Kurdish subtitles are not ready yet — tap again to continue")
    }

    private fun errorOf(body: String): String? =
        runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun JSONArray?.ints(): List<Int> = (0 until (this?.length() ?: 0)).map { this!!.optInt(it) }
    private fun JSONArray?.strings(): List<String> = (0 until (this?.length() ?: 0)).map { this!!.optString(it) }

    private fun absolute(url: String) = if (url.startsWith("http")) url else Config.BASE_URL + url
}
