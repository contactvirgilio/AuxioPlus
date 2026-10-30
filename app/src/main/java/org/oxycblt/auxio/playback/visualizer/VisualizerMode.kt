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
    /** The waveform itself, like an oscilloscope. */
    OSCILLOSCOPE,

    /** A scrolling waterfall of loudness per frequency over time. */
    SPECTROGRAM,

    /** Bouncing bars for each frequency band, like foobar2000's spectrum. */
    SPECTRUM;

    /** The integer representation of this instance, as stored in settings. */
    val intCode: Int
        get() =
            when (this) {
                OSCILLOSCOPE -> IntegerTable.VISUALIZER_MODE_OSCILLOSCOPE
                SPECTROGRAM -> IntegerTable.VISUALIZER_MODE_SPECTROGRAM
                SPECTRUM -> IntegerTable.VISUALIZER_MODE_SPECTRUM
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
                IntegerTable.VISUALIZER_MODE_OSCILLOSCOPE -> OSCILLOSCOPE
                IntegerTable.VISUALIZER_MODE_SPECTROGRAM -> SPECTROGRAM
                IntegerTable.VISUALIZER_MODE_SPECTRUM -> SPECTRUM
                else -> null
            }
    }
}
