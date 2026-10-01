package uk.andam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Building blocks for the Account / settings screen: grouped sections with one row per setting,
 * like the system Settings app. Every row is a single focus target with a visible highlight, so
 * the same screen works with touch and with a TV remote.
 */

val Teal = Color(0xFF38E1C6)
val Sky = Color(0xFF5AA9FF)
val Amber = Color(0xFFFFB547)
val Violet = Color(0xFFA78BFA)

/** A titled group of rows on one rounded surface, with hairline dividers between rows. */
@Composable
fun SettingsGroup(title: String?, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (title != null) Text(
            title.uppercase(), color = C.Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
        )
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Hair),
            verticalArrangement = Arrangement.spacedBy(1.dp),
            content = content,
        )
        if (footer != null) Text(
            footer, color = C.Faint, fontSize = 12.sp, lineHeight = 16.sp,
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp),
        )
    }
}

/** Plain block inside a group (results, progress…), same surface as a row. */
@Composable
fun SettingsBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(C.Surface).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun IconTile(icon: ImageVector, tint: Color, size: Int = 34) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3f).dp)).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.56f).dp)) }
}

/**
 * One settings row: icon, title, optional subtitle, optional value on the right, then [trailing]
 * (a switch, a status pill…) or a chevron when the row opens something.
 */
@Composable
fun SettingsRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    titleColor: Color = C.Text,
    chevron: Boolean = true,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) C.Surface3 else C.Surface)
            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(4.dp)) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint)
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(title, color = titleColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = C.Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) Text(
            value, color = C.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp),
        )
        when {
            trailing != null -> { Spacer(Modifier.width(6.dp)); trailing() }
            onClick != null && chevron -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.Faint, modifier = Modifier.size(22.dp))
        }
    }
}

/** On/off setting; the whole row toggles (one focus stop on TV). */
@Composable
fun SettingsSwitch(icon: ImageVector, tint: Color, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingsRow(
        icon, tint, title, subtitle,
        trailing = {
            Switch(
                checked = checked, onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = C.Ember, checkedThumbColor = Color.White,
                    uncheckedTrackColor = C.Surface3, uncheckedThumbColor = C.Muted, uncheckedBorderColor = Color.Transparent,
                ),
                modifier = Modifier.scale(0.85f),
            )
        },
        onClick = { onChange(!checked) },
    )
}

/** Shows the current choice; tapping opens a list to pick from (never cut off at the screen edge). */
@Composable
fun SettingsPicker(
    icon: ImageVector,
    tint: Color,
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    subtitle: String? = null,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selected }?.second ?: options.firstOrNull()?.second.orEmpty()
    SettingsRow(icon, tint, title, subtitle, value = label, onClick = { open = true })
    if (open) OptionDialog(title, options, selected, onDismiss = { open = false }) { onPick(it); open = false }
}

@Composable
fun OptionDialog(title: String, options: List<Pair<String, String>>, selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { (id, name) ->
                    val on = id == selected
                    var focused by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (on) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged { focused = it.isFocused }
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    focused -> C.Surface3
                                    on -> C.EmberDim
                                    else -> Color.Transparent
                                },
                            )
                            .then(if (focused) Modifier.border(2.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier)
                            .clickable { onPick(id) }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            name, color = if (on) C.Text else C.Muted, fontSize = 15.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f),
                        )
                        if (on) Icon(Icons.Filled.Check, null, tint = C.Ember, modifier = Modifier.size(20.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = C.Muted) } },
    )
}

/** Small rounded label used on the right of a row (status or a call to action). */
@Composable
fun SettingsPill(text: String, color: Color, filled: Boolean = false) {
    Text(
        text,
        color = if (filled) Color.White else color, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier
            .background(if (filled) color else color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
