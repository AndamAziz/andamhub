package uk.andam.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * Free Kurdish (Sorani, "ckb") translation through Google Translate's public endpoints, asked
 * from this device — no key. Google answers devices that ask too fast with HTTP 429, so:
 * a browser User-Agent, one request at a time with a short gap, batches of ~4000 characters /
 * 100 cues, a rotation between the three addresses, and growing pauses after a full round.
 *
 * Finished cues stay in memory per [key], so when a run stops (Google busy, no network) the next
 * attempt continues where it stopped instead of starting over.
 */
object GoogleTranslate {
    private val ENDPOINTS = listOf(
        "https://translate.googleapis.com/translate_a/t?client=gtx&sl=auto&tl=ckb&format=text",
        "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=ckb",
        "https://translate.google.com/translate_a/t?client=gtx&sl=auto&tl=ckb",
    )
    private const val BROWSER =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36"
    private const val MAX_CHARS = 4000
    private const val MAX_CUES = 100
    private const val GAP_MS = 400L
    private val ROUND_PAUSES = longArrayOf(8_000, 16_000, 32_000, 60_000)
    private const val MAX_TRIES = 10

    /** Finished translations per job key (e.g. subtitle part), kept for resuming. */
    private val done = ConcurrentHashMap<String, Array<String?>>()
    private var endpoint = 0

    class BusyException(message: String) : Exception(message)

    /**
     * Translates [lines] (line breaks inside a line are kept) and returns them in the same order.
     * [progress] gets 0..1. Throws [BusyException] when Google keeps refusing; call again to resume.
     */
    suspend fun translate(key: String, lines: List<String>, progress: (Float) -> Unit = {}): List<String> =
        withContext(Dispatchers.IO) {
            val out = done.getOrPut(key) { arrayOfNulls(lines.size) }.let { if (it.size == lines.size) it else arrayOfNulls(lines.size) }
            done[key] = out
            var lastCall = 0L
            while (true) {
                ensureActive()
                val first = out.indexOfFirst { it == null }
                if (first < 0) break
                // Next batch: consecutive untranslated cues within the size limits.
                val batch = ArrayList<Int>()
                var chars = 0
                var i = first
                while (i < lines.size && out[i] == null && batch.size < MAX_CUES) {
                    val len = lines[i].length + 3
                    if (batch.isNotEmpty() && chars + len > MAX_CHARS) break
                    batch.add(i); chars += len; i++
                }
                // Blank cues need no request.
                val ask = batch.filter { lines[it].isNotBlank() }
                batch.filter { lines[it].isBlank() }.forEach { out[it] = lines[it] }
                if (ask.isEmpty()) continue
                var tries = 0
                var result: List<String>? = null
                while (result == null) {
                    ensureActive()
                    val wait = GAP_MS - (System.currentTimeMillis() - lastCall)
                    if (wait > 0) delay(wait)
                    lastCall = System.currentTimeMillis()
                    result = runCatching { request(ENDPOINTS[endpoint], ask.map { lines[it] }) }.getOrNull()
                    if (result == null) {
                        tries++
                        if (tries >= MAX_TRIES) throw BusyException("Google Translate is busy right now.")
                        endpoint = (endpoint + 1) % ENDPOINTS.size
                        // After trying every address once, wait longer each round.
                        if (tries % ENDPOINTS.size == 0) delay(ROUND_PAUSES[(tries / ENDPOINTS.size - 1).coerceAtMost(ROUND_PAUSES.lastIndex)])
                    }
                }
                ask.forEachIndexed { k, idx -> out[idx] = result[k].ifBlank { lines[idx] } }
                progress(out.count { it != null }.toFloat() / lines.size.coerceAtLeast(1))
            }
            progress(1f)
            val list = out.map { it.orEmpty() }
            done.remove(key)
            list
        }

    /** One POST with q=…&q=…; the answer is a list of strings or of [text, sourceLang] pairs. */
    private fun request(url: String, texts: List<String>): List<String>? {
        val body = FormBody.Builder().apply { texts.forEach { add("q", it) } }.build()
        val req = Request.Builder().url(url)
            .header("User-Agent", BROWSER)
            .header("Accept", "application/json,*/*")
            .post(body)
            .build()
        Api.http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val text = res.body?.string() ?: return null
            val items = runCatching { JSONArray(text) }.getOrNull() ?: return null
            if (items.length() != texts.size) return null
            return (0 until items.length()).map { i ->
                when (val v = items.get(i)) {
                    is String -> v
                    is JSONArray -> v.optString(0)
                    else -> ""
                }
            }
        }
    }
}
