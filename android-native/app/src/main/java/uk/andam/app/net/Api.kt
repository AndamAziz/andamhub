package uk.andam.app.net

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import uk.andam.app.Config
import uk.andam.app.auth.Session
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object Api {
    lateinit var http: OkHttpClient
        private set

    /** Separate client for media: long reads, no response cache. */
    lateinit var media: OkHttpClient
        private set

    private val memo = ConcurrentHashMap<String, Pair<Long, Any>>()
    private const val TTL = 5 * 60_000L

    fun init(context: Context) {
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .cache(Cache(File(context.cacheDir, "http"), 20L * 1024 * 1024))
            .retryOnConnectionFailure(true)
            .build()
        media = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun clearCache() = memo.clear()

    private suspend fun get(path: String, params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val b = Uri.parse(Config.BASE_URL + path).buildUpon()
        params.forEach { (k, v) -> if (v.isNotEmpty()) b.appendQueryParameter(k, v) }
        val req = Request.Builder().url(b.build().toString())
            .header("Accept", "application/json")
            .header("User-Agent", Config.USER_AGENT)
        Session.accessToken()?.let { req.header("Authorization", "Bearer $it") }
        http.newCall(req.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!res.isSuccessful) {
                val msg = json.optString("error").ifBlank { json.optString("message") }
                    .ifBlank { if (res.code == 403) "This section is locked" else "Request failed (${res.code})" }
                throw ApiException(res.code, msg)
            }
            if (json.has("error") && json.optString("error").isNotBlank()) throw ApiException(502, json.optString("error"))
            json
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, load: suspend () -> T): T {
        val hit = memo[key]
        if (hit != null && System.currentTimeMillis() - hit.first < TTL) return hit.second as T
        val v = load()
        memo[key] = System.currentTimeMillis() to v
        return v
    }

    private inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) optJSONObject(i)?.let { out.add(f(it)) }
        return out
    }

    private fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k, "")

    // ---------------- access ----------------

    suspend fun access(): Access {
        val j = get("/api/public/access", emptyMap())
        val sections = j.optJSONArray("sections")
        val list = (0 until (sections?.length() ?: 0)).map { sections!!.optString(it) }
        return Access(j.optBoolean("signedIn"), j.optBoolean("admin"), list)
    }

    /** Redeems an activation code. Returns the server message; throws on failure. */
    suspend fun redeem(code: String): String = withContext(Dispatchers.IO) {
        val token = Session.accessToken() ?: throw ApiException(401, "Sign in first, then redeem your code.")
        val req = Request.Builder().url("${Config.BASE_URL}/api/public/access")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(JSONObject().put("code", code.trim()).toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val j = runCatching { JSONObject(res.body?.string().orEmpty()) }.getOrElse { JSONObject() }
            if (!j.optBoolean("ok")) throw ApiException(res.code, j.optString("message").ifBlank { "That code is not valid." })
            clearCache()
            j.optString("message").ifBlank { "Activated" }
        }
    }

    // ---------------- provider (Xtream) ----------------

    private const val X = "/api/public/xtream"

    suspend fun providers(): List<Provider> = cached("providers") {
        get(X, mapOf("action" to "providers")).optJSONArray("providers")
            .mapObjects { Provider(it.str("id"), it.str("name")) }
    }

    /** type = live | vod | series */
    suspend fun categories(source: String, type: String): List<Category> = cached("cats:$source:$type") {
        get(X, mapOf("action" to "categories", "type" to type, "source" to source)).optJSONArray("categories")
            .mapObjects { Category(it.str("id"), it.str("name")) }
    }

    suspend fun live(source: String): List<LiveChannel> = cached("live:$source") {
        get(X, mapOf("action" to "live", "source" to source)).optJSONArray("items").mapObjects {
            LiveChannel(it.str("id"), it.optInt("num"), it.str("name"), it.str("logo"), it.str("categoryId"))
        }
    }

    suspend fun vod(source: String, category: String): List<VodItem> = cached("vod:$source:$category") {
        get(X, mapOf("action" to "vod", "source" to source, "category_id" to category)).optJSONArray("items").mapObjects {
            VodItem(
                it.str("id"), it.str("name"), it.str("poster"), it.str("rating"), it.str("year"),
                it.str("genre"), it.str("categoryId"), it.str("ext").ifBlank { "mp4" },
            )
        }
    }

    suspend fun series(source: String, category: String): List<SeriesItem> = cached("series:$source:$category") {
        get(X, mapOf("action" to "series", "source" to source, "category_id" to category)).optJSONArray("items").mapObjects {
            SeriesItem(it.str("id"), it.str("name"), it.str("poster"), it.str("rating"), it.str("year"), it.str("genre"), it.str("categoryId"))
        }
    }

    suspend fun seriesInfo(source: String, id: String): SeriesInfo {
        // Episode play tokens are minted per request, so this one is not memoised for long.
        val j = get(X, mapOf("action" to "series_info", "source" to source, "series_id" to id))
        val seasons = j.optJSONArray("seasons").mapObjects { s ->
            Season(s.optInt("season"), s.optJSONArray("episodes").mapObjects { e ->
                Episode(e.str("id"), e.optInt("episode"), e.str("title"), e.str("image"), e.str("plot"), e.str("duration"), e.str("play"))
            })
        }
        return SeriesInfo(j.str("title"), j.str("cover"), j.str("plot"), j.str("genre"), j.str("rating"), seasons)
    }

    /** type = live | vod | series */
    suspend fun play(source: String, type: String, id: String, ext: String = ""): PlayTokens {
        val j = get(X, mapOf("action" to "play", "source" to source, "type" to type, "id" to id, "ext" to ext))
        return PlayTokens(j.str("play"), j.str("fallback").ifBlank { null })
    }

    // ---------------- IPTV (M3U playlists) ----------------

    private const val I = "/api/public/iptv"

    suspend fun iptvSources(): List<IptvSource> = cached("iptv:sources") {
        get(I, mapOf("action" to "sources")).optJSONArray("sources").mapObjects { IptvSource(it.str("id"), it.str("name")) }
    }

    suspend fun iptvChannels(source: String): IptvList = cached("iptv:ch:$source") {
        val j = get(I, mapOf("action" to "channels", "source" to source))
        IptvList(
            j.optJSONArray("groups").mapObjects { Category(it.str("name"), it.str("name"), it.optInt("count")) },
            j.optJSONArray("channels").mapObjects {
                IptvChannel(it.str("id"), it.optInt("num"), it.str("name"), it.str("logo"), it.str("group"))
            },
        )
    }

    suspend fun iptvPlay(source: String, id: String): String =
        get(I, mapOf("action" to "play", "source" to source, "id" to id)).str("token")

    // ---------------- stream URLs ----------------

    /** Playback URL for an opaque token. `fix = true` asks the server to re-encode the sound to AAC. */
    fun streamUrl(token: String, fix: Boolean = false): String =
        Uri.parse("${Config.BASE_URL}/api/public/xtream-play").buildUpon()
            .appendQueryParameter("t", token)
            .apply { if (fix) appendQueryParameter("tc", "1") }
            .build().toString()
}
