package uk.andam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.andam.app.net.Category

/**
 * "All categories" as the provider arranges it: one section per category, in the provider's
 * order, each a horizontal row with its name, its size and "See all" (which opens that category).
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

/** Items shown per row before the "See all" card; the category itself shows everything. */
const val SECTION_ROW_MAX = 30

@Composable
fun SectionHeader(title: String, count: Int, onSeeAll: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        Spacer(Modifier.weight(1f))
        if (onSeeAll != null) {
            Row(
                Modifier
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

/** The last card of a row: opens the whole category. */
@Composable
fun SeeAllCard(count: Int, width: Int, height: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .width(width.dp)
            .height(height.dp)
            .tvFocus(RoundedCornerShape(14.dp), 1.06f)
            .clip(RoundedCornerShape(14.dp))
            .background(C.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(C.EmberDim), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.Text)
            }
            Text("See all", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Text("$count", color = C.Faint, fontSize = 11.sp)
        }
    }
}

/**
 * The sections as a vertical list of horizontal rows. [card] draws item `index` (an index into
 * the caller's full list); [cardWidth] / [cardHeight] size the "See all" card to match.
 */
@Composable
fun CategorySections(
    sections: List<CategorySection>,
    header: (@Composable () -> Unit)? = null,
    cardWidth: Int,
    cardHeight: Int,
    onSeeAll: (Category) -> Unit,
    card: @Composable (index: Int) -> Unit,
) {
    LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (header != null) item(key = "header") { header() }
        items(sections, key = { "sec:" + it.category.id + ":" + it.category.name }) { sec ->
            val canOpen = sec.category.id.isNotEmpty()
            SectionHeader(sec.category.name, sec.indices.size, if (canOpen) ({ onSeeAll(sec.category) }) else null)
            val shown = if (canOpen) sec.indices.take(SECTION_ROW_MAX) else sec.indices
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                items(shown, key = { it }) { i -> card(i) }
                if (canOpen && sec.indices.size > shown.size) {
                    item(key = "all") { SeeAllCard(sec.indices.size, cardWidth, cardHeight) { onSeeAll(sec.category) } }
                }
            }
        }
    }
}
