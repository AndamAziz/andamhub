package uk.andam.app.player

import android.content.Context
import android.content.Intent
import uk.andam.app.net.Category

enum class Kind { LIVE, VOD, EPISODE, IPTV }

data class PlayItem(
    val kind: Kind,
    /** Provider slug (Xtream) or playlist slug (IPTV). */
    val source: String,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val logo: String = "",
    val ext: String = "",
    /** Category id (Live) or group name (IPTV) — drives the in-player channel panel. */
    val group: String = "",
    /** Pre-minted token (series episodes arrive with one). */
    val token: String? = null,
    /** Provider link to try straight from the device (series episodes arrive with one). */
    val direct: String? = null,
    /** TMDB id (the film, or the series for an episode) — finds online subtitles. 0 = unknown. */
    val tmdb: Int = 0,
    val season: Int = 0,
    val episode: Int = 0,
    /** Live channel with a provider archive (tv_archive=1): it can be rewound further (catch-up). */
    val archive: Boolean = false,
    /** Catch-up (archive) playback of this live channel; Live returns to it. */
    val catchupOf: PlayItem? = null,
    /** Subtitle chosen on the detail page: "" = the viewer's default, "off" = none, else a language. */
    val subLang: String = "",
) {
    val isLive: Boolean get() = (kind == Kind.LIVE || kind == Kind.IPTV) && catchupOf == null
    val resumeKey: String get() = "${kind.name}:$source:$id"
}

/** Hand-off between the catalog screens and the player activity (lists can be thousands long). */
object PlayQueue {
    var items: List<PlayItem> = emptyList()
        private set
    var index: Int = 0
    var groups: List<Category> = emptyList()
        private set

    fun open(context: Context, items: List<PlayItem>, index: Int, groups: List<Category> = emptyList()) {
        if (items.isEmpty()) return
        this.items = items
        this.index = index.coerceIn(0, items.lastIndex)
        // Always give the in-player panel a category column: when the caller had no list,
        // derive one from the items themselves.
        this.groups = groups.ifEmpty {
            items.filter { it.group.isNotEmpty() }
                .distinctBy { it.group }
                .map { Category(it.group, it.subtitle.ifBlank { it.group }) }
                .takeIf { it.size > 1 } ?: emptyList()
        }
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }

    fun current(): PlayItem? = items.getOrNull(index)
}

/** Where VOD / episodes stopped, so they resume. */
object Resume {
    private fun prefs(c: Context) = c.getSharedPreferences("andam_resume", Context.MODE_PRIVATE)
    fun get(c: Context, key: String): Long = prefs(c).getLong(key, 0L)
    /** Length of the title when it was last watched (0 when unknown) — for progress bars. */
    fun duration(c: Context, key: String): Long = prefs(c).getLong("$key#d", 0L)
    fun put(c: Context, key: String, positionMs: Long, durationMs: Long) {
        val done = durationMs > 0 && positionMs > durationMs - 90_000
        prefs(c).edit().apply {
            if (done || positionMs < 30_000) { remove(key); remove("$key#d") } else { putLong(key, positionMs); putLong("$key#d", durationMs) }
        }.apply()
    }
    fun clear(c: Context, key: String) = prefs(c).edit().remove(key).remove("$key#d").apply()
}
