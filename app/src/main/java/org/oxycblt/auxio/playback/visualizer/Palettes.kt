/*
 * Copyright (c) 2026 Auxio Project
 * Palettes.kt is part of Auxio.
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
import androidx.preference.PreferenceManager

/**
 * A hand-picked colour palette that only turns up now and then.
 *
 * @property id A stable name used in backups.
 * @property name What the user sees when it shows up.
 * @property colors Three colours, bottom to top.
 * @property weird Whether this is one of the weird ones instead of the rare ones.
 */
class Preset(val id: String, val name: String, val colors: IntArray, val weird: Boolean)

/** Every palette that is not random. */
object Presets {
    private fun p(id: String, name: String, a: Long, b: Long, c: Long, weird: Boolean) =
        Preset(id, name, intArrayOf(a.toInt(), b.toInt(), c.toInt()), weird)

    val RARE =
        listOf(
            p("sunset", "Sunset", 0xFF2B1055, 0xFFD53369, 0xFFFFB347, false),
            p("vaporwave", "Vaporwave", 0xFFFF71CE, 0xFFB967FF, 0xFF01CDFE, false),
            p("tricolour", "Tricolour", 0xFF006600, 0xFFD7141A, 0xFFFFCC00, false),
            p("aurora", "Aurora", 0xFF0B6E4F, 0xFF08D9D6, 0xFFB8F2E6, false),
            p("ember", "Ember", 0xFF1A0000, 0xFFC1121F, 0xFFFFB703, false),
        )

    val WEIRD =
        listOf(
            p("neon_candy", "Neon candy", 0xFFFF1493, 0xFFFF1493, 0xFFB6FF00, true),
            p("blood_and_ice", "Blood and ice", 0xFF8B0000, 0xFF8B0000, 0xFFA7E8FF, true),
            p("acid_violet", "Acid violet", 0xFF4B0082, 0xFF4B0082, 0xFF39FF14, true),
            p("orange_teal", "Orange and teal", 0xFFFF6A00, 0xFF00B3B3, 0xFFFF6A00, true),
            p("monochrome", "Monochrome", 0xFF303030, 0xFF9E9E9E, 0xFFFFFFFF, true),
            p("sweets", "Sweets", 0xFFFFB3D9, 0xFFB3FFE6, 0xFFFFF7A8, true),
            p("magenta", "Magenta", 0xFFFF00FF, 0xFFFF00FF, 0xFFFF00FF, true),
            p("neon_clash", "Neon clash", 0xFF00FF00, 0xFFFF00FF, 0xFF00FF00, true),
            p("ghost", "Ghost", 0xFF1F1F1F, 0xFF2E2E2E, 0xFF3D3D3D, true),
            p("level_meter", "Level meter", 0xFF00C853, 0xFFFFEA00, 0xFFFF1744, true),
            p("trench", "Trench", 0xFF000000, 0xFF0D1B6B, 0xFFFFFFFF, true),
        )

    val ALL = RARE + WEIRD

    fun byId(id: String) = ALL.firstOrNull { it.id == id }
}

/** Remembers which presets the user has seen in the visualizer. */
class PaletteCollection private constructor(context: Context) {
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)

    /** The ids of the presets that have been seen. */
    fun seen(): Set<String> =
        try {
            preferences.getStringSet(KEY, emptySet()).orEmpty().toSet()
        } catch (e: Exception) {
            emptySet()
        }

    /** Remember that a preset was seen. */
    fun add(id: String) {
        val now = seen()
        if (id !in now) preferences.edit().putStringSet(KEY, now + id).apply()
    }

    /** Merge in presets from a backup. @return How many were new. */
    fun merge(ids: Collection<String>): Int {
        val now = seen()
        val added = ids.filter { Presets.byId(it) != null && it !in now }.toSet()
        if (added.isNotEmpty()) preferences.edit().putStringSet(KEY, now + added).apply()
        return added.size
    }

    companion object {
        private const val KEY = "auxio_seen_palettes"

        @Volatile private var instance: PaletteCollection? = null

        fun get(context: Context) =
            instance
                ?: synchronized(this) {
                    instance ?: PaletteCollection(context.applicationContext).also { instance = it }
                }
    }
}
