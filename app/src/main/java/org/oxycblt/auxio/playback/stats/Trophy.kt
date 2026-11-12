/*
 * Copyright (c) 2026 Auxio Project
 * Trophy.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.playback.stats

import android.content.Context
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import org.oxycblt.auxio.R
import org.oxycblt.auxio.util.getPlural
import org.oxycblt.musikr.Song

/**
 * The level of a [Trophy], by how often the song was played.
 *
 * @property plays How many plays it takes.
 * @property color The colour used on normal screens.
 * @property bright The brighter colour used on the black full screen visualizer.
 * @property fill The dark colour of the progress fill on the full screen visualizer.
 */
enum class TrophyTier(
    val plays: Int,
    val color: Int,
    val bright: Int,
    val fill: Int,
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
) {
    BRONZE(
        25,
        0xFFB0703C.toInt(),
        0xFFE0A070.toInt(),
        0xFF4A2F19.toInt(),
        R.drawable.ic_medal_24,
        R.string.lbl_trophy_bronze,
    ),
    SILVER(
        50,
        0xFF8E99A3.toInt(),
        0xFFE3E8EC.toInt(),
        0xFF454A4F.toInt(),
        R.drawable.ic_medal_24,
        R.string.lbl_trophy_silver,
    ),
    GOLD(
        100,
        0xFFD4A017.toInt(),
        0xFFFFD75A.toInt(),
        0xFF5A4508.toInt(),
        R.drawable.ic_trophy_24,
        R.string.lbl_trophy_gold,
    ),
    DIAMOND(
        150,
        0xFF2BB5E0.toInt(),
        0xFF9BE7FF.toInt(),
        0xFF17414F.toInt(),
        R.drawable.ic_diamond_24,
        R.string.lbl_trophy_diamond,
    ),
}

/**
 * Recognition for a song that is among the [Trophies.TOP] most played of all time and has been
 * played at least [TrophyTier.BRONZE] times.
 *
 * @property plays How often the song was played.
 * @property rank Its place among the most played songs, starting at 1.
 */
data class Trophy(val tier: TrophyTier, val plays: Int, val rank: Int) {
    /** "Gold · 100 plays · #9 all time" */
    fun describe(context: Context) =
        context.getString(
            R.string.fmt_trophy,
            context.getString(tier.label),
            context.getPlural(R.plurals.fmt_play_count, plays),
            rank,
        )

    /** "100 plays · #9 all time" */
    fun describeShort(context: Context) =
        context.getString(
            R.string.fmt_trophy_short,
            context.getPlural(R.plurals.fmt_play_count, plays),
            rank,
        )
}

/** The trophies of every song, kept up to date by [PlayCountRepository]. */
object Trophies {
    /** Only this many of the most played songs can have a trophy. */
    const val TOP = 50

    @Volatile private var all: Map<String, Trophy> = emptyMap()

    /** The trophy of [song], if it has earned one. */
    fun of(song: Song): Trophy? = all[song.uid.toString()]

    /** Work the trophies out again from the play counts. */
    fun update(counts: Map<String, PlayCountRepository.Entry>) {
        all =
            counts.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, PlayCountRepository.Entry>> {
                            it.value.count
                        }
                        .thenByDescending { it.value.lastPlayedMs }
                )
                .take(TOP)
                .mapIndexedNotNull { i, (uid, entry) ->
                    val tier = TrophyTier.entries.lastOrNull { entry.count >= it.plays }
                    if (tier == null) null else uid to Trophy(tier, entry.count, i + 1)
                }
                .toMap()
    }
}

/** Show the trophy of [song] as a small icon at the end of this text, or nothing without one. */
fun TextView.showTrophy(song: Song) {
    val trophy = Trophies.of(song)
    val icon = if (trophy == null) null else AppCompatResources.getDrawable(context, trophy.tier.icon)
    if (trophy == null || icon == null) {
        setCompoundDrawablesRelative(null, null, null, null)
        return
    }
    val drawable = icon.mutate()
    val size = (18 * resources.displayMetrics.density).toInt()
    drawable.setBounds(0, 0, size, size)
    drawable.setTint(trophy.tier.color)
    compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
    setCompoundDrawablesRelative(null, null, drawable, null)
}
