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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import uk.andam.app.player.Resume

/*
 * Detail pages for films and series: backdrop, the poster with all the information beside it
 * (title, year, length, age rating, TMDB score, genres), Play / Resume and Trailer, the story,
 * director, cast with photos, and which subtitles are available.
 */

/** Film detail page; Play starts the film (with online subtitles available in the player). */
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
    var resume by remember(vod.id) { mutableStateOf(0L) }
    LaunchedEffect(vod.id) { resume = Resume.get(context, item.resumeKey) }

    LazyColumn(Modifier.fillMaxSize().background(C.Bg), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            MediaHero(
                details = details,
                fallbackTitle = vod.name,
                fallbackPoster = vod.poster,
                fallbackMeta = listOf(vod.year, vod.genre.substringBefore(',')).filter { it.isNotBlank() }.joinToString(" · "),
                onBack = onBack,
                subs = subs,
            ) {
                HeroButton(
                    if (resume > 0) "Resume ${clock(resume)}" else "Play",
                    Icons.Filled.PlayArrow, primary = true, modifier = Modifier.weight(1f),
                ) { PlayQueue.open(context, listOf(item), 0) }
                details?.trailer?.takeIf { it.isNotBlank() }?.let { key ->
                    HeroButton("Trailer", Icons.Filled.Theaters, primary = false) { openTrailer(context, key) }
                }
            }
        }
    }
}

/**
 * The top of a detail page. [actions] are the buttons under the information (Play, Trailer…).
 * Used by the film page and at the top of the series page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaHero(
    details: MediaDetails?,
    fallbackTitle: String,
    fallbackPoster: String,
    fallbackMeta: String,
    onBack: () -> Unit,
    subs: OnlineSubs? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val d = details
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 700.dp
        val backdropH = if (wide) 340.dp else 250.dp
        val posterW = if (wide) 170.dp else 118.dp
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()) {
                // Backdrop fading into the page.
                Box(Modifier.fillMaxWidth().height(backdropH)) {
                    AsyncImage(
                        model = d?.backdrop?.ifBlank { null } ?: d?.poster?.ifBlank { null } ?: fallbackPoster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = 0.85f,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(C.Bg.copy(alpha = 0.35f), Color.Transparent, C.Bg.copy(alpha = 0.75f), C.Bg)),
                        ),
                    )
                    if (wide) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(C.Bg.copy(alpha = 0.8f), Color.Transparent))))
                    Box(
                        Modifier.statusBarsPadding().padding(10.dp).size(42.dp).tvFocus(CircleShape).clip(CircleShape)
                            .background(Color(0x99000000)).clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                }
                // Poster with the information beside it, overlapping the bottom of the backdrop.
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
                    Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            d?.title?.ifBlank { null } ?: fallbackTitle,
                            color = C.Text, fontSize = if (wide) 28.sp else 21.sp, fontWeight = FontWeight.Bold,
                            lineHeight = if (wide) 32.sp else 25.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                        val original = d?.originalTitle.orEmpty()
                        if (original.isNotBlank() && !original.equals(d?.title, true)) {
                            Text(original, color = C.Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val meta = if (d == null) fallbackMeta else listOfNotNull(
                            d.year.ifBlank { null },
                            runtimeText(d.runtime),
                            if (d.seasons > 0) "${d.seasons} season${if (d.seasons > 1) "s" else ""}" else null,
                        ).joinToString("  ·  ")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (meta.isNotBlank()) Text(meta, color = C.Muted, fontSize = 13.sp)
                            d?.certification?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it, color = C.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 8.dp).border(1.dp, C.Faint, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                        if (d != null && d.rating > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Star, null, tint = C.Gold, modifier = Modifier.size(18.dp))
                                Text(
                                    "%.1f".format(java.util.Locale.US, d.rating), color = C.Text, fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp),
                                )
                                Text(
                                    if (d.votes > 0) " / 10 · ${compact(d.votes)} votes" else " / 10",
                                    color = C.Faint, fontSize = 12.sp,
                                )
                            }
                        }
                        val genres = d?.genres.orEmpty()
                        if (genres.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                genres.take(4).forEach { g ->
                                    Text(
                                        g, color = C.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x1FFFFFFF))
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
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

                d?.tagline?.takeIf { it.isNotBlank() }?.let {
                    Text("“$it”", color = C.Muted, fontSize = 14.sp, fontStyle = FontStyle.Italic)
                }
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
                if (d != null && d.director.isNotBlank()) InfoRow(if (d.seasons > 0) "Created by" else "Director", d.director)

                if (subs != null && (subs.subs.isNotEmpty() || subs.kurdishUrl != null)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Subtitles, null, tint = C.Muted, modifier = Modifier.size(18.dp))
                        Text(
                            (subs.subs.map { it.label } + listOfNotNull(if (subs.kurdishUrl != null) "Kurdish (auto)" else null))
                                .distinct().joinToString(" · "),
                            color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp),
                        )
                    }
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
        }
    }
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

private fun runtimeText(min: Int): String? = when {
    min <= 0 -> null
    min < 60 -> "${min}m"
    else -> "${min / 60}h ${min % 60}m"
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(java.util.Locale.US, n / 1_000_000f)
    n >= 1_000 -> "%.1fK".format(java.util.Locale.US, n / 1_000f)
    else -> n.toString()
}
