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
import org.oxycblt.auxio.playback.visualizer.PalettePreviewView
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

    /** A dialog with a circle for every hand-picked palette, filled in once it's been seen. */
    private fun showCollection(preference: Preference) {
        val context = requireContext()
        val seen = PaletteCollection.get(context).seen()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
            }
        val hint =
            TextView(context).apply {
                setText(R.string.lbl_palette_hint)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setPadding(0, dp(8), 0, 0)
            }
        fun section(label: Int, presets: List<Preset>) {
            root.addView(
                TextView(context).apply {
                    text =
                        getString(
                            R.string.fmt_palette_count,
                            getString(label),
                            presets.count { it.id in seen },
                            presets.size,
                        )
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                    setPadding(0, dp(12), 0, dp(8))
                }
            )
            val grid = GridLayout(context).apply { columnCount = COLLECTION_COLUMNS }
            for (preset in presets) {
                val found = preset.id in seen
                val dot =
                    View(context).apply {
                        background =
                            if (found) {
                                GradientDrawable(
                                        GradientDrawable.Orientation.BOTTOM_TOP,
                                        when {
                                            // Doubled up, stripes stay mostly crisp.
                                            preset.banded ->
                                                preset.colors.flatMap { listOf(it, it) }.toIntArray()
                                            preset.colors.size == 1 ->
                                                intArrayOf(preset.colors[0], preset.colors[0])
                                            else -> preset.colors
                                        },
                                    )
                                    .apply { shape = GradientDrawable.OVAL }
                            } else {
                                GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(Color.TRANSPARENT)
                                    setStroke(dp(1), context.getAttrColorCompat(
                                        com.google.android.material.R.attr.colorOutlineVariant).defaultColor)
                                }
                            }
                        contentDescription = if (found) preset.name else null
                        setOnClickListener {
                            if (found) {
                                hint.setText(R.string.lbl_palette_hint)
                                showPreview(preset)
                            } else {
                                hint.setText(R.string.lbl_palette_unknown)
                            }
                        }
                    }
                grid.addView(
                    dot,
                    GridLayout.LayoutParams().apply {
                        width = dp(40)
                        height = dp(40)
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    },
                )
            }
            root.addView(grid)
        }
        // The name of a tapped palette goes at the top, where it stays in view.
        root.addView(hint)
        section(R.string.lbl_palettes_rare, Presets.RARE)
        section(R.string.lbl_palettes_legendary, Presets.LEGENDARY)
        section(R.string.lbl_palettes_unique, Presets.UNIQUE)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.set_palette_collection)
            .setView(ScrollView(context).apply { addView(root) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** A moving preview of a palette that has been found, with a way to use it right now. */
    private fun showPreview(preset: Preset) {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val preview = PalettePreviewView(context).apply { show(preset) }
        val frame =
            FrameLayout(context).apply {
                setPadding((24 * density).toInt(), (12 * density).toInt(), (24 * density).toInt(), 0)
                addView(
                    preview,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        (170 * density).toInt(),
                    ),
                )
            }
        MaterialAlertDialogBuilder(context)
            .setTitle(preset.name)
            .setMessage(
                when (preset.tier) {
                    Tier.RARE -> R.string.lbl_palettes_rare
                    Tier.LEGENDARY -> R.string.lbl_palettes_legendary
                    Tier.UNIQUE -> R.string.lbl_palettes_unique
                }
            )
            .setView(frame)
            .setPositiveButton(R.string.lbl_apply_now) { _, _ ->
                VisualizerView.applyPreset(preset)
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
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
                    showCollection(preference)
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

    private companion object {
        const val COLLECTION_COLUMNS = 6
    }
}
