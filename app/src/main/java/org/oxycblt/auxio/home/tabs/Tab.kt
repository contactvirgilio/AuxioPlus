/*
 * Copyright (c) 2021 Auxio Project
 * Tab.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home.tabs

import org.oxycblt.auxio.music.MusicType
import timber.log.Timber as L

/**
 * A representation of a library tab suitable for configuration.
 *
 * @param type The type of list in the home view this instance corresponds to.
 * @author Alexander Capehart (OxygenCobalt)
 */
sealed class Tab(open val type: MusicType) {
    /**
     * A visible tab. This will be visible in the home and tab configuration views.
     *
     * @param type The type of list in the home view this instance corresponds to.
     */
    data class Visible(override val type: MusicType) : Tab(type)

    /**
     * A visible tab. This will be visible in the tab configuration view, but not in the home view.
     *
     * @param type The type of list in the home view this instance corresponds to.
     */
    data class Invisible(override val type: MusicType) : Tab(type)

    companion object {
        // Like other IO-bound datatypes in Auxio, tabs are stored in a binary format. However, tabs
        // cannot be serialized on their own. Instead, they are saved as a sequence of tabs as shown
        // below:
        //
        // 0bTAB1_TAB2_TAB3_TAB4_TAB5_TAB6
        //
        // Where TABN is a chunk representing a tab at position N.
        // Each chunk in a sequence is represented as:
        //
        // VTTT
        //
        // Where V is a bit representing the visibility and T is a 3-bit integer representing the
        // MusicMode for this tab.
        //
        // Sequences with six tabs (since the Top 50 tab) are marked with SEQUENCE_SIX_TABS, and
        // ones with seven (since the Activity tab) with SEQUENCE_SEVEN_TABS. Older sequences have
        // fewer tabs and are read as such, with the newer tabs added hidden.

        /** The maximum index that a well-formed tab sequence should be. */
        const val MAX_SEQUENCE_IDX = 6

        /** The maximum index of a sequence saved before the Top 50 tab existed. */
        private const val LEGACY_MAX_SEQUENCE_IDX = 4

        /** The maximum index of a sequence saved before the Activity tab existed. */
        private const val SIX_TABS_MAX_SEQUENCE_IDX = 5

        /** Marks a sequence as holding six tabs rather than the original five. */
        private const val SEQUENCE_SIX_TABS = 1 shl 28

        /** Marks a sequence as holding seven tabs. */
        private const val SEQUENCE_SEVEN_TABS = 1 shl 29

        /**
         * The default tab sequence, in integer form. This represents a set of five visible tabs
         * ordered as "Song", "Album", "Artist", "Genre", and "Playlists", then a hidden "Top 50"
         * and a hidden "Activity".
         */
        const val SEQUENCE_DEFAULT = SEQUENCE_SEVEN_TABS or 0b1000_1001_1010_1011_1100_0101_0110

        /** Maps between the integer code in the tab sequence and it's [MusicType]. */
        private val MODE_TABLE =
            arrayOf(
                MusicType.SONGS,
                MusicType.ALBUMS,
                MusicType.ARTISTS,
                MusicType.GENRES,
                MusicType.PLAYLISTS,
                MusicType.TOP,
                MusicType.STATS,
            )

        /**
         * Convert an array of [Tab]s into it's integer representation.
         *
         * @param tabs The array of [Tab]s to convert
         * @return An integer representation of the [Tab] array
         */
        fun toIntCode(tabs: Array<Tab>): Int {
            // Like when deserializing, make sure there are no duplicate tabs for whatever reason.
            val distinct = tabs.distinctBy { it.type }
            if (tabs.size != distinct.size) {
                L.w(
                    "Tab sequences should not have duplicates [old: ${tabs.size} new: ${distinct.size}]"
                )
            }

            var sequence = SEQUENCE_SEVEN_TABS
            var shift = MAX_SEQUENCE_IDX * 4
            for (tab in distinct.take(MAX_SEQUENCE_IDX + 1)) {
                val bin =
                    when (tab) {
                        is Visible -> 1.shl(3) or MODE_TABLE.indexOf(tab.type)
                        is Invisible -> MODE_TABLE.indexOf(tab.type)
                    }

                sequence = sequence or bin.shl(shift)
                shift -= 4
            }

            return sequence
        }

        /**
         * Convert a [Tab] integer representation into it's corresponding array of [Tab]s.
         *
         * @param intCode The integer representation of the [Tab]s.
         * @return An array of [Tab]s corresponding to the sequence.
         */
        fun fromIntCode(intCode: Int): Array<Tab>? {
            val tabs = mutableListOf<Tab>()
            val maxIdx =
                when {
                    intCode and SEQUENCE_SEVEN_TABS != 0 -> MAX_SEQUENCE_IDX
                    intCode and SEQUENCE_SIX_TABS != 0 -> SIX_TABS_MAX_SEQUENCE_IDX
                    else -> LEGACY_MAX_SEQUENCE_IDX
                }

            // Try to parse a mode for each chunk in the sequence.
            // If we can't parse one, just skip it.
            for (shift in (0..maxIdx * 4).reversed() step 4) {
                val chunk = intCode.shr(shift) and 0b1111
                val mode = MODE_TABLE.getOrNull(chunk and 7) ?: continue

                // Figure out the visibility
                tabs +=
                    if (chunk and 1.shl(3) != 0) {
                        Visible(mode)
                    } else {
                        Invisible(mode)
                    }
            }

            // Make sure there are no duplicate tabs
            val distinct = tabs.distinctBy { it.type }.toMutableList()
            if (tabs.size != distinct.size) {
                L.w(
                    "Tab sequences should not have duplicates [old: ${tabs.size} new: ${distinct.size}]"
                )
            }

            // For safety, return null if we have an empty tab array.
            if (distinct.isEmpty()) {
                L.e("Sequence was empty, which is invalid")
                return null
            }

            // Tabs that didn't exist when the sequence was saved are added at the end, hidden.
            for (mode in MODE_TABLE) {
                if (distinct.none { it.type == mode }) {
                    distinct.add(Invisible(mode))
                }
            }

            return distinct.toTypedArray()
        }
    }
}
