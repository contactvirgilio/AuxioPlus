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
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import androidx.preference.PreferenceManager

/** How hard a hand-picked palette is to come across. */
enum class Tier {
    /** Three colours. */
    RARE,
    /** Four colours. */
    LEGENDARY,
    /** The odd ones: stripes, single colours, nearly invisible. */
    UNIQUE,
}

/**
 * A hand-picked colour palette that only turns up now and then.
 *
 * @property id A stable name used in backups.
 * @property name What the user sees when it shows up.
 * @property tier How hard it is to come across.
 * @property banded Whether the colours are stacked as hard stripes instead of blended.
 * @property colors The colours, bottom to top.
 */
class Preset(
    val id: String,
    val name: String,
    val tier: Tier,
    val banded: Boolean,
    val colors: IntArray,
)

/** A round swatch of this palette's colours, [sizePx] across (or unsized with 0). */
fun Preset.swatch(sizePx: Int = 0) =
    GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            when {
                // Doubled up, stripes stay mostly crisp.
                banded -> colors.flatMap { listOf(it, it) }.toIntArray()
                colors.size == 1 -> intArrayOf(colors[0], colors[0])
                else -> colors
            },
        )
        .apply {
            shape = GradientDrawable.OVAL
            if (sizePx > 0) setSize(sizePx, sizePx)
        }

/** Every palette that is not random. */
object Presets {
    private fun p(id: String, name: String, tier: Tier, banded: Boolean, vararg colors: Long) =
        Preset(id, name, tier, banded, IntArray(colors.size) { colors[it].toInt() })

    val ALL =
        listOf(
            p("sunset", "Sunset", Tier.RARE, false, 0xFF2B1055, 0xFFD53369, 0xFFFFB347),
            p("vaporwave", "Vaporwave", Tier.RARE, false, 0xFFFF71CE, 0xFFB967FF, 0xFF01CDFE),
            p("tricolour", "Tricolour", Tier.RARE, false, 0xFF006600, 0xFFD7141A, 0xFFFFCC00),
            p("aurora", "Aurora", Tier.RARE, false, 0xFF0B6E4F, 0xFF08D9D6, 0xFFB8F2E6),
            p("ember", "Ember", Tier.RARE, false, 0xFF1A0000, 0xFFC1121F, 0xFFFFB703),
            p("lagoon", "Lagoon", Tier.RARE, false, 0xFF03045E, 0xFF0096C7, 0xFF90E0EF),
            p("forest_floor", "Forest floor", Tier.RARE, false, 0xFF1B3A1B, 0xFF4F772D, 0xFFC9E265),
            p("peach_melba", "Peach melba", Tier.RARE, false, 0xFFFF6F61, 0xFFFFB088, 0xFFFFE5B4),
            p("midnight_oil", "Midnight oil", Tier.RARE, false, 0xFF0D1B2A, 0xFF415A77, 0xFFE0E1DD),
            p("rosewood", "Rosewood", Tier.RARE, false, 0xFF2D0A12, 0xFF8C1C3A, 0xFFE5989B),
            p("citrus", "Citrus", Tier.RARE, false, 0xFF2E7D32, 0xFFC0CA33, 0xFFFFEB3B),
            p("lavender_field", "Lavender field", Tier.RARE, false, 0xFF3C1E6E, 0xFF9D7BD8, 0xFFE8DAFF),
            p("copper", "Copper", Tier.RARE, false, 0xFF2B1A12, 0xFFB4572A, 0xFFF2B48A),
            p("arctic", "Arctic", Tier.RARE, false, 0xFF0B2545, 0xFF8DA9C4, 0xFFEEF4ED),
            p("jungle", "Jungle", Tier.RARE, false, 0xFF004B23, 0xFF38B000, 0xFFCCFF33),
            p("flamingo", "Flamingo", Tier.RARE, false, 0xFF7A1E48, 0xFFFF5C8A, 0xFFFFC2D4),
            p("storm", "Storm", Tier.RARE, false, 0xFF1F2933, 0xFF52606D, 0xFF9FB3C8),
            p("honey", "Honey", Tier.RARE, false, 0xFF5C3D00, 0xFFE3A008, 0xFFFFE8A3),
            p("grape_soda", "Grape soda", Tier.RARE, false, 0xFF240046, 0xFF7B2CBF, 0xFFE0AAFF),
            p("seafoam", "Seafoam", Tier.RARE, false, 0xFF05445E, 0xFF189AB4, 0xFFD4F1F4),
            p("brick", "Brick", Tier.RARE, false, 0xFF3D0C02, 0xFF9B2915, 0xFFE9B872),
            p("moss", "Moss", Tier.RARE, false, 0xFF232D1A, 0xFF606C38, 0xFFDDE5B6),
            p("bubblegum", "Bubblegum", Tier.RARE, false, 0xFFC9184A, 0xFFFF8FAB, 0xFFFFE5EC),
            p("arcade", "Arcade", Tier.RARE, false, 0xFF0F0A3C, 0xFF4361EE, 0xFFF72585),
            p("sandstone", "Sandstone", Tier.RARE, false, 0xFF6B4226, 0xFFD9A066, 0xFFF5E6CA),
            p("emerald", "Emerald", Tier.RARE, false, 0xFF012A1A, 0xFF04A777, 0xFFA7F3D0),
            p("cherry_cola", "Cherry cola", Tier.RARE, false, 0xFF1A0508, 0xFF7B0828, 0xFFFF3B3F),
            p("sky", "Sky", Tier.RARE, false, 0xFF0A4D8C, 0xFF4FC3F7, 0xFFE1F5FE),
            p("mustard", "Mustard", Tier.RARE, false, 0xFF3A3000, 0xFFC99700, 0xFFF9E076),
            p("orchid", "Orchid", Tier.RARE, false, 0xFF3B0A45, 0xFFC13C9D, 0xFFF7B2E6),
            p("slate_rose", "Slate rose", Tier.RARE, false, 0xFF2F3E46, 0xFF84A98C, 0xFFF4ACB7),
            p("tangerine", "Tangerine", Tier.RARE, false, 0xFF7F2B00, 0xFFFF7A00, 0xFFFFD166),
            p("kelp", "Kelp", Tier.RARE, false, 0xFF0B2B26, 0xFF235347, 0xFF8EB69B),
            p("plum_wine", "Plum wine", Tier.RARE, false, 0xFF2A0A2E, 0xFF6F1D5E, 0xFFD4A5C9),
            p("desert_night", "Desert night", Tier.RARE, false, 0xFF14213D, 0xFFFCA311, 0xFFE5E5E5),
            p("lime_sorbet", "Lime sorbet", Tier.RARE, false, 0xFF3A5A00, 0xFFA7C957, 0xFFF2E8CF),
            p("blueberry", "Blueberry", Tier.RARE, false, 0xFF0B0B45, 0xFF3A3AC4, 0xFFA8B8FF),
            p("terracotta", "Terracotta", Tier.RARE, false, 0xFF4A1E0E, 0xFFC1440E, 0xFFF4A259),
            p("mint", "Mint", Tier.RARE, false, 0xFF004D40, 0xFF26A69A, 0xFFB2DFDB),
            p("ruby", "Ruby", Tier.RARE, false, 0xFF2B0000, 0xFFB00020, 0xFFFF8A80),
            p("dune", "Dune", Tier.RARE, false, 0xFF2F2A1F, 0xFFA68A64, 0xFFEDE0D4),
            p("electric", "Electric", Tier.RARE, false, 0xFF03071E, 0xFF3A0CA3, 0xFF4CC9F0),
            p("poppy", "Poppy", Tier.RARE, false, 0xFF1B4332, 0xFFE63946, 0xFFFFD6A5),
            p("wisteria", "Wisteria", Tier.RARE, false, 0xFF2E294E, 0xFF8661C1, 0xFFEFBCD5),
            p("olive_grove", "Olive grove", Tier.RARE, false, 0xFF283618, 0xFF7F8F3A, 0xFFFEFAE0),
            p("papaya", "Papaya", Tier.RARE, false, 0xFF9D0208, 0xFFF48C06, 0xFFFFF3B0),
            p("glacier_mint", "Glacier mint", Tier.RARE, false, 0xFF012A4A, 0xFF2A9D8F, 0xFFE9F5DB),
            p("mulberry", "Mulberry", Tier.RARE, false, 0xFF1F0318, 0xFF7D1D6F, 0xFFF28482),
            p("canary", "Canary", Tier.RARE, false, 0xFF264653, 0xFFE9C46A, 0xFFFFF8DC),
            p("indigo_dye", "Indigo dye", Tier.RARE, false, 0xFF0A0A2A, 0xFF283593, 0xFF9FA8DA),
            p("thermal", "Thermal", Tier.LEGENDARY, false, 0xFF000004, 0xFF8C2981, 0xFFFE9F6D, 0xFFFCFDBF),
            p("deep_sea", "Deep sea", Tier.LEGENDARY, false, 0xFF020024, 0xFF0B3D91, 0xFF00A6A6, 0xFFF5FFB0),
            p("prism", "Prism", Tier.LEGENDARY, false, 0xFFFF0000, 0xFFFFFF00, 0xFF00C8FF, 0xFF8B00FF),
            p("oil_slick", "Oil slick", Tier.LEGENDARY, false, 0xFF2E0F4F, 0xFF0F6E8C, 0xFF3FBF7F, 0xFFD96AA7),
            p("patina", "Patina", Tier.LEGENDARY, false, 0xFF3B1F0B, 0xFFC97B3A, 0xFF4FA39A, 0xFF9FE0D0),
            p("peacock", "Peacock", Tier.LEGENDARY, false, 0xFF0B1D51, 0xFF0E6BA8, 0xFF00A896, 0xFFE0CA3C),
            p("ice_and_fire", "Ice and fire", Tier.LEGENDARY, false, 0xFF00E5FF, 0xFF1A0033, 0xFFFF3D00, 0xFFFFEA00),
            p("nebula", "Nebula", Tier.LEGENDARY, false, 0xFF0B0033, 0xFF35A7FF, 0xFFFF5964, 0xFFFFE74C),
            p("coral_reef", "Coral reef", Tier.LEGENDARY, false, 0xFF003844, 0xFF006C67, 0xFFF194B4, 0xFFFFEBC6),
            p("gold_bar", "Gold bar", Tier.LEGENDARY, false, 0xFF3D2B00, 0xFFB8860B, 0xFFFFD700, 0xFFFFF8DC),
            p("chrome", "Chrome", Tier.LEGENDARY, false, 0xFF1C1C1C, 0xFFE0E0E0, 0xFF2A6FDB, 0xFFFFFFFF),
            p("mint_chocolate", "Mint chocolate", Tier.LEGENDARY, false, 0xFF2B1B12, 0xFF5C3A21, 0xFF3EB489, 0xFFC9FFE5),
            p("supernova", "Supernova", Tier.LEGENDARY, false, 0xFF0D0221, 0xFFFF2A6D, 0xFFFFD319, 0xFFFFFFFF),
            p("rainforest", "Rainforest", Tier.LEGENDARY, false, 0xFF081C15, 0xFF2D6A4F, 0xFFFFB703, 0xFFE63946),
            p("opal", "Opal", Tier.LEGENDARY, false, 0xFFB8F2E6, 0xFFAED9E0, 0xFFFFA69E, 0xFFFAF3DD),
            p("tropical_punch", "Tropical punch", Tier.LEGENDARY, false, 0xFFFF0054, 0xFFFF5400, 0xFFFFBD00, 0xFF00B4D8),
            p("northern_dusk", "Northern dusk", Tier.LEGENDARY, false, 0xFF0B132B, 0xFF3A506B, 0xFF5BC0BE, 0xFFFF6B6B),
            p("dragonfruit", "Dragonfruit", Tier.LEGENDARY, false, 0xFF3A0CA3, 0xFFFF006E, 0xFFF8F9FA, 0xFF70E000),
            p("abyss_glow", "Abyss glow", Tier.LEGENDARY, false, 0xFF03001C, 0xFF301E67, 0xFF5B8FB9, 0xFFB6EADA),
            p("harvest", "Harvest", Tier.LEGENDARY, false, 0xFF3C1518, 0xFF69140E, 0xFFD58936, 0xFFF2F3AE),
            p("macaw", "Macaw", Tier.LEGENDARY, false, 0xFF0353A4, 0xFFFFD60A, 0xFFD00000, 0xFF38B000),
            p("stained_glass", "Stained glass", Tier.LEGENDARY, false, 0xFF6A040F, 0xFF1D3557, 0xFF2A9D8F, 0xFFE9C46A),
            p("galaxy", "Galaxy", Tier.LEGENDARY, false, 0xFF000000, 0xFF240046, 0xFFFF6D00, 0xFF48CAE4),
            p("kingfisher", "Kingfisher", Tier.LEGENDARY, false, 0xFF023E8A, 0xFF00B4D8, 0xFFFB8500, 0xFFFFFFFF),
            p("autumn", "Autumn", Tier.LEGENDARY, false, 0xFF3E1F00, 0xFF9C2A00, 0xFFE36414, 0xFFF6E27F),
            p("poison_dart", "Poison dart", Tier.LEGENDARY, false, 0xFF001219, 0xFF0077B6, 0xFFFFD60A, 0xFFEE9B00),
            p("bonfire_night", "Bonfire night", Tier.LEGENDARY, false, 0xFF03071E, 0xFF6A00F4, 0xFFF20089, 0xFFFFB600),
            p("mermaid", "Mermaid", Tier.LEGENDARY, false, 0xFF3D348B, 0xFF00A6A6, 0xFF7AE582, 0xFFF7B801),
            p("watermelon", "Watermelon", Tier.LEGENDARY, false, 0xFF1B5E20, 0xFFA5D6A7, 0xFFFFFFFF, 0xFFFF1744),
            p("saturn", "Saturn", Tier.LEGENDARY, false, 0xFF22223B, 0xFFC9ADA7, 0xFFE9C46A, 0xFFF2E9E4),
            p("neon_candy", "Neon candy", Tier.UNIQUE, false, 0xFFFF1493, 0xFFFF1493, 0xFFB6FF00),
            p("blood_and_ice", "Blood and ice", Tier.UNIQUE, false, 0xFF8B0000, 0xFF8B0000, 0xFFA7E8FF),
            p("acid_violet", "Acid violet", Tier.UNIQUE, false, 0xFF4B0082, 0xFF4B0082, 0xFF39FF14),
            p("orange_teal", "Orange and teal", Tier.UNIQUE, false, 0xFFFF6A00, 0xFF00B3B3, 0xFFFF6A00),
            p("monochrome", "Monochrome", Tier.UNIQUE, false, 0xFF303030, 0xFF9E9E9E, 0xFFFFFFFF),
            p("sweets", "Sweets", Tier.UNIQUE, false, 0xFFFFB3D9, 0xFFB3FFE6, 0xFFFFF7A8),
            p("magenta", "Magenta", Tier.UNIQUE, false, 0xFFFF00FF, 0xFFFF00FF, 0xFFFF00FF),
            p("neon_clash", "Neon clash", Tier.UNIQUE, false, 0xFF00FF00, 0xFFFF00FF, 0xFF00FF00),
            p("ghost", "Ghost", Tier.UNIQUE, false, 0xFF1F1F1F, 0xFF2E2E2E, 0xFF3D3D3D),
            p("level_meter", "Level meter", Tier.UNIQUE, false, 0xFF00C853, 0xFFFFEA00, 0xFFFF1744),
            p("trench", "Trench", Tier.UNIQUE, false, 0xFF000000, 0xFF0D1B6B, 0xFFFFFFFF),
            p("hazard_tape", "Hazard tape", Tier.UNIQUE, true, 0xFFFFD400, 0xFF111111, 0xFFFFD400, 0xFF111111, 0xFFFFD400, 0xFF111111, 0xFFFFD400),
            p("pocket_console", "Pocket console", Tier.UNIQUE, true, 0xFF0F380F, 0xFF306230, 0xFF8BAC0F, 0xFF9BBC0F),
            p("candy_cane", "Candy cane", Tier.UNIQUE, true, 0xFFE10600, 0xFFFFFFFF, 0xFFE10600, 0xFFFFFFFF, 0xFFE10600, 0xFFFFFFFF),
            p("barber_pole", "Barber pole", Tier.UNIQUE, true, 0xFFE10600, 0xFFFFFFFF, 0xFF0033A0, 0xFFFFFFFF),
            p("blackout", "Blackout", Tier.UNIQUE, false, 0xFF000000, 0xFF000000, 0xFF000000, 0xFFFFFFFF),
            p("highlighter", "Highlighter", Tier.UNIQUE, true, 0xFFFF00A8, 0xFFFFF200, 0xFF00FFE0, 0xFF7CFF00),
            p("glitch", "Glitch", Tier.UNIQUE, true, 0xFF00FFFF, 0xFFFF0000, 0xFF000000, 0xFFFFFFFF),
            p("allsorts", "Allsorts", Tier.UNIQUE, true, 0xFF111111, 0xFFFF7EB6, 0xFFFFE14D, 0xFFFFFFFF),
            p("zebra", "Zebra", Tier.UNIQUE, true, 0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF),
        )

    val RARE = ALL.filter { it.tier == Tier.RARE }
    val LEGENDARY = ALL.filter { it.tier == Tier.LEGENDARY }
    val UNIQUE = ALL.filter { it.tier == Tier.UNIQUE }

    fun of(tier: Tier) =
        when (tier) {
            Tier.RARE -> RARE
            Tier.LEGENDARY -> LEGENDARY
            Tier.UNIQUE -> UNIQUE
        }

    fun byId(id: String) = ALL.firstOrNull { it.id == id }
}

/**
 * Palettes are drawn from a ramp: [SIZE] colours from the bottom of the visualizer to the top. Every
 * palette becomes a ramp, whatever its number of colours and whether it is striped, so any two can
 * be faded into each other.
 */
object Ramp {
    const val SIZE = 64

    /** Where the three colours of a three-colour palette sit, bottom to top. */
    private val THREE = floatArrayOf(0f, 0.55f, 1f)

    /** Fill [into] ([SIZE] long) from [colors], blended or as [banded] stripes. */
    fun fill(into: IntArray, colors: IntArray, banded: Boolean) {
        val n = colors.size
        for (i in 0 until SIZE) {
            val at = i / (SIZE - 1f)
            into[i] =
                if (banded || n == 1) {
                    colors[(at * n).toInt().coerceIn(0, n - 1)]
                } else {
                    var segment = 0
                    while (segment < n - 2 && at > position(segment + 1, n)) segment++
                    val from = position(segment, n)
                    val to = position(segment + 1, n)
                    ColorUtils.blendARGB(
                        colors[segment],
                        colors[segment + 1],
                        ((at - from) / (to - from)).coerceIn(0f, 1f),
                    )
                }
        }
    }

    private fun position(index: Int, count: Int) =
        if (count == 3) THREE[index] else index / (count - 1f)
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
