package uk.andam.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import uk.andam.app.net.Api
import uk.andam.app.net.CastMember
import uk.andam.app.net.MediaDetails
import uk.andam.app.net.OnlineSubs
import uk.andam.app.net.VodItem
import uk.andam.app.player.Kind
import uk.andam.app.player.PlayItem
import uk.andam.app.player.PlayQueue
import uk.andam.app.player.PlayerPrefs
import uk.andam.app.player.Resume
import uk.andam.app.player.SubPrep

/*
 * Detail pages for films and series: faded backdrop, the poster with the facts beside it (title,
 * original title, ★ rating · votes, year, length or seasons · episodes, genres, tagline), Play /
 * From start / Trailer, the story, cast with photos, director or creator, country, and a CC row
 * that prepares subtitles before playback ("Preparing subtitles… 45%").
 *
 * Every fact is optional: without TMDB the page shows the provider's own info as before.
 */

/** Film detail page; Play starts the film (with the subtitle chosen in the CC row). */
@Composable
fun MovieDetail(source: String, vod: VodItem, onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler { onBack() }
    var details by remember(vod.id) { mutableStateOf<MediaDetails?>(null) }
    var subs by remember(vod.id) { mutableStateOf<OnlineSubs?>(null) }
    LaunchedEffect(vod.id) {
        details = runCatching { Api.details(source, "movie", vod.id) }.getOrNull()
        val tmdb = details?.tmdbId ?: 0
        if (tmdb > 0) subs = runCatching { Api.subtitles(tmdb, "movie") }.getOrNull()
    }
    val item = PlayItem(
        Kind.VOD, source, vod.id, details?.title?.ifBlank { null } ?: vod.name,
        subtitle = details?.year ?: vod.year, logo = vod.poster, ext = vod.ext, tmdb = details?.tmdbId ?: 0,
    )
    var resume by remember(vod.id) { mutableLongStateOf(0L) }
    var length by remember(vod.id) { mutableLongStateOf(0L) }
    LaunchedEffect(vod.id) { resume = Resume.get(context, item.resumeKey); length = Resume.duration(context, item.resumeKey) }
    var cc by rememberSaveable(vod.id) { mutableStateOf(PlayerPrefs.subLang(context).ifBlank { "off" }) }
    val prep = rememberPrep()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().background(C.Bg), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                MediaHero(
                    details = details,
                    fallbackTitle = vod.name,
                    fallbackPoster = vod.poster,
                    fallbackMeta = listOf(vod.year, vod.genre.substringBefore(',')).filter { it.isNotBlank() }.joinToString(" · "),
                    onBack = onBack,
                    progress = if (resume > 0 && length > 0) resume.toFloat() / length else 0f,
                    actions = {
                        HeroButton(
                            if (resume > 0) "Resume ${clock(resume)}" else "Play",
                            Icons.Filled.PlayArrow, primary = true, modifier = Modifier.weight(1f),
                        ) { prep.play(context, item, cc) { PlayQueue.open(context, listOf(it), 0) } }
                        if (resume > 0) HeroButton("From start", Icons.Filled.Replay, primary = false) {
                            Resume.clear(context, item.resumeKey); resume = 0
                            prep.play(context, item, cc) { PlayQueue.open(context, listOf(it), 0) }
                        }
                        details?.trailer?.takeIf { it.isNotBlank() }?.let { key ->
                            HeroButton("Trailer", Icons.Filled.Theaters, primary = false) { openTrailer(context, key) }
                        }
                    },
                    cc = {
                        if (item.tmdb > 0) CcRow(subs, cc, { SubPrep.key(item, it) }) { lang ->
                            cc = lang
                            if (lang != "off") SubPrep.prepare(context, SubPrep.key(item, lang))
                        }
                    },
                )
            }
        }
        PrepOverlay(prep)
    }
}

/**
 * Waits for the chosen subtitle before playback starts, with "Play without subtitles" as a way
 * out. [play] hands the item (with its subtitle choice) to [open] once ready.
 */
class PrepWait {
    var key by mutableStateOf<SubPrep.Key?>(null)
    var pending: (() -> Unit)? = null
    var skip: (() -> Unit)? = null

    fun play(context: android.content.Context, item: PlayItem, lang: String, open: (PlayItem) -> Unit) {
        if (lang == "off" || item.tmdb <= 0) { open(item.copy(subLang = "off")); return }
        val k = SubPrep.key(item, lang)
        if (SubPrep.ready(k) != null) { open(item.copy(subLang = lang)); return }
        SubPrep.prepare(context, k)
        pending = { open(item.copy(subLang = lang)) }
        skip = { open(item.copy(subLang = "off")) }
        key = k
    }

    fun retry(context: android.content.Context) { key?.let { SubPrep.prepare(context, it) } }
    fun cancel() { key = null; pending = null; skip = null }
}

@Composable
fun rememberPrep(): PrepWait = remember { PrepWait() }

/** "Preparing subtitles… 45%" over the page while the chosen subtitle is made. */
@Composable
fun PrepOverlay(prep: PrepWait) {
    val context = LocalContext.current
    val k = prep.key ?: return
    val state = SubPrep.states[k]
    LaunchedEffect(state) {
        if (state is SubPrep.State.Ready) { val go = prep.pending; prep.cancel(); go?.invoke() }
    }
    BackHandler { prep.cancel() }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 420.dp).clip(RoundedCornerShape(20.dp)).background(C.Surface).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (state) {
                is SubPrep.State.Failed -> {
                    Text(state.message, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    HeroButton("Try again", Icons.Filled.Replay, primary = true, modifier = Modifier.fillMaxWidth()) { prep.retry(context) }
                }
                else -> {
                    val p = (state as? SubPrep.State.Working)?.progress ?: 0f
                    CircularProgressIndicator(color = C.Ember, trackColor = Color(0x33FFFFFF), strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
                    Text(
                        "Preparing ${SubPrep.label(k.lang)} subtitles… ${(p * 100).toInt()}%",
                        color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                    if (k.lang == "ku") Text("The first viewer of a title makes them; everyone after gets them at once.", color = C.Faint, fontSize = 12.sp)
                }
            }
            HeroButton("Play without subtitles", Icons.Filled.PlayArrow, primary = false, modifier = Modifier.fillMaxWidth()) {
                val go = prep.skip; prep.cancel(); go?.invoke()
            }
        }
    }
}

/**
 * CC row: Off, Kurdish · AI, English, العربية, then the other languages found. Languages with no
 * subtitle for this title are dimmed. A chip that is being prepared shows its percent, then ✓.
 */
@Composable
fun CcRow(subs: OnlineSubs?, selected: String, keyOf: (String) -> SubPrep.Key, onSelect: (String) -> Unit) {
    val found = subs?.subs?.map { it.lang }.orEmpty()
    val kurdish = subs != null && (subs.kurdishUrl != null || "ku" in found)
    val langs = listOf("off", "ku", "en", "ar") + found.filter { it !in setOf("ku", "en", "ar") }.distinct()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SUBTITLES", color = C.Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(langs) { lang ->
                val available = lang == "off" || (lang == "ku" && kurdish) || lang in found
                val on = selected == lang
                val state = if (lang == "off") null else SubPrep.states[keyOf(lang)]
                Row(
                    Modifier
                        .alpha(if (available || subs == null) 1f else 0.38f)
                        .tvFocus(RoundedCornerShape(50))
                        .clip(RoundedCornerShape(50))
                        .background(if (on) C.Ember else Color(0x1FFFFFFF))
                        .clickable(enabled = available) { onSelect(lang) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (lang) { "off" -> "Off"; "ku" -> if ("ku" in found) "Kurdish" else "Kurdish · AI"; else -> SubPrep.label(lang) },
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                    when (state) {
                        is SubPrep.State.Working -> {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                            Text(" ${(state.progress * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                        }
                        is SubPrep.State.Ready -> Text("  ✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        is SubPrep.State.Failed -> Text("  !", color = C.Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        null -> {}
                    }
                }
            }
        }
        val failed = if (selected == "off") null else SubPrep.states[keyOf(selected)] as? SubPrep.State.Failed
        when {
            subs == null -> Text("Finding subtitles…", color = C.Faint, fontSize = 12.sp)
            failed != null -> Text(failed.message, color = C.Gold, fontSize = 12.sp)
        }
    }
}

/**
 * The top of a detail page. [actions] are the buttons under the information (Play, Trailer…),
 * [cc] the subtitle row. Used by the film page and at the top of the series page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaHero(
    details: MediaDetails?,
    fallbackTitle: String,
    fallbackPoster: String,
    fallbackMeta: String,
    onBack: () -> Unit,
    progress: Float = 0f,
    actions: @Composable RowScope.() -> Unit,
    cc: @Composable ColumnScope.() -> Unit = {},
) {
    val d = details
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 700.dp
        val backdropH = if (wide) 340.dp else 250.dp
        val posterW = if (wide) 170.dp else 118.dp
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()) {
                // Faded backdrop behind the header.
                Box(Modifier.fillMaxWidth().height(backdropH)) {
                    AsyncImage(
                        model = d?.backdrop?.ifBlank { null } ?: d?.poster?.ifBlank { null } ?: fallbackPoster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = 0.6f,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(C.Bg.copy(alpha = 0.45f), Color.Transparent, C.Bg.copy(alpha = 0.8f), C.Bg)),
                        ),
                    )
                    if (wide) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(C.Bg.copy(alpha = 0.85f), Color.Transparent))))
                    Box(
                        Modifier.statusBarsPadding().padding(10.dp).size(42.dp).tvFocus(CircleShape).clip(CircleShape)
                            .background(Color(0x99000000)).clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                }
                // Poster with the facts beside it, overlapping the bottom of the backdrop.
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = backdropH - (if (wide) 150.dp else 105.dp)),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    AsyncImage(
                        model = d?.poster?.ifBlank { null } ?: fallbackPoster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(posterW).aspectRatio(2f / 3f).clip(RoundedCornerShape(16.dp))
                            .background(C.Surface2).border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp)),
                    )
                    Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        val title = d?.title?.ifBlank { null } ?: fallbackTitle
                        Text(
                            title,
                            color = C.Text, fontSize = if (wide) 28.sp else 21.sp, fontWeight = FontWeight.Bold,
                            lineHeight = if (wide) 32.sp else 25.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                        // Original / translated title, only when it really differs (year ignored).
                        val original = d?.originalTitle.orEmpty()
                        if (original.isNotBlank() && withoutYear(original) != withoutYear(title)) {
                            Text(original, color = C.Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (d == null) {
                            if (fallbackMeta.isNotBlank()) Text(fallbackMeta, color = C.Muted, fontSize = 13.sp)
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (d.rating > 0) FactChip(
                                    "★ " + "%.1f".format(java.util.Locale.US, d.rating) + (if (d.votes > 0) "  ${compact(d.votes)} votes" else ""),
                                    gold = true,
                                )
                                d.year.ifBlank { null }?.let { FactChip(it) }
                                when {
                                    d.seasons > 0 -> FactChip(
                                        "${d.seasons} season${if (d.seasons > 1) "s" else ""}" +
                                            if (d.episodes > 0) " · ${d.episodes} episodes" else "",
                                    )
                                    else -> runtimeText(d.runtime)?.let { FactChip(it) }
                                }
                                d.certification.ifBlank { null }?.let { FactChip(it) }
                            }
                            if (d.genres.isNotEmpty()) {
                                Text(d.genres.take(4).joinToString("  ·  "), color = C.Ember, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            d.tagline.ifBlank { null }?.let {
                                Text(it, color = C.Muted, fontSize = 13.sp, fontStyle = FontStyle.Italic, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            Column(
                Modifier.padding(horizontal = 16.dp).padding(top = 18.dp).widthIn(max = 900.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
                if (progress > 0f) {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x26FFFFFF))) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).background(C.Ember))
                    }
                }
                cc()

                val story = d?.overview.orEmpty()
                if (story.isNotBlank()) {
                    var more by remember(story) { mutableStateOf(false) }
                    Column(Modifier.tvFocus(RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).clickable { more = !more }) {
                        Text(
                            story, color = C.Text.copy(alpha = 0.88f), fontSize = 14.sp, lineHeight = 21.sp,
                            maxLines = if (more) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                        )
                        if (story.length > 220) Text(if (more) "Less" else "More", color = C.Ember, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                if (d != null) {
                    if (d.creator.isNotBlank()) InfoRow("Created by", d.creator)
                    if (d.director.isNotBlank()) InfoRow("Director", d.director)
                    if (d.country.isNotBlank()) InfoRow("Country", d.country)
                }
            }

            val cast = d?.cast.orEmpty()
            if (cast.isNotEmpty()) {
                Text(
                    "CAST", color = C.Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 10.dp),
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(cast.size) { i -> CastCard(cast[i]) }
                }
            }
            if ((d?.tmdbId ?: 0) > 0) {
                Text(
                    "Facts and images: TMDB", color = C.Faint, fontSize = 10.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun FactChip(text: String, gold: Boolean = false) {
    Text(
        text, color = if (gold) C.Gold else C.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (gold) Color(0x26FFD27A) else Color(0x1FFFFFFF))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun CastCard(c: CastMember) {
    Column(Modifier.width(78.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(66.dp).clip(CircleShape).background(C.Surface2), contentAlignment = Alignment.Center) {
            Text(c.name.trim().firstOrNull()?.uppercase() ?: "?", color = C.Muted, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (c.photo.isNotBlank()) AsyncImage(model = c.photo, contentDescription = c.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(c.name, color = C.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        if (c.role.isNotBlank()) Text(c.role, color = C.Faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, color = C.Faint, fontSize = 13.sp, modifier = Modifier.width(92.dp))
        Text(value, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun HeroButton(
    label: String,
    icon: ImageVector,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // On TV the remote starts on Play.
    val tv = LocalTv.current
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (primary && tv) LaunchedEffect(Unit) { kotlinx.coroutines.delay(200); runCatching { focus.requestFocus() } }
    Row(
        modifier.height(48.dp).focusRequester(focus).tvFocus(RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
            .background(if (primary) C.Ember else Color(0x1FFFFFFF))
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

fun openTrailer(context: android.content.Context, key: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun withoutYear(s: String) = s.replace(Regex("""\s*[(\[]\s*\d{4}\s*[)\]]\s*"""), " ").trim().lowercase()

private fun runtimeText(min: Int): String? = when {
    min <= 0 -> null
    min < 60 -> "${min}m"
    else -> "${min / 60}h ${min % 60}m"
}

internal fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(java.util.Locale.US, n / 1_000_000f)
    n >= 1_000 -> "%.1fK".format(java.util.Locale.US, n / 1_000f)
    else -> n.toString()
}
