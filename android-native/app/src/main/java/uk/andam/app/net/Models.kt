package uk.andam.app.net

data class Access(val signedIn: Boolean, val admin: Boolean, val sections: List<String>) {
    /** Provider Live TV, Movies and Series are all behind the `live` entitlement. */
    val live: Boolean get() = admin || "live" in sections
}

data class Provider(val id: String, val name: String)
data class Category(val id: String, val name: String, val count: Int = 0)

data class LiveChannel(
    val id: String,
    val num: Int,
    val name: String,
    val logo: String,
    val categoryId: String,
    /** The provider keeps an archive of this channel (catch-up / rewind further). */
    val archive: Boolean = false,
)

data class VodItem(
    val id: String,
    val name: String,
    val poster: String,
    val rating: String,
    val year: String,
    val genre: String,
    val categoryId: String,
    val ext: String,
    /** Unix seconds the provider added it (0 when unknown). */
    val added: Long = 0,
)

data class SeriesItem(
    val id: String,
    val name: String,
    val poster: String,
    val rating: String,
    val year: String,
    val genre: String,
    val categoryId: String,
    /** Unix seconds of the last new episode (0 when unknown). */
    val updated: Long = 0,
)

data class Episode(
    val id: String,
    val episode: Int,
    val title: String,
    val image: String,
    val plot: String,
    val duration: String,
    val play: String,
    val direct: String? = null,
)

data class Season(val season: Int, val episodes: List<Episode>)

data class SeriesInfo(
    val title: String,
    val cover: String,
    val plot: String,
    val genre: String,
    val rating: String,
    val seasons: List<Season>,
)

data class IptvSource(val id: String, val name: String)
data class IptvChannel(val id: String, val num: Int, val name: String, val logo: String, val group: String)
data class IptvList(val groups: List<Category>, val channels: List<IptvChannel>)

/** IPTV play answer: opaque token, plus a direct link + headers for Referer-protected channels. */
data class IptvPlay(val token: String, val directUrl: String?, val headers: Map<String, String>)

/** Opaque playback tokens: the app never sees provider URLs or credentials. */
data class PlayTokens(
    val play: String,
    val fallback: String?,
    /** Provider link for playing straight from this device (only for providers that block the relay). */
    val direct: String? = null,
    /** Same provider link as a playlist (live channels). */
    val directHls: String? = null,
)

/** A catch-up (archive) link for a live channel; [start] is "yyyy-MM-dd:HH-mm" on the provider's clock. */
data class Catchup(val play: String, val direct: String?, val start: String, val title: String)

class ApiException(val code: Int, message: String) : Exception(message)

/** Provider account summary from `action=info` (real counts; server host only for admins). */
data class ProviderInfo(
    val name: String,
    val server: String,
    val reachable: Boolean,
    val status: String,
    val expires: String,
    val trial: Boolean,
    val maxConnections: Int,
    val activeConnections: Int,
    val live: Int,
    val liveCategories: Int,
    val vod: Int,
    val vodCategories: Int,
    val series: Int,
    val seriesCategories: Int,
    val ms: Long,
)

/** Detail page of a film or series (provider info enriched with TMDB). */
data class CastMember(val name: String, val role: String, val photo: String)

data class MediaDetails(
    val title: String,
    val originalTitle: String,
    val tagline: String,
    val overview: String,
    val year: String,
    val runtime: Int,
    val rating: Double,
    val votes: Int,
    val genres: List<String>,
    val certification: String,
    val director: String,
    /** Series creators (films leave it empty). */
    val creator: String,
    val country: String,
    val cast: List<CastMember>,
    val poster: String,
    val backdrop: String,
    val trailer: String,
    val tmdbId: Int,
    val seasons: Int,
    val episodes: Int,
)

/** One episode's facts from TMDB (still, real name, summary). */
data class EpisodeFacts(val episode: Int, val name: String, val overview: String, val still: String, val runtime: Int)

/** A subtitle the server found online (OpenSubtitles) — `url` is relative to the server. */
data class OnlineSub(val lang: String, val label: String, val url: String)

/**
 * Online subtitles for one film / episode, plus the Kurdish (Sorani) auto-translation when one
 * can be made: [kurdishUrl] answers the finished file (add `&e=ENGINE`), [kurdishSourceUrl] /
 * [kurdishUploadUrl] serve the free Google engine run on this device, [kurdishPartUrl] the
 * server's AI engines. [kurdishEngines] lists the engines on offer ("g" is always there).
 */
data class OnlineSubs(
    val subs: List<OnlineSub>,
    val kurdishUrl: String?,
    val kurdishPartUrl: String?,
    val kurdishSourceUrl: String? = null,
    val kurdishUploadUrl: String? = null,
    val kurdishEngines: List<String> = emptyList(),
    /** Language the Kurdish is made from (en, else ar, …). */
    val kurdishFrom: String = "en",
)
