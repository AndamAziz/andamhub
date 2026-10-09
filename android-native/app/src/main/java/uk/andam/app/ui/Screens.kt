package uk.andam.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import uk.andam.app.net.Api
import uk.andam.app.net.Category
import uk.andam.app.net.SeriesInfo
import uk.andam.app.net.SeriesItem
import uk.andam.app.net.VodItem
import uk.andam.app.player.Kind
import uk.andam.app.player.PlayItem
import uk.andam.app.player.PlayQueue

// ---------------------------------------------------------------- Live TV

private class ChannelData(val cats: List<Category>, val items: List<PlayItem>, val nums: List<Int>)

@Composable
fun LiveScreen() {
    val source = Store.provider
    var reload by remember { mutableIntStateOf(0) }
    var state by remember(source) { mutableStateOf<Load<ChannelData>>(Load.Busy) }
    LaunchedEffect(source, reload) {
        if (source.isEmpty()) { state = Load.Err("No Live TV provider is available for your account."); return@LaunchedEffect }
        state = Load.Busy
        state = try {
            val cats = Api.categories(source, "live")
            val chans = Api.live(source)
            Load.Ok(
                ChannelData(
                    cats,
                    chans.map { PlayItem(Kind.LIVE, source, it.id, it.name, subtitle = cats.firstOrNull { c -> c.id == it.categoryId }?.name.orEmpty(), logo = it.logo, group = it.categoryId, archive = it.archive) },
                    chans.map { it.num },
                ),
            )
        } catch (e: Exception) {
            Load.Err(e.message ?: "Could not load channels.")
        }
    }
    when (val s = state) {
        is Load.Busy -> Busy()
        is Load.Err -> ErrorBox(s.message) { Api.clearCache(); reload++ }
        is Load.Ok -> ChannelBrowser("live:$source", s.value.cats, s.value.items, s.value.nums)
    }
}

// ---------------------------------------------------------------- IPTV

@Composable
fun IptvScreen() {
    val source = Store.iptvSource
    var reload by remember { mutableIntStateOf(0) }
    var state by remember(source) { mutableStateOf<Load<ChannelData>>(Load.Busy) }
    LaunchedEffect(source, reload) {
        if (source.isEmpty()) { state = Load.Err("No IPTV playlist is available yet."); return@LaunchedEffect }
        state = Load.Busy
        state = try {
            val l = Api.iptvChannels(source)
            Load.Ok(
                ChannelData(
                    l.groups,
                    l.channels.map { PlayItem(Kind.IPTV, source, it.id, it.name, subtitle = it.group, logo = it.logo, group = it.group) },
                    l.channels.map { it.num },
                ),
            )
        } catch (e: Exception) {
            Load.Err(e.message ?: "Could not load this playlist.")
        }
    }
    when (val s = state) {
        is Load.Busy -> Busy()
        is Load.Err -> ErrorBox(s.message) { Api.clearCache(); reload++ }
        is Load.Ok -> ChannelBrowser("iptv:$source", s.value.cats, s.value.items, s.value.nums)
    }
}

// ---------------------------------------------------------------- Movies & Series grids

@Composable
fun MoviesScreen() {
    val source = Store.provider
    // Tapping a film opens its detail page (poster, information, cast, trailer, subtitles).
    var open by remember(source) { mutableStateOf<VodItem?>(Store.pendingMovie.also { Store.pendingMovie = null }) }
    val current = open
    if (current != null) {
        MovieDetail(source, current) { open = null }
        return
    }
    PosterGrid(
        key = "vod:$source",
        loadCats = { Api.categories(source, "vod") },
        loadItems = { cat -> Api.vod(source, cat).map { Poster(it.id, it.name, it.poster, listOf(it.year, it.genre.substringBefore(',')).filter { s -> s.isNotBlank() }.joinToString(" · "), it.rating, it) } },
        onOpen = { list, index -> open = list[index].payload as VodItem },
    )
}

@Composable
fun SeriesScreen() {
    val source = Store.provider
    var open by remember(source) { mutableStateOf<SeriesItem?>(Store.pendingSeries.also { Store.pendingSeries = null }) }
    val current = open
    if (current != null) {
        BackHandler { open = null }
        SeriesDetail(source, current) { open = null }
        return
    }
    PosterGrid(
        key = "series:$source",
        loadCats = { Api.categories(source, "series") },
        loadItems = { cat -> Api.series(source, cat).map { Poster(it.id, it.name, it.poster, listOf(it.year, it.genre.substringBefore(',')).filter { s -> s.isNotBlank() }.joinToString(" · "), it.rating, it) } },
        onOpen = { list, index -> open = list[index].payload as SeriesItem },
    )
}

class Poster(val id: String, val title: String, val image: String, val sub: String, val rating: String, val payload: Any)

@Composable
private fun PosterGrid(
    key: String,
    loadCats: suspend () -> List<Category>,
    loadItems: suspend (String) -> List<Poster>,
    onOpen: (List<Poster>, Int) -> Unit,
) {
    if (Store.provider.isEmpty()) { ErrorBox("No provider is available for your account."); return }
    var cats by remember(key) { mutableStateOf<List<Category>>(emptyList()) }
    var cat by rememberSaveable(key) { mutableStateOf("") }
    var q by rememberSaveable(key) { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var state by remember(key) { mutableStateOf<Load<List<Poster>>>(Load.Busy) }

    LaunchedEffect(key) { cats = runCatching { loadCats() }.getOrDefault(emptyList()) }
    LaunchedEffect(key, cat, reload) {
        state = Load.Busy
        state = try { Load.Ok(loadItems(cat)) } catch (e: Exception) { Load.Err(e.message ?: "Could not load.") }
    }

    val tv = LocalTv.current
    Row(Modifier.fillMaxSize()) {
    if (tv && cats.isNotEmpty()) {
        TvCategoryPane(cats, cat, onSelect = { cat = it })
        Spacer(Modifier.width(16.dp))
    }
    Column(Modifier.weight(1f).fillMaxSize()) {
        if (tv) TvSearchButton(q, { q = it }, "Search titles", Modifier.padding(bottom = 6.dp))
        else {
            SearchField(q, { q = it }, "Search titles", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (cats.isNotEmpty()) CategoryBar(cats, cat, onSelect = { cat = it })
        }
        when (val s = state) {
            is Load.Busy -> Busy()
            is Load.Err -> ErrorBox(s.message) { Api.clearCache(); reload++ }
            is Load.Ok -> {
                val shown = remember(s.value, q) { s.value.filter { q.isBlank() || it.title.contains(q, ignoreCase = true) } }
                if (shown.isEmpty()) ErrorBox("Nothing here yet.") else
                    LazyVerticalGrid(
                        // 3 posters per row on phones, more on tablets/TV — like pro streaming apps.
                        columns = GridCells.Adaptive(if (tv) 122.dp else 96.dp),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = if (tv) 14.dp else 8.dp, bottom = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (tv) 18.dp else 10.dp),
                        verticalArrangement = Arrangement.spacedBy(if (tv) 20.dp else 14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) { CountLine("${shown.size} titles") }
                        items(shown.size, key = { shown[it].id + "#" + it }) { i ->
                            val p = shown[i]
                            PosterCard(p.title, p.image, p.sub, p.rating) { onOpen(shown, i) }
                        }
                    }
            }
        }
    }
    }
}

// ---------------------------------------------------------------- Series detail

@Composable
private fun SeriesDetail(source: String, series: SeriesItem, onBack: () -> Unit) {
    val context = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    var state by remember(series.id) { mutableStateOf<Load<SeriesInfo>>(Load.Busy) }
    var details by remember(series.id) { mutableStateOf<uk.andam.app.net.MediaDetails?>(null) }
    var subs by remember(series.id) { mutableStateOf<uk.andam.app.net.OnlineSubs?>(null) }
    var season by rememberSaveable(series.id) { mutableIntStateOf(-1) }
    var cc by rememberSaveable(series.id) { mutableStateOf(uk.andam.app.player.PlayerPrefs.subLang(context).ifBlank { "off" }) }
    val prep = rememberPrep()
    var facts by remember(series.id) { mutableStateOf<Map<Int, uk.andam.app.net.EpisodeFacts>>(emptyMap()) }
    LaunchedEffect(series.id, reload) {
        state = Load.Busy
        state = try { Load.Ok(Api.seriesInfo(source, series.id)) } catch (e: Exception) { Load.Err(e.message ?: "Could not load this series.") }
    }
    LaunchedEffect(series.id) {
        details = runCatching { Api.details(source, "series", series.id, name = series.name, year = series.year) }.getOrNull()
    }
    val tmdb = details?.tmdbId ?: 0
    val seasons = (state as? Load.Ok<SeriesInfo>)?.value?.seasons?.filter { it.episodes.isNotEmpty() }.orEmpty()
    val active = seasons.firstOrNull { it.season == season } ?: seasons.firstOrNull()
    // Which subtitles exist (shown under the story) — checked on the first episode of the season.
    LaunchedEffect(tmdb, active?.season) {
        val a = active
        val first = a?.episodes?.firstOrNull()
        if (tmdb > 0 && a != null && first != null) subs = runCatching { Api.subtitles(tmdb, "episode", a.season, first.episode) }.getOrNull()
    }
    // TMDB facts for the season shown: still, real episode name, summary (empty without TMDB).
    LaunchedEffect(tmdb, active?.season) {
        val a = active
        facts = if (tmdb > 0 && a != null) {
            runCatching { Api.season(source, tmdb, a.season) }.getOrDefault(emptyList()).associateBy { it.episode }
        } else emptyMap()
    }
    val seriesTitle = details?.title?.ifBlank { null } ?: series.name
    fun nameOf(e: uk.andam.app.net.Episode): String {
        val real = facts[e.episode]?.name.orEmpty()
        return if (real.isNotBlank() && genericEpisodeTitle(e.title, series.name)) real else e.title
    }
    fun playFrom(i: Int) {
        val a = active ?: return
        val queue = a.episodes.map { e ->
            PlayItem(
                Kind.EPISODE, source, e.id,
                title = "$seriesTitle · S${a.season} E${e.episode}",
                subtitle = nameOf(e), logo = series.poster, token = e.play, direct = e.direct,
                tmdb = tmdb, season = a.season, episode = e.episode,
            )
        }
        // The chosen subtitle is prepared for the episode tapped; later ones in the player.
        prep.play(context, queue[i], cc) { first -> PlayQueue.open(context, queue.map { it.copy(subLang = first.subLang) }, i) }
    }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().background(C.Bg), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            val info = (state as? Load.Ok<SeriesInfo>)?.value
            MediaHero(
                details = details,
                fallbackTitle = info?.title?.ifBlank { null } ?: series.name,
                fallbackPoster = info?.cover?.ifBlank { null } ?: series.poster,
                fallbackMeta = listOf(series.year, info?.genre.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                onBack = onBack,
                actions = {
                    if (active != null) HeroButton("Play S${active.season} E${active.episodes.first().episode}", Icons.Filled.PlayArrow, primary = true, modifier = Modifier.weight(1f)) { playFrom(0) }
                    details?.trailer?.takeIf { it.isNotBlank() }?.let { key ->
                        HeroButton("Trailer", Icons.Filled.Theaters, primary = false) { openTrailer(context, key) }
                    }
                },
                cc = {
                    val a = active
                    val firstEp = a?.episodes?.firstOrNull()
                    if (tmdb > 0 && a != null && firstEp != null) {
                        CcRow(subs, cc, { lang -> uk.andam.app.player.SubPrep.Key(tmdb, "episode", a.season, firstEp.episode, lang) }) { cc = it }
                    }
                },
            )
        }
        when (val s = state) {
            is Load.Busy -> item { Box(Modifier.fillMaxWidth().height(160.dp)) { Busy() } }
            is Load.Err -> item { Box(Modifier.fillMaxWidth().height(200.dp)) { ErrorBox(s.message) { reload++ } } }
            is Load.Ok -> {
                if (active == null) { item { Box(Modifier.fillMaxWidth().height(160.dp)) { ErrorBox("No episodes yet.") } } }
                else {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(seasons, key = { it.season }) { se ->
                                Chip("Season ${se.season}", se.season == active.season) { season = se.season }
                            }
                        }
                    }
                    itemsIndexed(active.episodes, key = { i, e -> e.id + "#" + i }) { i, ep ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .tvFocus(RoundedCornerShape(14.dp), 1.02f)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { playFrom(i) }
                                .background(C.Surface)
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val f = facts[ep.episode]
                            val still = f?.still?.ifBlank { null } ?: ep.image
                            Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(C.Surface2), contentAlignment = Alignment.Center) {
                                if (still.isNotBlank()) AsyncImage(model = still, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
                                Text(
                                    "E${ep.episode}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text("Episode ${ep.episode}", color = C.Ember, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(nameOf(ep), color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val summary = f?.overview?.ifBlank { null } ?: ep.plot
                                if (summary.isNotBlank()) Text(summary, color = C.Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (ep.duration.isNotBlank()) Text(ep.duration, color = C.Faint, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    PrepOverlay(prep)
    }
}

/** A provider episode title that says nothing ("Name - S01E03", "Episode 3"): TMDB's name is shown instead. */
private fun genericEpisodeTitle(title: String, series: String): Boolean {
    val t = title.trim()
    if (t.isBlank()) return true
    if (Regex("(?i)\\bS\\d{1,2}\\s*E\\d{1,3}\\b").containsMatchIn(t)) return true
    if (Regex("(?i)^(episode|ep\\.?|e)\\s*\\d+$").matches(t)) return true
    return t.equals(series.trim(), ignoreCase = true)
}

// ---------------------------------------------------------------- Home

@Composable
fun HomeScreen(onTab: (Int) -> Unit) {
    val context = LocalContext.current
    val access = Store.access
    var live by remember(Store.provider) { mutableStateOf<List<PlayItem>>(emptyList()) }
    var liveCats by remember(Store.provider) { mutableStateOf<List<Category>>(emptyList()) }
    var iptv by remember(Store.iptvSource) { mutableStateOf<List<PlayItem>>(emptyList()) }
    var iptvCats by remember(Store.iptvSource) { mutableStateOf<List<Category>>(emptyList()) }
    var movies by remember(Store.provider) { mutableStateOf<List<VodItem>>(emptyList()) }
    var series by remember(Store.provider) { mutableStateOf<List<SeriesItem>>(emptyList()) }
    var newLive by remember(Store.provider) { mutableStateOf<Set<String>>(emptySet()) }

    // Provider content, refreshed every 10 minutes while Home is open so newly added
    // channels, films and series appear on their own (they are marked NEW in the spotlight).
    LaunchedEffect(Store.provider, access?.live) {
        val src = Store.provider
        if (src.isEmpty() || access?.live != true) return@LaunchedEffect
        while (true) {
            runCatching {
                val cats = runCatching { Api.categories(src, "live") }.getOrDefault(emptyList())
                liveCats = cats
                val raw = Api.live(src)
                newLive = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    HeroPicker.newLiveIds(context, "live:$src", raw.map { it.id })
                }
                live = raw.map { ch ->
                    PlayItem(Kind.LIVE, src, ch.id, ch.name, subtitle = cats.firstOrNull { it.id == ch.categoryId }?.name.orEmpty(), logo = ch.logo, group = ch.categoryId, archive = ch.archive)
                }
            }
            runCatching { movies = Api.vod(src, "") }
            runCatching { series = Api.series(src, "") }
            kotlinx.coroutines.delay(10 * 60_000L)
            Api.forget("live:$src"); Api.forget("vod:$src:"); Api.forget("series:$src:")
        }
    }
    LaunchedEffect(Store.iptvSource) {
        val src = Store.iptvSource
        if (src.isEmpty()) return@LaunchedEffect
        while (true) {
            runCatching {
                val l = Api.iptvChannels(src)
                iptvCats = l.groups
                iptv = l.channels.map { PlayItem(Kind.IPTV, src, it.id, it.name, subtitle = it.group, logo = it.logo, group = it.group) }
            }
            kotlinx.coroutines.delay(10 * 60_000L)
            Api.forget("iptv:ch:$src")
        }
    }
    // Sorting thousands of titles happens off the main thread.
    var slides by remember { mutableStateOf<List<HeroSlide>>(emptyList()) }
    LaunchedEffect(live, iptv, movies, series, newLive) {
        val cats = liveCats
        slides = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            HeroPicker.build(live, newLive, iptv, movies, series) { id -> cats.firstOrNull { it.id == id }?.name.orEmpty() }
        }
    }
    val openSlide: (HeroSlide) -> Unit = { sl ->
        when (sl.kind) {
            "live" -> live.indexOfFirst { it.id == sl.live?.id }.takeIf { it >= 0 }?.let { PlayQueue.open(context, live, it, liveCats) }
            "iptv" -> iptv.indexOfFirst { it.id == sl.live?.id }.takeIf { it >= 0 }?.let { PlayQueue.open(context, iptv, it, iptvCats) }
            "movie" -> sl.movie?.let { Store.pendingMovie = it; onTab(2) }
            "series" -> sl.series?.let { Store.pendingSeries = it; onTab(3) }
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            if (slides.isNotEmpty()) {
                HeroCarousel(slides, openSlide)
            } else {
                Box(
                    Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF2A1218), C.Surface, C.Surface)))
                        .padding(20.dp),
                ) {
                    Column {
                        Text("Watch now", color = C.Ember, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("Live TV, movies and series in one place", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { onTab(if (access?.live == true) 1 else 4) },
                                colors = ButtonDefaults.buttonColors(containerColor = C.Ember),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.tvFocus(RoundedCornerShape(12.dp)),
                            ) { Text(if (access?.live == true) "Live TV" else "IPTV") }
                            if (access?.live == true) Button(
                                onClick = { onTab(2) },
                                colors = ButtonDefaults.buttonColors(containerColor = C.Surface3, contentColor = C.Text),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.tvFocus(RoundedCornerShape(12.dp)),
                            ) { Text("Movies") }
                        }
                    }
                }
            }
        }
        if (live.isNotEmpty()) {
            item { SectionTitle("Live channels", "See all") { onTab(1) } }
            item { ChannelStrip(live) { i -> PlayQueue.open(context, live, i, liveCats) } }
        }
        if (iptv.isNotEmpty()) {
            item { SectionTitle("IPTV", "See all") { onTab(4) } }
            item { ChannelStrip(iptv) { i -> PlayQueue.open(context, iptv, i, iptvCats) } }
        }
        if (access?.live != true) {
            item {
                Text(
                    "Live TV, Movies and Series unlock with an activation code — open Live TV to enter yours.",
                    color = C.Muted, modifier = Modifier.padding(16.dp),
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ChannelStrip(list: List<PlayItem>, onPlay: (Int) -> Unit) {
    val first = remember(list) { list.take(24) }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(first, key = { i, it -> it.id + "#" + i }) { i, ch ->
            Column(
                Modifier
                    .width(112.dp)
                    .tvFocus(RoundedCornerShape(16.dp), 1.07f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(C.Surface)
                    .clickable { onPlay(i) }
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Logo(ch.logo, ch.title, 64)
                Text(ch.title, color = C.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
