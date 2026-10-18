package uk.andam.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import uk.andam.app.net.Category
import uk.andam.app.player.PlayItem
import uk.andam.app.player.PlayQueue

/**
 * Shared Live TV / IPTV browser. The whole channel list is loaded once and filtered on the
 * device, so picking a category shows exactly that category's channels immediately.
 */
@Composable
fun ChannelBrowser(key: String, categories: List<Category>, list: List<PlayItem>, nums: List<Int>) {
    val context = LocalContext.current
    var cat by rememberSaveable(key) { mutableStateOf("") }
    var q by rememberSaveable(key) { mutableStateOf("") }
    val listState = rememberLazyListState()

    val shown = remember(list, cat, q) {
        list.indices.filter { i ->
            (cat.isEmpty() || list[i].group == cat) && (q.isBlank() || list[i].title.contains(q, ignoreCase = true))
        }
    }
    LaunchedEffect(cat, q) { listState.scrollToItem(0) }

    val tv = LocalTv.current
    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
    if (tv && categories.isNotEmpty()) {
        TvCategoryPane(categories, cat, onSelect = { cat = it })
        Spacer(Modifier.width(16.dp))
    }
    Column(Modifier.weight(1f).fillMaxHeight()) {
        if (tv) {
            TvSearchButton(q, { q = it }, "Search channels", Modifier.padding(bottom = 10.dp))
            CountLine("${shown.size} channels")
            Spacer(Modifier.height(6.dp))
        } else {
            SearchField(q, { q = it }, "Search channels", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (categories.isNotEmpty()) CategoryBar(categories, cat, onSelect = { cat = it })
            Spacer(Modifier.height(6.dp))
        }
        val grouped = cat.isEmpty() && q.isBlank() && categories.isNotEmpty()
        val sections = remember(list, categories) { groupByCategory(categories, list.size) { list[it].group } }
        if (shown.isEmpty()) {
            ErrorBox(if (list.isEmpty()) "No channels yet." else "No channels match.")
        } else if (grouped && sections.size > 1) {
            // All categories: one row per provider category, in the provider's order.
            CategorySections(
                sections,
                cardWidth = if (tv) 132 else 112,
                cardHeight = 128,
                onSeeAll = { cat = it.id },
            ) { i ->
                ChannelTile(list[i].title, list[i].logo, if (tv) 132 else 112) {
                    // The player gets the full list, so its panel can switch categories too.
                    PlayQueue.open(context, list, i, categories)
                }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(shown, key = { list[it].id + "#" + it }) { i ->
                    val ch = list[i]
                    ChannelRow(nums.getOrElse(i) { i + 1 }, ch.title, ch.logo) {
                        // The player gets the full list, so its panel can switch categories too.
                        PlayQueue.open(context, list, i, categories)
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
    }
}

/** A channel as a tile (logo above its name), for the category rows. */
@Composable
fun ChannelTile(name: String, logo: String, widthDp: Int, onClick: () -> Unit) {
    Column(
        Modifier
            .width(widthDp.dp)
            .height(128.dp)
            .tvFocus(androidx.compose.foundation.shape.RoundedCornerShape(16.dp), 1.07f)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(C.Surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Logo(logo, name, 64)
        Text(
            name, color = C.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, minLines = 2, lineHeight = 15.sp, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
fun CountLine(text: String) {
    Text(text, color = C.Faint, modifier = Modifier.padding(horizontal = 16.dp))
}
