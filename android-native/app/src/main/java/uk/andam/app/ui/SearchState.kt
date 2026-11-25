package uk.andam.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * What a search box shows: [indices] of the matching items, best first (null = no query, show
 * everything), [corrected] when the server's spelling fixer found what was meant, [busy] while
 * that fixer is being asked.
 */
data class SearchResult(val indices: List<Int>?, val corrected: String? = null, val busy: Boolean = false)

/**
 * Smart search over one list (see [SmartSearch]). The list is prepared once and every query is
 * ranked off the main thread, a moment after typing stops. When nothing matches and [fix] is
 * given, it is asked for the intended title (typos, Kurdish / Arabic spelling) and the list is
 * searched again with that.
 */
@Composable
fun rememberSmartSearch(
    listKey: Any,
    size: Int,
    q: String,
    title: (Int) -> String,
    year: (Int) -> String = { "" },
    extra: (Int) -> String = { "" },
    fix: (suspend (String) -> String?)? = null,
): SearchResult {
    val index by produceState<SmartSearch.Index?>(null, listKey) {
        value = null
        value = withContext(Dispatchers.Default) { SmartSearch.Index(size, title, year, extra) }
    }
    val result by produceState(SearchResult(null), index, q) {
        val query = q.trim()
        if (query.isEmpty()) { value = SearchResult(null); return@produceState }
        val ix = index
        if (ix == null) {
            // Still preparing a very long list: a plain match meanwhile.
            value = SearchResult((0 until size).filter { title(it).contains(query, ignoreCase = true) })
            return@produceState
        }
        delay(150)
        val hits = withContext(Dispatchers.Default) { ix.rank(query) }
        if (hits.isNotEmpty() || fix == null || query.length < 3) { value = SearchResult(hits); return@produceState }
        value = SearchResult(hits, busy = true)
        delay(350)
        val meant = runCatching { fix(query) }.getOrNull()?.trim()
        if (!meant.isNullOrEmpty() && !meant.equals(query, ignoreCase = true)) {
            val again = withContext(Dispatchers.Default) { ix.rank(meant) }
            if (again.isNotEmpty()) { value = SearchResult(again, corrected = meant); return@produceState }
        }
        value = SearchResult(hits)
    }
    return result
}
