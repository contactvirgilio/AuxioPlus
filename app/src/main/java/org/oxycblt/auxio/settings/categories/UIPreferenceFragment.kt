/*
 * Copyright (c) 2023 Auxio Project
 * UIPreferenceFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.settings.categories

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.oxycblt.auxio.playback.visualizer.PaletteDialogs
import org.oxycblt.auxio.playback.visualizer.PaletteCollection
import org.oxycblt.auxio.playback.visualizer.Preset
import org.oxycblt.auxio.playback.visualizer.Presets
import org.oxycblt.auxio.playback.visualizer.Tier
import org.oxycblt.auxio.playback.visualizer.VisualizerView
import org.oxycblt.auxio.util.getAttrColorCompat
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.BasePreferenceFragment
import org.oxycblt.auxio.settings.ui.WrappedDialogPreference
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.util.isNight
import org.oxycblt.auxio.util.navigateSafe
import timber.log.Timber as L

/**
 * Display preferences.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class UIPreferenceFragment : BasePreferenceFragment(R.xml.preferences_ui) {
    @Inject lateinit var uiSettings: UISettings

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        if (preference.key == getString(R.string.set_key_accent)) {
            L.d("Navigating to accent dialog")
            findNavController().navigateSafe(UIPreferenceFragmentDirections.accentSettings())
        }
    }

    override fun onResume() {
        super.onResume()
        findPreference<Preference>(getString(R.string.set_key_palette_collection))?.let {
            updateCollectionSummary(it)
        }
    }

    private fun updateCollectionSummary(preference: Preference) {
        val seen = PaletteCollection.get(requireContext()).seen()
        preference.summary =
            getString(
                R.string.fmt_palette_summary,
                Presets.RARE.count { it.id in seen },
                Presets.RARE.size,
                Presets.LEGENDARY.count { it.id in seen },
                Presets.LEGENDARY.size,
                Presets.UNIQUE.count { it.id in seen },
                Presets.UNIQUE.size,
            )
    }

    override fun onSetupPreference(preference: Preference) {
        when (preference.key) {
            getString(R.string.set_key_theme) -> {
                L.d("Configuring theme setting")
                preference.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, value ->
                        L.d("Theme changed, recreating")
                        requireActivity().recreate()
                        true
                    }
            }
            getString(R.string.set_key_palette_collection) -> {
                updateCollectionSummary(preference)
                preference.setOnPreferenceClickListener {
                    PaletteDialogs.showCollection(requireContext())
                    true
                }
            }
            getString(R.string.set_key_bluetooth_delay) -> {
                preference.summary = bluetoothDelaySummary(preference)
                preference.setOnPreferenceClickListener {
                    showBluetoothDelay(preference)
                    true
                }
            }
            getString(R.string.set_key_accent) -> {
                L.d("Configuring accent setting")
                preference.summary = getString(uiSettings.accent.name)
            }
            getString(R.string.set_key_black_theme) -> {
                L.d("Configuring black theme setting")
                preference.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, _ ->
                        val activity = requireActivity()
                        if (activity.isNight) {
                            L.d("Black theme changed in night mode, recreating")
                            activity.recreate()
                        }

                        true
                    }
            }
        }
    }

    private fun bluetoothDelay(preference: Preference) =
        preference.sharedPreferences?.getInt(preference.key, DEFAULT_BLUETOOTH_DELAY)
            ?: DEFAULT_BLUETOOTH_DELAY

    private fun bluetoothDelaySummary(preference: Preference) =
        getString(R.string.fmt_bluetooth_delay, bluetoothDelay(preference))

    /** A small dialog with a minus and a plus button that move the delay in steps of 10. */
    private fun showBluetoothDelay(preference: Preference) {
        val context = requireContext()
        val density = resources.displayMetrics.density
        var value = bluetoothDelay(preference)
        val label =
            TextView(context).apply {
                textSize = 24f
                gravity = android.view.Gravity.CENTER
                minWidth = (110 * density).toInt()
            }
        fun refresh() {
            label.text = getString(R.string.fmt_bluetooth_delay, value)
            preference.sharedPreferences?.edit()?.putInt(preference.key, value)?.apply()
            preference.summary = bluetoothDelaySummary(preference)
        }
        fun step(delta: Int) {
            value = (value + delta).coerceIn(0, MAX_BLUETOOTH_DELAY)
            refresh()
        }
        fun button(text: String, delta: Int) =
            com.google.android.material.button.MaterialButton(context).apply {
                this.text = text
                textSize = 22f
                setOnClickListener { step(delta) }
            }
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                setPadding(0, (16 * density).toInt(), 0, 0)
                addView(button("−", -STEP))
                addView(label)
                addView(button("+", STEP))
            }
        label.text = getString(R.string.fmt_bluetooth_delay, value)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.set_bluetooth_delay)
            .setView(row)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private companion object {
        const val DEFAULT_BLUETOOTH_DELAY = 200
        const val MAX_BLUETOOTH_DELAY = 500
        const val STEP = 10
        const val COLLECTION_COLUMNS = 6
    }
}
