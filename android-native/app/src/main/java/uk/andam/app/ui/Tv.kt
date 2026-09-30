package uk.andam.app.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.andam.app.net.Category

/** True on Android TV / Google TV, Fire TV and TV boxes (no touchscreen). */
fun isTvDevice(context: Context): Boolean {
    val pm = context.packageManager
    val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        pm.hasSystemFeature("android.software.leanback") ||
        pm.hasSystemFeature("amazon.hardware.fire_tv") ||
        !pm.hasSystemFeature("android.hardware.touchscreen")
}

/** Whether the app is running with the TV (remote control) layout. */
val LocalTv = staticCompositionLocalOf { false }

/**
 * Visible focus for remote-control navigation: a bright ring and a small zoom on the item the
 * D-pad is on. Place it before `clickable`. On phones nothing changes (touch never focuses).
 */
fun Modifier.tvFocus(shape: Shape = RoundedCornerShape(12.dp), zoom: Float = 1.05f): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .graphicsLayer {
            val s = if (focused) zoom else 1f
            scaleX = s; scaleY = s
        }
        .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
}

/**
 * TV search: a button instead of a text field, so moving over it with the remote does not pop
 * the on-screen keyboard. OK opens a small dialog with the keyboard.
 */
@Composable
fun TvSearchButton(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .tvFocus(RoundedCornerShape(14.dp), 1.02f)
            .clip(RoundedCornerShape(14.dp))
            .background(C.Surface)
            .clickable { open = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = C.Faint, modifier = Modifier.size(20.dp))
        Text(
            value.ifBlank { placeholder },
            color = if (value.isBlank()) C.Faint else C.Text,
            fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
        )
        if (value.isNotBlank()) Text("Clear", color = C.Ember, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { onChange("") }.padding(start = 8.dp))
    }
    if (open) {
        var text by remember { mutableStateOf(value) }
        val fr = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = C.Surface,
            title = { Text(placeholder) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onChange(text); open = false }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = C.Surface2, unfocusedContainerColor = C.Surface2,
                        focusedBorderColor = C.Ember, unfocusedBorderColor = C.Hair, cursorColor = C.Ember,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(fr),
                )
            },
            confirmButton = { TextButton(onClick = { onChange(text); open = false }) { Text("Search", color = C.Ember) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel", color = C.Muted) } },
        )
    }
}

/** Left-hand category list used on TV instead of the phone's category button + sheet. */
@Composable
fun TvCategoryPane(categories: List<Category>, selected: String, onSelect: (String) -> Unit, allLabel: String = "All") {
    val all = remember(categories) { listOf(Category("", allLabel)) + categories }
    val state = rememberLazyListState()
    LaunchedEffect(all) {
        val i = all.indexOfFirst { it.id == selected }
        if (i > 2) state.scrollToItem(i - 2)
    }
    Column(Modifier.width(250.dp).fillMaxHeight().background(C.Surface).padding(vertical = 10.dp)) {
        Text(
            "CATEGORIES", color = C.Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 8.dp),
        )
        LazyColumn(state = state, modifier = Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(all, key = { i, c -> "$i:${c.id}" }) { _, c ->
                val on = c.id == selected
                Row(
                    Modifier
                        .padding(horizontal = 10.dp)
                        .fillMaxWidth()
                        .tvFocus(RoundedCornerShape(12.dp), 1.03f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) C.EmberDim else Color.Transparent)
                        .clickable { onSelect(c.id) }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        c.name, color = if (on) Color.White else C.Text, fontSize = 14.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (c.count > 0) Text("${c.count}", color = C.Faint, fontSize = 11.sp)
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
