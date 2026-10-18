package uk.andam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.andam.app.net.Category

/**
 * "All categories" as the provider arranges it: one section per category, in the provider's
 * order. Each section is a header (name, size, "See all") and ONE full row of the grid — 3 on
 * phones, more on tablets and TV — so every section lines up the same. "See all" opens the
 * category with everything in it. No horizontal scrolling anywhere.
 * Used by Live TV, IPTV, Movies and Series on phones, tablets and TV.
 */
class CategorySection(val category: Category, val indices: List<Int>)

/** Indices of [count] items grouped by category, in category order; unknown categories last. */
fun groupByCategory(categories: List<Category>, count: Int, categoryOf: (Int) -> String): List<CategorySection> {
    val byCat = LinkedHashMap<String, MutableList<Int>>()
    for (i in 0 until count) byCat.getOrPut(categoryOf(i)) { mutableListOf() }.add(i)
    val out = ArrayList<CategorySection>()
    for (c in categories) byCat.remove(c.id)?.let { if (it.isNotEmpty()) out.add(CategorySection(c, it)) }
    val rest = byCat.values.flatten()
    if (rest.isNotEmpty()) out.add(CategorySection(Category("", "Other"), rest))
    return out
}

/** Grid columns for the section rows: 3 on phones, more on tablets / TV. */
fun sectionColumns(width: Dp, tv: Boolean): Int = when {
    width < 600.dp -> 3
    tv -> ((width.value - 32f) / 150f).toInt().coerceIn(4, 8)
    else -> ((width.value - 32f) / 130f).toInt().coerceIn(4, 7)
}

@Composable
fun SectionHeader(title: String, count: Int, onSeeAll: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(C.Ember))
            Text(
                title, color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp).weight(1f, fill = false),
            )
            Text(
                "$count", color = C.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(C.Surface2).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (onSeeAll != null) {
            Row(
                Modifier
                    .padding(start = 8.dp)
                    .tvFocus(RoundedCornerShape(50), 1.04f)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onSeeAll)
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("See all", color = C.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** A channel as a card (logo above its name), sized by its grid cell. */
@Composable
fun ChannelTile(name: String, logo: String, onClick: () -> Unit) {
    val tv = LocalTv.current
    Column(
        Modifier
            .fillMaxWidth()
            .height(if (tv) 132.dp else 116.dp)
            .tvFocus(RoundedCornerShape(14.dp), 1.06f)
            .clip(RoundedCornerShape(14.dp))
            .background(C.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Logo(logo, name, if (tv) 64 else 52)
        Text(
            name, color = C.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, lineHeight = 15.sp, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * The sections as one grid: per section a full-width header and one row of [cell]s
 * (`cell(section, k)` draws the k-th item of that section).
 */
@Composable
fun SectionGrid(
    sections: List<CategorySection>,
    onSeeAll: (Category) -> Unit,
    cell: @Composable (CategorySection, Int) -> Unit,
) {
    val tv = LocalTv.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cols = sectionColumns(maxWidth, tv)
        val gap = if (tv) 16.dp else 10.dp
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier.fillMaxSize(),
        ) {
            sections.forEach { sec ->
                val canOpen = sec.category.id.isNotEmpty()
                item(key = "h:" + sec.category.id + ":" + sec.category.name, span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(sec.category.name, sec.indices.size, if (canOpen) ({ onSeeAll(sec.category) }) else null)
                }
                val n = if (canOpen) minOf(cols, sec.indices.size) else sec.indices.size
                items(n, key = { "i:" + sec.category.id + ":" + sec.indices[it] }) { k -> cell(sec, k) }
            }
        }
    }
}
