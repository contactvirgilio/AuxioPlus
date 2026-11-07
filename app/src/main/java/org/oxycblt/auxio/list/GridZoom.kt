/*
 * Copyright (c) 2026 Auxio Project
 * GridZoom.kt is part of Auxio.
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

package org.oxycblt.auxio.list

import android.content.Context
import android.content.SharedPreferences
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView

/** How a list of albums or artists is laid out. */
enum class GridLevel(
    /** Roughly how wide an item is, so the number of columns follows the screen. 0 is a list. */
    val itemWidthDp: Int
) {
    /** One row per item. */
    LIST(0),
    /** A grid of covers with names. */
    TILES(170),
    /** A denser grid of covers with short names. */
    SMALL(105),
}

/**
 * Lets the user pinch a list to change its [GridLevel]: pinching in shows more, smaller items and
 * pinching out goes back towards the list. The level is remembered per list.
 *
 * @param preferences Where the level is kept.
 * @param key The preference key to keep this list's level under.
 * @param default The level to use until the user changes it.
 * @param onChange Called with the new level after a pinch.
 */
class GridZoom(
    context: Context,
    private val preferences: SharedPreferences,
    private val key: String,
    private val default: GridLevel,
    private val onChange: (GridLevel) -> Unit,
) {
    /** The current level. */
    var level: GridLevel =
        GridLevel.entries.getOrNull(preferences.getInt(key, default.ordinal)) ?: default
        private set

    private var recycler: RecyclerView? = null
    private var accumulated = 1f
    private val detector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    accumulated = 1f
                    // The pinch belongs to the list, not to the pager or sheets around it.
                    recycler?.parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    accumulated *= detector.scaleFactor
                    when {
                        accumulated < PINCH_IN -> step(+1)
                        accumulated > PINCH_OUT -> step(-1)
                    }
                    return true
                }
            },
        )

    fun attach(recycler: RecyclerView) {
        this.recycler = recycler
        recycler.addOnItemTouchListener(
            object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    detector.onTouchEvent(e)
                    return detector.isInProgress
                }

                override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                    detector.onTouchEvent(e)
                }
            }
        )
    }

    private fun step(delta: Int) {
        accumulated = 1f
        val next = GridLevel.entries.getOrNull(level.ordinal + delta) ?: return
        level = next
        preferences.edit().putInt(key, next.ordinal).apply()
        recycler?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onChange(next)
    }

    private companion object {
        const val PINCH_IN = 0.8f
        const val PINCH_OUT = 1.25f
    }
}
