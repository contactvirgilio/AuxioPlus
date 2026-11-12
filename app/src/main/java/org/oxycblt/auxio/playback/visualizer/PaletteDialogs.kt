/*
 * Copyright (c) 2026 Auxio Project
 * PaletteDialogs.kt is part of Auxio.
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

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.R as MR
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.oxycblt.auxio.R
import org.oxycblt.auxio.util.getAttrColorCompat

/** The dialogs around the hand-picked palettes: the collection and the preview of one of them. */
object PaletteDialogs {
    private const val COLUMNS = 6

    /** A circle for every hand-picked palette, filled in once it has been seen. */
    fun showCollection(context: Context) {
        val seen = PaletteCollection.get(context).seen()
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
            }
        val hint =
            TextView(context).apply {
                setText(R.string.lbl_palette_hint)
                setTextAppearance(MR.style.TextAppearance_Material3_BodyMedium)
                setPadding(0, dp(8), 0, 0)
            }
        // The hint goes at the top, where it stays in view.
        root.addView(hint)
        fun section(label: Int, presets: List<Preset>) {
            root.addView(
                TextView(context).apply {
                    text =
                        context.getString(
                            R.string.fmt_palette_count,
                            context.getString(label),
                            presets.count { it.id in seen },
                            presets.size,
                        )
                    setTextAppearance(MR.style.TextAppearance_Material3_LabelLarge)
                    setPadding(0, dp(12), 0, dp(8))
                }
            )
            val grid = GridLayout(context).apply { columnCount = COLUMNS }
            for (preset in presets) {
                val found = preset.id in seen
                val dot =
                    View(context).apply {
                        background =
                            if (found) {
                                preset.swatch()
                            } else {
                                GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor(Color.TRANSPARENT)
                                    setStroke(
                                        dp(1),
                                        context
                                            .getAttrColorCompat(MR.attr.colorOutlineVariant)
                                            .defaultColor,
                                    )
                                }
                            }
                        contentDescription = if (found) preset.name else null
                        setOnClickListener {
                            if (found) {
                                hint.setText(R.string.lbl_palette_hint)
                                showPreview(context, preset)
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
        section(R.string.lbl_palettes_rare, Presets.RARE)
        section(R.string.lbl_palettes_legendary, Presets.LEGENDARY)
        section(R.string.lbl_palettes_unique, Presets.UNIQUE)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.set_palette_collection)
            .setView(ScrollView(context).apply { addView(root) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * A moving preview of a palette that has been found, with a way to use it right now.
     *
     * @param offerCollection Whether to add a button that opens the collection.
     */
    fun showPreview(context: Context, preset: Preset, offerCollection: Boolean = false) {
        val density = context.resources.displayMetrics.density
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
        val builder =
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
        if (offerCollection) {
            builder.setNeutralButton(R.string.lbl_palette_collection_short) { _, _ ->
                showCollection(context)
            }
        }
        builder.show()
    }
}
