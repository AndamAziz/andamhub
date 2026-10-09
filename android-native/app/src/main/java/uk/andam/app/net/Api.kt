package uk.andam.app.net

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

    /** Film / series facts (TMDB) kept on disk for 7 days, newest ~120 titles. */
    private lateinit var facts: File
    private const val FACTS_TTL = 7L * 24 * 60 * 60_000
    private const val FACTS_MAX = 120

    fun init(context: Context) {
        facts = File(context.cacheDir, "facts").apply { mkdirs() }
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

    /** Drop cached answers whose key starts with [prefix] so the next call fetches fresh data. */
    fun forget(prefix: String) {
        memo.keys.filter { it.startsWith(prefix) }.forEach { memo.remove(it) }
    }

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

    /** A JSON answer from the 7-day disk cache, or fetched and stored there. */
    private suspend fun factsCached(key: String, load: suspend () -> JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val file = File(facts, key.hashCode().toUInt().toString(16) + ".json")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < FACTS_TTL) {
            runCatching { JSONObject(file.readText()) }.getOrNull()?.let { return@withContext it }
        }
        val j = load()
        runCatching {
            file.writeText(j.toString())
            facts.listFiles()?.sortedByDescending { it.lastModified() }?.drop(FACTS_MAX)?.forEach { it.delete() }
        }
        j
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
            LiveChannel(it.str("id"), it.optInt("num"), it.str("name"), it.str("logo"), it.str("categoryId"), it.optBoolean("archive"))
        }
    }

    suspend fun vod(source: String, category: String): List<VodItem> = cached("vod:$source:$category") {
        get(X, mapOf("action" to "vod", "source" to source, "category_id" to category)).optJSONArray("items").mapObjects {
            VodItem(
                it.str("id"), it.str("name"), it.str("poster"), it.str("rating"), it.str("year"),
                it.str("genre"), it.str("categoryId"), it.str("ext").ifBlank { "mp4" },
                it.str("added").toLongOrNull() ?: 0L,
            )
        }
    }

    suspend fun series(source: String, category: String): List<SeriesItem> = cached("series:$source:$category") {
        get(X, mapOf("action" to "series", "source" to source, "category_id" to category)).optJSONArray("items").mapObjects {
            SeriesItem(
                it.str("id"), it.str("name"), it.str("poster"), it.str("rating"), it.str("year"), it.str("genre"), it.str("categoryId"),
                it.str("lastModified").toLongOrNull() ?: 0L,
            )
        }
    }

    suspend fun seriesInfo(source: String, id: String): SeriesInfo {
        // Episode play tokens are minted per request, so this one is not memoised for long.
        val j = get(X, mapOf("action" to "series_info", "source" to source, "series_id" to id, "device" to "1"))
        val seasons = j.optJSONArray("seasons").mapObjects { s ->
            Season(s.optInt("season"), s.optJSONArray("episodes").mapObjects { e ->
                Episode(e.str("id"), e.optInt("episode"), e.str("title"), e.str("image"), e.str("plot"), e.str("duration"), e.str("play"), e.str("direct").ifBlank { null })
            })
        }
        return SeriesInfo(j.str("title"), j.str("cover"), j.str("plot"), j.str("genre"), j.str("rating"), seasons)
    }

    /** Film or series details for the detail page. type = movie | series */
    suspend fun details(source: String, type: String, id: String, lang: String = "en"): MediaDetails = cached("details:$source:$type:$id:$lang") {
        val j = factsCached("details:$source:$type:$id:$lang") {
            get(X, mapOf("action" to "details", "source" to source, "type" to type, "id" to id, "lang" to lang))
        }
        MediaDetails(
            title = j.str("title"),
            originalTitle = j.str("originalTitle"),
            tagline = j.str("tagline"),
            overview = j.str("overview"),
            year = j.str("year"),
            runtime = j.optInt("runtime"),
            rating = j.optDouble("rating", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            votes = j.optInt("votes"),
            genres = j.optJSONArray("genres").let { a -> (0 until (a?.length() ?: 0)).map { a!!.optString(it) }.filter { it.isNotBlank() } },
            certification = j.str("certification"),
            director = j.str("director"),
            creator = j.str("creator"),
            country = j.str("country"),
            cast = j.optJSONArray("cast").mapObjects { CastMember(it.str("name"), it.str("role"), it.str("photo")) },
            poster = j.str("poster"),
            backdrop = j.str("backdrop"),
            trailer = j.str("trailer"),
            tmdbId = j.optInt("tmdbId"),
            seasons = j.optInt("seasons"),
            episodes = j.optInt("episodes"),
        )
    }

    /** TMDB facts for one season of a series (empty when unknown or TMDB is unreachable). */
    suspend fun season(source: String, tmdb: Int, season: Int, lang: String = "en"): List<EpisodeFacts> = cached("season:$tmdb:$season:$lang") {
        val j = factsCached("season:$tmdb:$season:$lang") {
            get(X, mapOf("action" to "season", "source" to source, "tmdb" to tmdb.toString(), "season" to season.toString(), "lang" to lang))
        }
        j.optJSONArray("episodes").mapObjects {
            EpisodeFacts(it.optInt("episode"), it.str("name"), it.str("overview"), it.str("still"), it.optInt("runtime"))
        }
    }

    /** Online subtitles for a film (type = movie) or an episode (series tmdb id + season/episode). */
    suspend fun subtitles(tmdb: Int, type: String, season: Int = 0, episode: Int = 0): OnlineSubs = cached("subs:$tmdb:$type:$season:$episode") {
        val j = get(
            "/api/public/subtitles",
            mapOf("action" to "list", "tmdb" to tmdb.toString(), "type" to type, "season" to season.toString(), "episode" to episode.toString()),
        )
        val k = j.optJSONObject("kurdishAuto")
        val engines = k?.optJSONArray("engines")
        OnlineSubs(
            j.optJSONArray("subtitles").mapObjects { OnlineSub(it.str("lang"), it.str("label"), it.str("url")) },
            k?.str("url")?.ifBlank { null },
            k?.str("partUrl")?.ifBlank { null },
            k?.str("sourceUrl")?.ifBlank { null },
            k?.str("uploadUrl")?.ifBlank { null },
            (0 until (engines?.length() ?: 0)).map { engines!!.optString(it) }.filter { it.isNotBlank() },
            k?.str("from")?.ifBlank { null } ?: "en",
        )
    }

    /**
     * Kurdish (Sorani) subtitle generated from the English one. Translates the missing parts
     * (a few at a time) and returns the link to the finished file. Already translated titles
     * return at once. [progress] gets 0..1.
     */
    suspend fun kurdishSubtitle(kurdishUrl: String, partUrl: String, progress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        fun request(path: String) = Request.Builder().url(Config.BASE_URL + path).header("Accept", "application/json").header("User-Agent", Config.USER_AGENT)
        repeat(3) {
            val state = http.newCall(request(kurdishUrl).build()).execute().use { res ->
                if (res.code == 200) return@withContext kurdishUrl
                runCatching { JSONObject(res.body?.string().orEmpty()) }.getOrElse { JSONObject() }
            }
            val parts = state.optInt("parts", 1).coerceAtLeast(1)
            val missing = state.optJSONArray("missing").let { a -> (0 until (a?.length() ?: 0)).map { a!!.optInt(it) } }
            if (missing.isEmpty() && !state.optBoolean("preparing")) throw ApiException(502, state.optString("error").ifBlank { "Could not prepare Kurdish subtitles" })
            var done = parts - missing.size
            progress(done.toFloat() / parts)
            val token = Session.accessToken()
            // Four parts at a time keeps it quick without flooding the server.
            missing.chunked(4).forEach { group ->
                kotlinx.coroutines.coroutineScope {
                    group.map { n ->
                        async {
                            val b = request("$partUrl&n=$n")
                            token?.let { b.header("Authorization", "Bearer $it") }
                            runCatching { http.newCall(b.build()).execute().close() }
                        }
                    }.forEach { it.await() }
                }
                done += group.size
                progress(done.toFloat() / parts)
            }
        }
        throw ApiException(502, "Kurdish subtitles are not ready yet")
    }

    /** Catch-up link for a live channel: [minutes] ago, or the start of the programme on air (null). */
    suspend fun catchup(source: String, id: String, minutes: Int?): Catchup {
        val p = mutableMapOf("action" to "catchup", "source" to source, "stream_id" to id, "device" to "1")
        if (minutes == null) p["from"] = "programme" else p["back"] = minutes.toString()
        val j = get(X, p)
        return Catchup(j.str("play"), j.str("direct").ifBlank { null }, j.str("start"), j.str("title"))
    }

    /**
     * Raw request to the subtitle API (relative [path]): status code and body. Used by the
     * Kurdish preparation, which needs the 202 "still preparing" answers too.
     */
    suspend fun subtitleCall(path: String, post: JSONObject? = null): Pair<Int, String> = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(Config.BASE_URL + path).header("Accept", "application/json").header("User-Agent", Config.USER_AGENT)
        Session.accessToken()?.let { b.header("Authorization", "Bearer $it") }
        if (post != null) b.post(post.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use { res -> res.code to (res.body?.string().orEmpty()) }
    }

    /** type = live | vod | series */
    suspend fun play(source: String, type: String, id: String, ext: String = ""): PlayTokens {
        val j = get(X, mapOf("action" to "play", "source" to source, "type" to type, "id" to id, "ext" to ext, "device" to "1"))
        return PlayTokens(
            j.str("play"), j.str("fallback").ifBlank { null }, j.str("direct").ifBlank { null },
            j.str("directHls").ifBlank { null },
        )
    }

    // ---------------- IPTV (M3U playlists) ----------------

    /** Live account summary for the diagnostics card (never cached: it is a test). */
    suspend fun info(source: String): ProviderInfo {
        val j = get(X, mapOf("action" to "info", "source" to source))
        fun n(k: String) = if (j.isNull(k)) -1 else j.optInt(k, -1)
        return ProviderInfo(
            name = j.str("provider"),
            server = j.str("server"),
            reachable = j.optBoolean("reachable", false),
            status = j.str("status"),
            expires = j.str("expires"),
            trial = j.optBoolean("trial", false),
            maxConnections = j.optInt("maxConnections", 0),
            activeConnections = j.optInt("activeConnections", 0),
            live = n("live"), liveCategories = n("liveCategories"),
            vod = n("vod"), vodCategories = n("vodCategories"),
            series = n("series"), seriesCategories = n("seriesCategories"),
            ms = j.optLong("ms", 0),
        )
    }

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

    suspend fun iptvPlay(source: String, id: String): IptvPlay {
        val j = get(I, mapOf("action" to "play", "source" to source, "id" to id, "device" to "1"))
        val d = j.optJSONObject("direct")
        val h = d?.optJSONObject("headers")
        val headers = LinkedHashMap<String, String>()
        if (h != null) {
            h.optString("referer").takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
            h.optString("origin").takeIf { it.isNotBlank() }?.let { headers["Origin"] = it }
            h.optString("userAgent").takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        }
        return IptvPlay(j.str("token"), d?.optString("url")?.takeIf { it.startsWith("http") }, headers)
    }

    // ---------------- stream URLs ----------------

    /** Playback URL for an opaque token. `fix = true` asks the server to re-encode the sound to AAC. */
    fun streamUrl(token: String, fix: Boolean = false): String =
        Uri.parse("${Config.BASE_URL}/api/public/xtream-play").buildUpon()
            .appendQueryParameter("t", token)
            .apply { if (fix) appendQueryParameter("tc", "1") }
            .build().toString()
}
