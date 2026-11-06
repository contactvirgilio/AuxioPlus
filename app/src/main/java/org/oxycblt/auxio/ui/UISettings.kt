/*
 * Copyright (c) 2023 Auxio Project
 * UISettings.kt is part of Auxio.
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
 
package org.oxycblt.auxio.ui

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.visualizer.VisualizerMode
import org.oxycblt.auxio.settings.Settings
import org.oxycblt.auxio.ui.accent.Accent
import timber.log.Timber as L

/**
 * User configuration for the general app UI.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
interface UISettings : Settings<UISettings.Listener> {
    /** The current theme. Represented by the AppCompatDelegate constants. */
    val theme: Int
    /** Whether to use a black background when a dark theme is currently used. */
    val useBlackTheme: Boolean
    /** The current [Accent] (Color Scheme). */
    var accent: Accent
    /** Whether to round additional UI elements that require album covers to be rounded. */
    val roundMode: Boolean
    /** Whether Now Playing shows the visualizer instead of the album cover. */
    var showVisualizer: Boolean
    /** The style of visualizer to show. */
    val visualizerMode: VisualizerMode
    /** How many seconds the colour-cycling spectrum shows each palette for. */
    val visualizerIntervalSeconds: Int

    interface Listener {
        /** Called when [roundMode] changes. */
        fun onRoundModeChanged() {}

        /** Called when any of the visualizer settings change. */
        fun onVisualizerChanged() {}
    }
}

class UISettingsImpl @Inject constructor(@ApplicationContext context: Context) :
    Settings.Impl<UISettings.Listener>(context), UISettings {
    override val theme: Int
        get() =
            sharedPreferences.getInt(
                getString(R.string.set_key_theme),
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            )

    override val useBlackTheme: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_black_theme), false)

    override var accent: Accent
        get() =
            Accent.from(
                sharedPreferences.getInt(getString(R.string.set_key_accent), Accent.DEFAULT)
            )
        set(value) {
            sharedPreferences.edit {
                putInt(getString(R.string.set_key_accent), value.index)
                apply()
            }
        }

    override val roundMode: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_round_mode), true)

    override var showVisualizer: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_show_visualizer), false)
        set(value) {
            sharedPreferences.edit {
                putBoolean(getString(R.string.set_key_show_visualizer), value)
                apply()
            }
        }

    override val visualizerMode: VisualizerMode
        get() =
            VisualizerMode.fromIntCode(
                sharedPreferences.getInt(getString(R.string.set_key_visualizer_mode), Int.MIN_VALUE)
            ) ?: VisualizerMode.SPECTRUM

    override val visualizerIntervalSeconds: Int
        get() =
            sharedPreferences.getInt(getString(R.string.set_key_visualizer_interval), 60).let {
                // 0 is every song, the rest are seconds. Anything else is an old setting.
                if (it in setOf(0, 60, 150, 300)) it else 60
            }

    override fun migrate() {
        // The oscilloscope and spectrogram visualizers were removed, fall back to the default.
        val visualizerKey = getString(R.string.set_key_visualizer_mode)
        if (
            sharedPreferences.contains(visualizerKey) &&
                VisualizerMode.fromIntCode(sharedPreferences.getInt(visualizerKey, 0)) == null
        ) {
            L.d("Resetting removed visualizer mode")
            sharedPreferences.edit { remove(visualizerKey) }
        }

        // The colour change intervals shorter than a minute were removed.
        val intervalKey = getString(R.string.set_key_visualizer_interval)
        if (
            sharedPreferences.contains(intervalKey) &&
                sharedPreferences.getInt(intervalKey, 60) !in setOf(0, 60, 150, 300)
        ) {
            L.d("Resetting removed colour change interval")
            sharedPreferences.edit { remove(intervalKey) }
        }

        if (sharedPreferences.contains(OLD_KEY_ACCENT3)) {
            L.d("Migrating $OLD_KEY_ACCENT3")

            var accent = sharedPreferences.getInt(OLD_KEY_ACCENT3, 5)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Accents were previously frozen as soon as the OS was updated to android
                // twelve, as dynamic colors were enabled by default. This is no longer the
                // case, so we need to re-update the setting to dynamic colors here.
                accent = 16
            }

            sharedPreferences.edit {
                putInt(getString(R.string.set_key_accent), accent)
                remove(OLD_KEY_ACCENT3)
                apply()
            }
        }
    }

    override fun onSettingChanged(key: String, listener: UISettings.Listener) {
        if (key == getString(R.string.set_key_round_mode)) {
            L.d("Dispatching round mode setting change")
            listener.onRoundModeChanged()
        }
        if (
            key == getString(R.string.set_key_show_visualizer) ||
                key == getString(R.string.set_key_visualizer_mode) ||
                key == getString(R.string.set_key_visualizer_interval)
        ) {
            L.d("Dispatching visualizer setting change")
            listener.onVisualizerChanged()
        }
    }

    private companion object {
        const val OLD_KEY_ACCENT3 = "auxio_accent"
    }
}
