package uk.andam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
 * order — a header (name, size, "See all"), a short list / poster grid and a "See all" button.
 * Used by Live TV, IPTV, Movies and Series on phones, tablets and TV (no horizontal rows).
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

@Composable
fun SectionHeader(title: String, count: Int, onSeeAll: (() -> Unit)?, start: Int = 16, end: Int = 8) {
    Row(
        Modifier.fillMaxWidth().padding(start = start.dp, end = end.dp, top = 18.dp, bottom = 8.dp),
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

/** Per section, how many items show before "See all" (a list / grid like the other pages). */
const val SECTION_PHONE_CHANNELS = 8
const val SECTION_PHONE_POSTERS = 12

/** Full-width "See all" button closing a section on phones. */
@Composable
fun SeeAllRow(count: Int, horizontal: Int = 12, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontal.dp, vertical = 6.dp)
            .tvFocus(RoundedCornerShape(12.dp), 1.02f)
            .clip(RoundedCornerShape(12.dp))
            .background(C.Surface)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("See all", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("$count", color = C.Faint, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        Box(Modifier.padding(start = 8.dp).size(24.dp).clip(RoundedCornerShape(50)).background(C.EmberDim), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.Text, modifier = Modifier.size(18.dp))
        }
    }
}
