/*
 * Copyright (c) 2026 Auxio Project
 * PalettePreviewView.kt is part of Auxio.
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
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A small stand-alone preview of one [Preset]: made-up bars moving in its colours, with the sparkle
 * of its tier when it opens. It listens to nothing and only animates while it is on screen.
 */
class PalettePreviewView(context: Context) : View(context) {
    private val ramp = IntArray(Ramp.SIZE)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val levels = FloatArray(BARS)
    private val peaks = FloatArray(BARS)
    private val sparkX = FloatArray(MAX_SPARKS)
    private val sparkY = FloatArray(MAX_SPARKS)
    private val sparkVx = FloatArray(MAX_SPARKS)
    private val sparkVy = FloatArray(MAX_SPARKS)
    private val sparkLife = FloatArray(MAX_SPARKS)
    private val random = Random()
    private val density = resources.displayMetrics.density
    private var shaderHeight = -1f
    private var sparkLimit = 0
    private var sparkRate = 0f
    private var sparkleUntil = 0L
    private var lastFrame = 0L

    /** Show [preset], starting with the sparkle of its tier. */
    fun show(preset: Preset) {
        Ramp.fill(ramp, preset.colors, preset.banded)
        barPaint.shader = null
        peakPaint.color = ramp[Ramp.SIZE - 1]
        val now = SystemClock.elapsedRealtime()
        when (preset.tier) {
            Tier.RARE -> {
                sparkLimit = 30
                sparkRate = 0.12f
                sparkleUntil = now + 2200L
            }
            Tier.LEGENDARY -> {
                sparkLimit = 70
                sparkRate = 0.3f
                sparkleUntil = now + 3200L
            }
            Tier.UNIQUE -> {
                sparkLimit = MAX_SPARKS
                sparkRate = 0.5f
                sparkleUntil = now + 4500L
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrame == 0L) 1f / 60f else min(0.1f, (now - lastFrame) / 1000f)
        lastFrame = now
        canvas.drawColor(Color.BLACK)
        if (barPaint.shader == null || shaderHeight != h) {
            shaderHeight = h
            barPaint.shader =
                LinearGradient(0f, h, 0f, 0f, ramp.copyOf(), null, Shader.TileMode.CLAMP)
        }
        val slot = w / BARS
        val gap = max(1f, slot * 0.2f)
        val peakHeight = max(2f, 3f * density)
        val time = now % 600_000L
        for (i in 0 until BARS) {
            // Two slow waves across the bars, louder on the left like most music.
            val wave =
                (sin(time / 260.0 + i * 0.5) * 0.5 + 0.5) * 0.5 +
                    (sin(time / 130.0 + i * 1.3) * 0.5 + 0.5) * 0.35
            val shape = 0.45f + (1f - i.toFloat() / BARS).pow(0.8f) * 0.7f
            val target = min(1f, wave.toFloat() * shape + 0.05f)
            levels[i] = if (target > levels[i]) target else max(0f, levels[i] - dt * 1.2f)
            peaks[i] = if (levels[i] > peaks[i]) levels[i] else max(0f, peaks[i] - dt * 0.5f)
            val left = i * slot + gap / 2
            val right = (i + 1) * slot - gap / 2
            canvas.drawRect(left, h - levels[i] * h, right, h, barPaint)
            val top = max(0f, h - peaks[i] * h - peakHeight)
            canvas.drawRect(left, top, right, top + peakHeight, peakPaint)
        }
        val sparkling = now < sparkleUntil
        val size = max(1.5f, 1.5f * density)
        for (i in 0 until MAX_SPARKS) {
            if (sparkLife[i] <= 0f) {
                if (!sparkling || i >= sparkLimit || random.nextFloat() > sparkRate) continue
                val bar = random.nextInt(BARS)
                sparkX[i] = bar * slot + slot / 2
                sparkY[i] = h - peaks[bar] * h
                sparkVx[i] = (random.nextFloat() - 0.5f) * 60f * density
                sparkVy[i] = -(20f + random.nextFloat() * 70f) * density
                sparkLife[i] = 1f
            }
            sparkX[i] += sparkVx[i] * dt
            sparkY[i] += sparkVy[i] * dt
            sparkVy[i] += 120f * density * dt
            sparkLife[i] -= dt * 1.6f
            if (sparkLife[i] <= 0f) continue
            sparkPaint.alpha = (sparkLife[i] * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(sparkX[i], sparkY[i], size, sparkPaint)
        }
        // Keep going for as long as this is being drawn; it stops by itself once it's gone.
        postInvalidateOnAnimation()
    }

    private companion object {
        const val BARS = 24
        const val MAX_SPARKS = 120
    }
}
