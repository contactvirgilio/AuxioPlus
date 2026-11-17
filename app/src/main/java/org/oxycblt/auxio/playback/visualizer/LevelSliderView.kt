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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A slim vertical level bar for the brightness and volume gestures. It looks like the song pill: a
 * black track with a thin border and a dark grey fill that rises with the level, a percentage on
 * top and a small icon at the bottom.
 */
class LevelSliderView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C1C1C.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3B3B3B.toInt() }
    private val border =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = 0x55FFFFFF
        }
    private val text =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12 * density
            textAlign = Paint.Align.CENTER
        }
    private val icon =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 1.6f * density
            strokeCap = Paint.Cap.ROUND
        }
    private val bar = RectF()
    private val clip = Path()
    private val speaker = Path()
    private var level = 0f
    private var brightness = true

    fun show(level: Float, brightness: Boolean) {
        this.level = level.coerceIn(0f, 1f)
        this.brightness = brightness
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val top = 22 * density
        bar.set(density, top, width - density, height - density)
        val r = bar.width() / 2
        canvas.drawRoundRect(bar, r, r, track)
        clip.rewind()
        clip.addRoundRect(bar, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(bar.left, bar.bottom - bar.height() * level, bar.right, bar.bottom, fill)
        canvas.restore()
        canvas.drawRoundRect(bar, r, r, border)
        canvas.drawText(
            "${(level * 100).roundToInt()}%",
            width / 2f,
            top - 6 * density,
            text,
        )
        val cx = width / 2f
        val cy = bar.bottom - 18 * density
        if (brightness) {
            icon.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, 3.5f * density, icon)
            icon.style = Paint.Style.STROKE
            for (i in 0 until 8) {
                val a = i * Math.PI / 4
                canvas.drawLine(
                    cx + (6f * density * cos(a)).toFloat(),
                    cy + (6f * density * sin(a)).toFloat(),
                    cx + (8f * density * cos(a)).toFloat(),
                    cy + (8f * density * sin(a)).toFloat(),
                    icon,
                )
            }
        } else {
            icon.style = Paint.Style.FILL
            val d = density
            speaker.rewind()
            speaker.moveTo(cx - 7 * d, cy - 2.5f * d)
            speaker.lineTo(cx - 3 * d, cy - 2.5f * d)
            speaker.lineTo(cx + 1 * d, cy - 6 * d)
            speaker.lineTo(cx + 1 * d, cy + 6 * d)
            speaker.lineTo(cx - 3 * d, cy + 2.5f * d)
            speaker.lineTo(cx - 7 * d, cy + 2.5f * d)
            speaker.close()
            canvas.drawPath(speaker, icon)
            if (level > 0f) {
                icon.style = Paint.Style.STROKE
                canvas.drawArc(cx - 3 * d, cy - 4 * d, cx + 5 * d, cy + 4 * d, -45f, 90f, false, icon)
            }
        }
    }
}
