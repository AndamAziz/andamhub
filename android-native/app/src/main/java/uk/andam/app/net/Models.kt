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
)

data class SeriesItem(
    val id: String,
    val name: String,
    val poster: String,
    val rating: String,
    val year: String,
    val genre: String,
    val categoryId: String,
)

data class Episode(
    val id: String,
    val episode: Int,
    val title: String,
    val image: String,
    val plot: String,
    val duration: String,
    val play: String,
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

/** Opaque playback tokens: the app never sees provider URLs or credentials. */
data class PlayTokens(val play: String, val fallback: String?)

class ApiException(val code: Int, message: String) : Exception(message)
