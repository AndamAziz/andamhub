package uk.andam.app.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

/** Audio / subtitle / quality choices read from ExoPlayer's current tracks. */
object TrackMenu {
    data class Option(val label: String, val selected: Boolean, val group: TrackGroup, val index: Int, val type: Int)

    fun options(player: ExoPlayer, type: Int): List<Option> {
        val out = ArrayList<Option>()
        player.currentTracks.groups.forEach { g: Tracks.Group ->
            if (g.type != type) return@forEach
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                out.add(Option(label(g.getTrackFormat(i), type, out.size + 1), g.isTrackSelected(i), g.mediaTrackGroup, i, type))
            }
        }
        if (type == C.TRACK_TYPE_VIDEO) out.sortByDescending { it.group.getFormat(it.index).height }
        return out
    }

    private fun label(f: Format, type: Int, n: Int): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
            Locale(it).getDisplayLanguage(Locale.ENGLISH).ifBlank { it }
        }
        return when (type) {
            C.TRACK_TYPE_VIDEO -> if (f.height > 0) "${f.height}p" else "Track $n"
            C.TRACK_TYPE_AUDIO -> listOfNotNull(f.label ?: lang ?: "Track $n", channels(f.channelCount)).joinToString(" · ")
            else -> f.label ?: lang ?: "Subtitle $n"
        }
    }

    private fun channels(c: Int): String? = when {
        c <= 0 -> null
        c == 1 -> "Mono"
        c == 2 -> "Stereo"
        c == 6 -> "5.1"
        c == 8 -> "7.1"
        else -> "$c ch"
    }

    fun select(player: ExoPlayer, o: Option) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(o.type, false)
            .setOverrideForType(TrackSelectionOverride(o.group, o.index))
            .build()
    }

    fun disable(player: ExoPlayer, type: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(type)
            .setTrackTypeDisabled(type, true)
            .build()
    }

    fun auto(player: ExoPlayer, type: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(type)
            .setTrackTypeDisabled(type, false)
            .build()
    }

    fun hasOverride(player: ExoPlayer, type: Int): Boolean =
        player.trackSelectionParameters.overrides.values.any { it.type == type }
}
