package uk.andam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import uk.andam.app.net.Api
import uk.andam.app.net.Category

@Composable
fun Busy(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = C.Ember, strokeWidth = 3.dp)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = C.Muted, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) { Text("Try again", color = C.Ember) }
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(placeholder, color = C.Faint) },
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = C.Faint) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, "Clear", tint = C.Muted) }
        },
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {}),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = C.Surface, unfocusedContainerColor = C.Surface,
            focusedBorderColor = C.Ember.copy(alpha = 0.6f), unfocusedBorderColor = C.Hair,
            cursorColor = C.Ember,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) C.Bg else C.Muted,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) C.Text else C.Surface)
            .border(1.dp, if (selected) C.Text else C.Hair, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

/**
 * Category bar: a scrollable chip row plus a button that opens the full list.
 * Selection is applied instantly by the caller (filtering happens on the device).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryBar(categories: List<Category>, selected: String, onSelect: (String) -> Unit, allLabel: String = "All") {
    var sheet by remember { mutableStateOf(false) }
    val rowState = rememberLazyListState()
    val all = remember(categories) { listOf(Category("", allLabel)) + categories }
    LaunchedEffect(selected, all) {
        val i = all.indexOfFirst { it.id == selected }
        if (i > 0) rowState.animateScrollToItem(i)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            state = rowState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(all, key = { i, c -> "$i:${c.id}" }) { _, c ->
                Chip(c.name, c.id == selected) { onSelect(c.id) }
            }
        }
        if (categories.size > 4) {
            IconButton(onClick = { sheet = true }, modifier = Modifier.padding(end = 8.dp)) {
                Icon(Icons.Filled.Tune, "All categories", tint = C.Text)
            }
        }
    }
    if (sheet) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        var q by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = state, containerColor = C.Surface) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Categories", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 10.dp))
                if (all.size > 12) SearchField(q, { q = it }, "Search categories", Modifier.padding(bottom = 8.dp))
                val shown = all.filter { q.isBlank() || it.name.contains(q, ignoreCase = true) }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().height(460.dp)) {
                    itemsIndexed(shown, key = { i, c -> "$i:${c.id}" }) { _, c ->
                        val on = c.id == selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (on) C.EmberDim else Color.Transparent)
                                .clickable {
                                    onSelect(c.id)
                                    scope.launch { state.hide() }.invokeOnCompletion { sheet = false }
                                }
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.name, color = if (on) Color.White else C.Text, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.count > 0) Text("${c.count}", color = C.Faint, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun ChannelRow(num: Int, name: String, logo: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (num > 0) "$num" else "", color = C.Faint, fontSize = 12.sp, modifier = Modifier.width(34.dp))
        Logo(logo, name, 44)
        Text(
            name, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp).weight(1f),
        )
    }
}

@Composable
fun Logo(url: String, name: String, sizeDp: Int) {
    Box(
        Modifier.size(sizeDp.dp).clip(RoundedCornerShape(10.dp)).background(C.Surface2),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().take(1).uppercase(), color = C.Muted, fontWeight = FontWeight.Bold, fontSize = (sizeDp / 2.6).sp)
        if (url.isNotBlank()) {
            AsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().background(C.Surface2).padding(4.dp),
            )
        }
    }
}

@Composable
fun PosterCard(title: String, poster: String, sub: String, rating: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(C.Surface2),
            contentAlignment = Alignment.Center,
        ) {
            Text(title.take(1).uppercase(), color = C.Faint, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            if (poster.isNotBlank()) {
                AsyncImage(model = poster, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            val r = rating.toFloatOrNull()
            if (r != null && r > 0f) {
                Text(
                    "★ %.1f".format(if (r > 10f) r / 10f else r), color = C.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xC70A0B0F)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(title, color = C.Text, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
        if (sub.isNotBlank()) Text(sub, color = C.Faint, fontSize = 10.sp, maxLines = 1)
    }
}

/** Provider / playlist switcher shown in the top bar. */
@Composable
fun SourcePicker(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    if (options.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(C.Surface)
                .border(1.dp, C.Hair, RoundedCornerShape(50))
                .clickable(enabled = options.size > 1) { open = true }
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(options.firstOrNull { it.first == selected }?.second ?: options.first().second, color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(IntrinsicMax(options)))
            if (options.size > 1) Icon(Icons.Filled.ExpandMore, null, tint = C.Muted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = C.Surface2) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name, fontWeight = if (id == selected) FontWeight.Bold else FontWeight.Normal) }, onClick = { open = false; onPick(id) })
            }
        }
    }
}

private fun IntrinsicMax(options: List<Pair<String, String>>) =
    (options.maxOfOrNull { it.second.length } ?: 8).coerceIn(4, 14).times(8).dp

/** Activation-code gate for Live TV / Movies / Series. */
@Composable
fun LockCard(signedIn: Boolean, onUnlocked: () -> Unit, onSignIn: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(C.EmberDim),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Lock, null, tint = C.Ember, modifier = Modifier.size(30.dp)) }
        Spacer(Modifier.height(16.dp))
        Text("This section is locked", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Text(
            if (signedIn) "Enter an activation code to unlock Live TV, Movies and Series." else "Sign in first, then enter your activation code.",
            color = C.Muted, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        if (!signedIn) {
            Button(onClick = onSignIn, colors = ButtonDefaults.buttonColors(containerColor = C.Ember), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("Sign in")
            }
            return@Column
        }
        OutlinedTextField(
            value = code, onValueChange = { code = it.uppercase() }, singleLine = true,
            placeholder = { Text("ABCD-EFGH-JKLM", color = C.Faint) },
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = C.Surface, unfocusedContainerColor = C.Surface, focusedBorderColor = C.Ember, unfocusedBorderColor = C.Hair),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                busy = true; note = null
                scope.launch {
                    note = try {
                        val msg = Api.redeem(code)
                        onUnlocked()
                        msg to true
                    } catch (e: Exception) {
                        (e.message ?: "That code is not valid.") to false
                    }
                    busy = false
                }
            },
            enabled = !busy && code.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = C.Ember),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text(if (busy) "Checking…" else "Activate") }
        note?.let { (msg, ok) -> Text(msg, color = if (ok) Color(0xFF38E1C6) else C.Ember, modifier = Modifier.padding(top = 10.dp)) }
    }
}

@Composable
fun SectionTitle(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = androidx.compose.material3.MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action, color = C.Muted) }
    }
}

@Composable
fun BrandMark(size: Int = 32) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.28f).dp))
            .background(Brush.linearGradient(listOf(Color(0xFFFF5A6A), C.Ember, Color(0xFFB81D31)))),
        contentAlignment = Alignment.Center,
    ) {
        Text("A", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (size * 0.5f).sp)
    }
}

@Composable
fun Dot(color: Color) {
    Box(Modifier.size(6.dp).clip(CircleShape).background(color))
}
