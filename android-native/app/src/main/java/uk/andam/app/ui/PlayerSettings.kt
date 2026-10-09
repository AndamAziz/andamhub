package uk.andam.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import uk.andam.app.player.PlayerPrefs

/** Account → Playback: background audio, floating window, languages, subtitle size. */
@Composable
fun PlaybackSettings() {
    val c = LocalContext.current
    val tv = LocalTv.current
    var bg by remember { mutableStateOf(PlayerPrefs.backgroundAudio(c)) }
    var pip by remember { mutableStateOf(PlayerPrefs.pip(c)) }
    var aLang by remember { mutableStateOf(PlayerPrefs.audioLang(c)) }
    var sLang by remember { mutableStateOf(PlayerPrefs.subLang(c)) }
    var sSize by remember { mutableIntStateOf(PlayerPrefs.subSize(c)) }
    var kEngine by remember { mutableStateOf(PlayerPrefs.kurdishEngine(c)) }

    SettingsGroup("Playback", footer = "Changes apply to the next channel, film or episode you open.") {
        SettingsSwitch(Icons.Filled.Headphones, Violet, "Background audio", "Keep listening with the screen locked", bg) {
            bg = it; PlayerPrefs.setBackgroundAudio(c, it)
        }
        if (!tv) SettingsSwitch(Icons.Filled.PictureInPictureAlt, Sky, "Picture-in-picture", "Small floating video when you leave the app", pip) {
            pip = it; PlayerPrefs.setPip(c, it)
        }
        SettingsPicker(Icons.Filled.Translate, Amber, "Audio language", PlayerPrefs.languages, aLang) {
            aLang = it; PlayerPrefs.setAudioLang(c, it)
        }
        SettingsPicker(Icons.Filled.Subtitles, Teal, "Subtitles", listOf("" to "Off") + PlayerPrefs.languages.drop(1), sLang) {
            sLang = it; PlayerPrefs.setSubLang(c, it)
        }
        SettingsPicker(
            Icons.Filled.FormatSize, C.Ember, "Subtitle size",
            listOf("0" to "Small", "1" to "Normal", "2" to "Large", "3" to "Extra large"), sSize.toString(),
        ) { sSize = it.toInt(); PlayerPrefs.setSubSize(c, sSize) }
    }

    SettingsGroup(
        "Subtitles",
        footer = "Kurdish (Sorani) subtitles are machine-translated from the English (or Arabic) subtitle. " +
            "Google is free and runs on this device; Claude runs on the Andam server when it is set up there, " +
            "otherwise Google is used. A title is translated once and then shared with every viewer.",
    ) {
        SettingsPicker(Icons.Filled.Translate, Teal, "Kurdish subtitles made by", PlayerPrefs.kurdishEngines, kEngine) {
            kEngine = it; PlayerPrefs.setKurdishEngine(c, it)
        }
    }
}
