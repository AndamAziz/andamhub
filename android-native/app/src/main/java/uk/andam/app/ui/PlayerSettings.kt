package uk.andam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.andam.app.player.PlayerPrefs

/** Account → Player settings: background audio, floating window, languages, subtitle size. */
@Composable
fun PlayerSettingsCard() {
    val c = LocalContext.current
    val tv = LocalTv.current
    var bg by remember { mutableStateOf(PlayerPrefs.backgroundAudio(c)) }
    var pip by remember { mutableStateOf(PlayerPrefs.pip(c)) }
    var aLang by remember { mutableStateOf(PlayerPrefs.audioLang(c)) }
    var sLang by remember { mutableStateOf(PlayerPrefs.subLang(c)) }
    var sSize by remember { mutableIntStateOf(PlayerPrefs.subSize(c)) }

    Column(
        Modifier.fillMaxWidth().background(C.Surface, RoundedCornerShape(18.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Player settings", color = C.Faint, style = MaterialTheme.typography.labelMedium)

        SwitchRow("Background audio", "Sound keeps playing with the screen locked or another app open", bg) {
            bg = it; PlayerPrefs.setBackgroundAudio(c, it)
        }
        if (!tv) SwitchRow("Picture-in-picture", "Leaving the app shrinks the video to a small floating window", pip) {
            pip = it; PlayerPrefs.setPip(c, it)
        }

        ChoiceRow("Preferred audio language", "Used automatically when a channel or film has several", PlayerPrefs.languages, aLang) {
            aLang = it; PlayerPrefs.setAudioLang(c, it)
        }
        ChoiceRow(
            "Subtitles", "Shown automatically in this language when available",
            listOf("" to "Off") + PlayerPrefs.languages.drop(1), sLang,
        ) { sLang = it; PlayerPrefs.setSubLang(c, it) }
        ChoiceRow(
            "Subtitle size", null,
            listOf("0" to "Small", "1" to "Normal", "2" to "Large", "3" to "Extra large"), sSize.toString(),
        ) { sSize = it.toInt(); PlayerPrefs.setSubSize(c, sSize) }
        Text("Changes apply to the next channel or film you open.", color = C.Faint, fontSize = 12.sp)
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocus(RoundedCornerShape(12.dp), 1.02f)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!on) }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(sub, color = C.Muted, fontSize = 12.sp)
        }
        Switch(
            checked = on, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = C.Ember, checkedThumbColor = androidx.compose.ui.graphics.Color.White),
        )
    }
}

@Composable
private fun ChoiceRow(title: String, sub: String?, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 4.dp))
        if (sub != null) Text(sub, color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options, key = { it.first }) { (id, label) ->
                Chip(label, id == selected) { onPick(id) }
            }
        }
    }
}
