package uk.andam.app.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters

/** Viewer's player preferences (Account → Player settings, and the in-player Settings panel). */
object PlayerPrefs {
    private fun p(c: Context) = c.getSharedPreferences("andam_player", Context.MODE_PRIVATE)

    /** Keep the sound playing with the screen off / app in the background. */
    fun backgroundAudio(c: Context) = p(c).getBoolean("bg", true)
    fun setBackgroundAudio(c: Context, on: Boolean) = p(c).edit().putBoolean("bg", on).apply()

    /** Shrink the video to a floating window when leaving the app (phones/tablets). */
    fun pip(c: Context) = p(c).getBoolean("pip", true)
    fun setPip(c: Context, on: Boolean) = p(c).edit().putBoolean("pip", on).apply()

    /** Preferred audio language code ("" = the stream's default). */
    fun audioLang(c: Context) = p(c).getString("alang", "").orEmpty()
    fun setAudioLang(c: Context, v: String) = p(c).edit().putString("alang", v).apply()

    /** Preferred subtitle language code ("" = subtitles off unless chosen). */
    fun subLang(c: Context) = p(c).getString("slang", "").orEmpty()
    fun setSubLang(c: Context, v: String) = p(c).edit().putString("slang", v).apply()

    /**
     * Engine for Kurdish (Sorani) subtitles: "g" free Google Translate on this device (default),
     * "claude-best" / "claude-fast" Claude on the Andam server, "ai" Gemini on the server.
     * Server engines are used only when the server offers them; otherwise Google.
     */
    fun kurdishEngine(c: Context) = p(c).getString("kueng", "g").orEmpty().ifBlank { "g" }
    fun setKurdishEngine(c: Context, v: String) = p(c).edit().putString("kueng", v).apply()

    val kurdishEngines = listOf(
        "g" to "Google (free)",
        "claude-best" to "Claude · best",
        "claude-fast" to "Claude · fast",
        "ai" to "Gemini AI",
    )

    /** Subtitle size: 0 small, 1 normal, 2 large, 3 extra large. */
    fun subSize(c: Context) = p(c).getInt("ssize", 1)
    fun setSubSize(c: Context, v: Int) = p(c).edit().putInt("ssize", v).apply()

    /** Language choices offered in the app; Kurdish covers Sorani and Kurmanji codes. */
    val languages = listOf(
        "" to "Auto",
        "ku" to "Kurdish",
        "ar" to "Arabic",
        "en" to "English",
        "fa" to "Persian",
        "tr" to "Turkish",
        "fr" to "French",
        "de" to "German",
        "es" to "Spanish",
    )

    private fun codes(lang: String): Array<String> = when (lang) {
        "ku" -> arrayOf("ckb", "ku", "kur", "kmr", "sdh")
        "fa" -> arrayOf("fa", "per", "fas")
        "ar" -> arrayOf("ar", "ara")
        "en" -> arrayOf("en", "eng")
        "tr" -> arrayOf("tr", "tur")
        "fr" -> arrayOf("fr", "fre", "fra")
        "de" -> arrayOf("de", "ger", "deu")
        "es" -> arrayOf("es", "spa")
        else -> arrayOf(lang)
    }

    /** Applies the language preferences to the player's automatic track choice. */
    fun apply(c: Context, params: TrackSelectionParameters): TrackSelectionParameters {
        val b = params.buildUpon()
        val a = audioLang(c)
        if (a.isNotBlank()) b.setPreferredAudioLanguages(*codes(a))
        val s = subLang(c)
        if (s.isNotBlank()) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            b.setPreferredTextLanguages(*codes(s))
            b.setSelectUndeterminedTextLanguage(false)
        }
        return b.build()
    }
}
