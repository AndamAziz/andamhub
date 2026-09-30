package uk.andam.app.ui

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import uk.andam.app.net.SeriesItem
import uk.andam.app.net.VodItem
import uk.andam.app.player.PlayItem
import java.util.Calendar

/** One slide of the Home spotlight. */
class HeroSlide(
    val key: String,
    val kind: String, // "live" | "iptv" | "movie" | "series"
    val title: String,
    val image: String,
    val meta: String,
    val rating: String,
    val isNew: Boolean,
    val live: PlayItem? = null,
    val movie: VodItem? = null,
    val series: SeriesItem? = null,
)

/**
 * Picks what the spotlight shows. Newly added titles and channels come first (marked NEW),
 * then the latest movies and series, mixed with live channels. The mix changes through the
 * day, so the Home screen never looks the same twice.
 */
object HeroPicker {
    private const val NEW_WINDOW_S = 3L * 24 * 3600 // "NEW" for three days

    /**
     * Remembers which live channels existed before, so channels added later are recognised as
     * new (live channels carry no "added" date). The first visit only records the list.
     */
    fun newLiveIds(context: Context, key: String, ids: List<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val prefs = context.getSharedPreferences("hero", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis() / 1000
        val known = prefs.getString("known:$key", null)?.split('\n')?.toHashSet()
        // id -> first-seen time, kept for three days
        val fresh = prefs.getString("fresh:$key", "").orEmpty().split('\n')
            .mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to (it[1].toLongOrNull() ?: 0L) } }
            .filter { now - it.second < NEW_WINDOW_S }
            .toMap().toMutableMap()
        if (known != null) ids.filter { it !in known && it !in fresh }.forEach { fresh[it] = now }
        prefs.edit()
            .putString("known:$key", ids.joinToString("\n"))
            .putString("fresh:$key", fresh.entries.joinToString("\n") { "${it.key}\t${it.value}" })
            .apply()
        val current = ids.toHashSet()
        return fresh.keys.filter { it in current }.toSet()
    }

    fun build(
        live: List<PlayItem>,
        newLive: Set<String>,
        iptv: List<PlayItem>,
        movies: List<VodItem>,
        series: List<SeriesItem>,
        liveCatName: (String) -> String,
    ): List<HeroSlide> {
        val now = System.currentTimeMillis() / 1000
        val seed = Calendar.getInstance().let { it.get(Calendar.DAY_OF_YEAR) * 24 + it.get(Calendar.HOUR_OF_DAY) }
        val rnd = kotlin.random.Random(seed)

        fun ratingOf(r: String) = r.toFloatOrNull()?.let { if (it > 10f) it / 10f else it } ?: 0f

        // Movies: newest first when the provider tells us, otherwise the best rated with a poster.
        val withPoster = movies.filter { it.poster.isNotBlank() }
        val movieSlides = (
            if (withPoster.any { it.added > 0 }) withPoster.sortedByDescending { it.added }.take(4)
            else withPoster.sortedByDescending { ratingOf(it.rating) }.take(20).shuffled(rnd).take(4)
            ).map { m ->
            HeroSlide(
                "m:${m.id}", "movie", m.name, m.poster,
                listOf(m.year, m.genre.substringBefore(',').trim()).filter { it.isNotBlank() }.joinToString(" · "),
                m.rating, m.added > 0 && now - m.added < NEW_WINDOW_S, movie = m,
            )
        }

        val seriesPoster = series.filter { it.poster.isNotBlank() }
        val seriesSlides = (
            if (seriesPoster.any { it.updated > 0 }) seriesPoster.sortedByDescending { it.updated }.take(3)
            else seriesPoster.sortedByDescending { ratingOf(it.rating) }.take(15).shuffled(rnd).take(3)
            ).map { s ->
            HeroSlide(
                "s:${s.id}", "series", s.name, s.poster,
                listOf(s.year, s.genre.substringBefore(',').trim()).filter { it.isNotBlank() }.joinToString(" · "),
                s.rating, s.updated > 0 && now - s.updated < NEW_WINDOW_S, series = s,
            )
        }

        // Live: new channels first, then a changing handful of channels that have a logo.
        val liveWithLogo = live.filter { it.logo.isNotBlank() }
        val newOnes = live.filter { it.id in newLive }.take(3)
        val others = liveWithLogo.filter { it.id !in newLive }.shuffled(rnd).take(4 - newOnes.size.coerceAtMost(3))
        val liveSlides = (newOnes + others).map { ch ->
            HeroSlide(
                "l:${ch.id}", "live", ch.title, ch.logo,
                liveCatName(ch.group).ifBlank { ch.subtitle }, "", ch.id in newLive, live = ch,
            )
        }
        val iptvSlides = if (liveSlides.isEmpty()) iptv.filter { it.logo.isNotBlank() }.shuffled(rnd).take(4).map { ch ->
            HeroSlide("i:${ch.id}", "iptv", ch.title, ch.logo, ch.group, "", false, live = ch)
        } else emptyList()

        // New things lead; the rest alternate movie / live / series so the mix feels alive.
        val all = movieSlides + seriesSlides + liveSlides + iptvSlides
        val lead = all.filter { it.isNew }
        val queues = listOf(movieSlides, liveSlides + iptvSlides, seriesSlides).map { q -> q.filter { !it.isNew }.toMutableList() }
        val rest = ArrayList<HeroSlide>()
        while (queues.any { it.isNotEmpty() }) queues.forEach { q -> if (q.isNotEmpty()) rest.add(q.removeAt(0)) }
        return (lead + rest).distinctBy { it.key }.take(10)
    }
}

/** Auto-rotating spotlight at the top of Home. */
@Composable
fun HeroCarousel(slides: List<HeroSlide>, onOpen: (HeroSlide) -> Unit) {
    if (slides.isEmpty()) return
    val tv = LocalTv.current
    val pager = rememberPagerState(pageCount = { slides.size })
    // Advance every 6 s; a swipe restarts the timer.
    LaunchedEffect(pager.currentPage, slides.size, pager.isScrollInProgress) {
        if (pager.isScrollInProgress || slides.size < 2) return@LaunchedEffect
        delay(6000)
        pager.animateScrollToPage((pager.currentPage + 1) % slides.size)
    }
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        HorizontalPager(
            state = pager,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            pageSpacing = 10.dp,
            key = { slides[it].key },
        ) { page ->
            HeroCard(slides[page], height = if (tv) 300 else 236) { onOpen(slides[page]) }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slides.indices.forEach { i ->
                val on = i == pager.currentPage
                val w by animateDpAsState(if (on) 20.dp else 6.dp, label = "dot")
                val c by animateColorAsState(if (on) C.Ember else Color(0x40FFFFFF), label = "dotc")
                Box(Modifier.padding(horizontal = 3.dp).size(width = w, height = 6.dp).clip(CircleShape).background(c))
            }
        }
    }
}

@Composable
private fun HeroCard(s: HeroSlide, height: Int, onOpen: () -> Unit) {
    val isChannel = s.kind == "live" || s.kind == "iptv"
    Box(
        Modifier
            .fillMaxWidth()
            .height(height.dp)
            .tvFocus(RoundedCornerShape(22.dp), 1.02f)
            .clip(RoundedCornerShape(22.dp))
            .background(C.Surface)
            .clickable(onClick = onOpen),
    ) {
        // Backdrop: the poster itself, wide and dimmed; channels get a warm glow with a faint logo.
        if (isChannel) {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF4A1019), C.Surface), radius = 900f)))
            if (s.image.isNotBlank()) AsyncImage(
                model = s.image, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxHeight().aspectRatio(1f).align(Alignment.CenterEnd).padding(10.dp).alpha(0.10f),
            )
        } else if (s.image.isNotBlank()) {
            AsyncImage(
                model = s.image, contentDescription = null, contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize().alpha(0.55f),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF20A0B0F), Color(0xB30A0B0F), Color(0x330A0B0F)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xCC0A0B0F))))

        Row(Modifier.fillMaxSize().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    KindChip(s.kind)
                    if (s.isNew) Tag("NEW", C.Gold, Color(0xFF1A1405))
                }
                Text(
                    s.title, color = Color.White, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.ExtraBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
                )
                val r = s.rating.toFloatOrNull()?.let { if (it > 10f) it / 10f else it }
                val meta = listOfNotNull(s.meta.ifBlank { null }, r?.takeIf { it > 0f }?.let { "★ %.1f".format(it) }).joinToString("  ·  ")
                if (meta.isNotBlank()) Text(meta, color = C.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(C.Ember)
                        .padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Text(
                        when (s.kind) { "series" -> "Episodes"; "movie" -> "Play"; else -> "Watch live" },
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            // Artwork: sharp poster for films/series, logo tile for channels.
            if (isChannel) {
                Box(
                    Modifier
                        .size(112.dp)
                        .shadow(18.dp, RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF1E2028))
                        .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(s.title.trim().take(1).uppercase(), color = C.Muted, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    if (s.image.isNotBlank()) AsyncImage(
                        model = s.image, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().background(Color(0xFF1E2028)).padding(14.dp),
                    )
                }
            } else {
                AsyncImage(
                    model = s.image, contentDescription = s.title, contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxHeight(0.92f)
                        .aspectRatio(2f / 3f)
                        .shadow(20.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(C.Surface2),
                )
            }
        }
    }
}

@Composable
private fun KindChip(kind: String) {
    when (kind) {
        "live", "iptv" -> Row(
            Modifier.clip(RoundedCornerShape(50)).background(C.Ember).padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
            Text(if (kind == "iptv") "IPTV" else "LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 5.dp))
        }
        "movie" -> Tag("MOVIE", Color(0x33FFFFFF), Color.White)
        else -> Tag("SERIES", Color(0x33FFFFFF), Color.White)
    }
}

@Composable
private fun Tag(text: String, bg: Color, fg: Color) {
    Text(
        text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}
