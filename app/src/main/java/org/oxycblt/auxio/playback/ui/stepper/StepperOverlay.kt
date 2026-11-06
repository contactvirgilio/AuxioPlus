/*
 * Copyright (c) 2025 Auxio Project
 * StepperOverlay.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.ui.stepper

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.playback.visualizer.VisualizerActivity
import org.oxycblt.auxio.ui.UISettings

enum class Direction {
    FORWARDS,
    BACKWARDS,
}

/**
 * Sits on top of the album cover and handles taps: one tap switches between the cover and the
 * visualizer, a double tap opens the visualizer on the full screen.
 */
@AndroidEntryPoint
class StepperOverlay(context: Context, attrs: AttributeSet?) :
    View(context, attrs), GestureDetector.OnDoubleTapListener {
    @Inject lateinit var uiSettings: UISettings

    private val gestureDetector =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
            },
        )

    init {
        gestureDetector.setOnDoubleTapListener(this)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        uiSettings.showVisualizer = !uiSettings.showVisualizer
        return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        // The full screen page is left again with the back gesture.
        context.startActivity(Intent(context, VisualizerActivity::class.java))
        return true
    }

    override fun onDoubleTapEvent(e: MotionEvent) = false
}
