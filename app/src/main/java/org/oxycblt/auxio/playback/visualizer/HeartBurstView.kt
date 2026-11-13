/*
 * Copyright (c) 2026 Auxio Project
 * HeartBurstView.kt is part of Auxio.
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

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.PathParser

/**
 * The heart of the full screen visualizer: it pops in when a song is added to the favorites and
 * breaks in two when it is removed. The heart takes the top middle of this view; the rest is room
 * for the halves to fall into.
 */
class HeartBurstView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    private val source = PathParser.createPathFromPathData(HEART)
    private val heart = Path()
    private val leftHalf = Path()
    private val rightHalf = Path()
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF0527A.toInt() }
    private var size = 0f
    private var left = 0f
    private var progress = 0f
    private var breaking = false
    private var animator: ValueAnimator? = null

    /** A heart pops in, stays for a moment and fades. */
    fun pop() = play(false)

    /** A heart cracks down the middle and the halves fall away. */
    fun breakApart() = play(true)

    private fun play(breaking: Boolean) {
        this.breaking = breaking
        animator?.cancel()
        animator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DURATION_MS
                interpolator = LinearInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        size = w / 2f
        left = (w - size) / 2f
        matrix.reset()
        matrix.setScale(size / 24f, size / 24f)
        matrix.postTranslate(left, 0f)
        source.transform(matrix, heart)
        // The crack zigzags down the middle.
        fun x(fraction: Float) = left + size * fraction
        fun y(fraction: Float) = size * fraction
        leftHalf.reset()
        leftHalf.moveTo(x(0f), y(0f))
        leftHalf.lineTo(x(0.5f), y(0f))
        leftHalf.lineTo(x(0.58f), y(0.3f))
        leftHalf.lineTo(x(0.44f), y(0.48f))
        leftHalf.lineTo(x(0.56f), y(0.66f))
        leftHalf.lineTo(x(0.5f), y(1f))
        leftHalf.lineTo(x(0f), y(1f))
        leftHalf.close()
        rightHalf.reset()
        rightHalf.moveTo(x(0.5f), y(0f))
        rightHalf.lineTo(x(1f), y(0f))
        rightHalf.lineTo(x(1f), y(1f))
        rightHalf.lineTo(x(0.5f), y(1f))
        rightHalf.lineTo(x(0.56f), y(0.66f))
        rightHalf.lineTo(x(0.44f), y(0.48f))
        rightHalf.lineTo(x(0.58f), y(0.3f))
        rightHalf.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = progress
        if (animator == null || p <= 0f || p >= 1f) return
        val centerX = left + size / 2f
        if (!breaking) {
            val scale =
                when {
                    p < 0.25f -> 0.3f + 0.95f * (p / 0.25f)
                    p < 0.4f -> 1.25f - 0.25f * ((p - 0.25f) / 0.15f)
                    p < 0.75f -> 1f
                    else -> 1f + 0.1f * ((p - 0.75f) / 0.25f)
                }
            val alpha =
                when {
                    p < 0.25f -> p / 0.25f
                    p < 0.75f -> 1f
                    else -> 1f - (p - 0.75f) / 0.25f
                }
            paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.scale(scale, scale, centerX, size / 2f)
            canvas.drawPath(heart, paint)
            canvas.restore()
            return
        }
        // First the crack opens a little, then the halves tip over and drop.
        val fall = if (p < 0.25f) 0f else (p - 0.25f) / 0.75f
        val angle = if (p < 0.25f) 3f * (p / 0.25f) else 3f + 35f * fall
        paint.alpha = ((1f - fall) * 255).toInt().coerceIn(0, 255)
        for (side in intArrayOf(-1, 1)) {
            canvas.save()
            canvas.translate(side * size * (0.015f + 0.25f * fall), size * 0.65f * fall)
            canvas.rotate(side * angle, centerX, size * 0.9f)
            canvas.clipPath(if (side < 0) leftHalf else rightHalf)
            canvas.drawPath(heart, paint)
            canvas.restore()
        }
    }

    private companion object {
        const val DURATION_MS = 1300L
        const val HEART =
            "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 " +
                "4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 " +
                "-8.55,11.54L12,21.35z"
    }
}
