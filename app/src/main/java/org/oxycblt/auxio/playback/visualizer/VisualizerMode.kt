/*
 * Copyright (c) 2026 Auxio Project
 * VisualizerMode.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.visualizer

import org.oxycblt.auxio.IntegerTable

/** The styles of visualizer that can be shown in place of the album cover. */
enum class VisualizerMode {
    /** Bouncing bars for each frequency band in the theme colour, like foobar2000's spectrum. */
    SPECTRUM,

    /** The same bars filled with a three-colour gradient that fades between palettes. */
    SPECTRUM_CYCLE,

    /**
     * The colour cycle spectrum on its own full screen page, opened by tapping the cover. The cover
     * itself keeps showing the album art.
     */
    FULLSCREEN;

    /** The integer representation of this instance, as stored in settings. */
    val intCode: Int
        get() =
            when (this) {
                SPECTRUM -> IntegerTable.VISUALIZER_MODE_SPECTRUM
                SPECTRUM_CYCLE -> IntegerTable.VISUALIZER_MODE_SPECTRUM_CYCLE
                FULLSCREEN -> IntegerTable.VISUALIZER_MODE_FULLSCREEN
            }

    companion object {
        /**
         * Convert a [VisualizerMode] integer representation into an instance.
         *
         * @param intCode An integer representation of a [VisualizerMode].
         * @return The corresponding [VisualizerMode], or null if the code is invalid.
         */
        fun fromIntCode(intCode: Int) =
            when (intCode) {
                IntegerTable.VISUALIZER_MODE_SPECTRUM -> SPECTRUM
                IntegerTable.VISUALIZER_MODE_SPECTRUM_CYCLE -> SPECTRUM_CYCLE
                IntegerTable.VISUALIZER_MODE_FULLSCREEN -> FULLSCREEN
                else -> null
            }
    }
}
