/*
 * Copyright (c) 2026 Auxio Project
 * VisualizerView.kt is part of Auxio.
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
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.google.android.material.R as MR
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.util.getAttrColorCompat

/**
 * Draws the playing audio as a bar spectrum in place of the album cover, either in the theme colour
 * or with a three-colour gradient that fades between palettes on an interval. It shows and hides
 * itself according to [UISettings.showVisualizer], and only does any work while it is on screen.
 */
@AndroidEntryPoint
class VisualizerView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    View(context, attrs, defStyleAttr), UISettings.Listener {
    @Inject lateinit var uiSettings: UISettings
    @Inject lateinit var buffer: VisualizerBuffer
    @Inject lateinit var playbackManager: PlaybackStateManager

    private var mode = VisualizerMode.SPECTRUM
    private var intervalMs = 10_000L
    private var acquired = false
    private var running = false
    private var lastFrameNanos = 0L
    private val visibleRect = Rect()

    private val spectrum = Spectrum(FFT_SIZE)
    private val samples = FloatArray(FFT_SIZE)

    private val backgroundColor =
        context.getAttrColorCompat(MR.attr.colorSurfaceContainerHigh).defaultColor
    private val primaryColor =
        context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary).defaultColor
    private val accentColor = context.getAttrColorCompat(MR.attr.colorTertiary).defaultColor
    private val density = resources.displayMetrics.density

    private val barLevels = FloatArray(MAX_BARS)
    private val peakLevels = FloatArray(MAX_BARS)
    private val peakHolds = FloatArray(MAX_BARS)
    private val peakSpeeds = FloatArray(MAX_BARS)
    private val barRect = RectF()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cycleColors = IntArray(3)
    private val gradientStops = floatArrayOf(0f, 0.55f, 1f)

    private val frame = Runnable { onFrame() }

    init {
        // Stay hidden until the setting says otherwise.
        isVisible = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        uiSettings.registerListener(this)
        update()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        uiSettings.unregisterListener(this)
        stop()
    }

    override fun onVisualizerChanged() {
        update()
    }

    private fun update() {
        val newMode = uiSettings.visualizerMode
        if (newMode != mode) {
            mode = newMode
            reset()
        }
        intervalMs = uiSettings.visualizerIntervalSeconds * 1000L
        val show = uiSettings.showVisualizer
        if (show != isVisible) {
            if (show) {
                alpha = 0f
                isVisible = true
                animate().alpha(1f).setDuration(FADE_MS).start()
            } else {
                animate().alpha(0f).setDuration(FADE_MS).withEndAction { isVisible = false }.start()
            }
        }
        if (show && isAttachedToWindow) start() else stop()
    }

    private fun start() {
        if (running) return
        running = true
        if (!acquired) {
            buffer.acquire()
            acquired = true
        }
        lastFrameNanos = 0L
        removeCallbacks(frame)
        postOnAnimation(frame)
    }

    private fun stop() {
        running = false
        removeCallbacks(frame)
        if (acquired) {
            buffer.release()
            acquired = false
        }
    }

    private fun reset() {
        barLevels.fill(0f)
        peakLevels.fill(0f)
        peakHolds.fill(0f)
        peakSpeeds.fill(0f)
    }

    private fun onFrame() {
        if (!running) return
        // Pages next to the current one in the cover pager are attached but off screen. Don't
        // spend any time on them, just check back now and then.
        if (!isShown || !getGlobalVisibleRect(visibleRect) || width == 0 || height == 0) {
            lastFrameNanos = 0L
            postDelayed(frame, IDLE_CHECK_MS)
            return
        }

        val now = System.nanoTime()
        val dt =
            if (lastFrameNanos == 0L) 1f / 60f
            else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, MAX_FRAME_TIME)
        lastFrameNanos = now

        // When paused, act on silence so the bars settle down instead of freezing.
        if (!playbackManager.progression.isPlaying || !buffer.read(samples, FFT_SIZE)) {
            samples.fill(0f)
        }
        updateBars(dt)

        invalidate()
        postOnAnimation(frame)
    }

    private val barCount: Int
        get() = (width / (BAR_SPACING_DP * density)).toInt().coerceIn(MIN_BARS, MAX_BARS)

    private fun updateBars(dt: Float) {
        spectrum.analyze(samples)
        val count = barCount
        val maxFrequency = min(Spectrum.MAX_FREQUENCY, buffer.sampleRate / 2f)
        for (bar in 0 until count) {
            val low = Spectrum.logFrequency(bar.toFloat() / count, maxFrequency)
            val high = Spectrum.logFrequency((bar + 1f) / count, maxFrequency)
            val target = levelFor(spectrum.peakDecibels(low, high, buffer.sampleRate))

            // Jump up straight away, fall back gradually, like foobar2000.
            barLevels[bar] =
                if (target > barLevels[bar]) target
                else max(target, barLevels[bar] - BAR_FALL_PER_SECOND * dt)

            // Peak caps hold for a moment, then drop with gravity.
            if (barLevels[bar] >= peakLevels[bar]) {
                peakLevels[bar] = barLevels[bar]
                peakHolds[bar] = PEAK_HOLD_SECONDS
                peakSpeeds[bar] = 0f
            } else if (peakHolds[bar] > 0f) {
                peakHolds[bar] -= dt
            } else {
                peakSpeeds[bar] += PEAK_GRAVITY * dt
                peakLevels[bar] = max(barLevels[bar], peakLevels[bar] - peakSpeeds[bar] * dt)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(backgroundColor)
        val h = height.toFloat()
        when (mode) {
            VisualizerMode.SPECTRUM -> {
                barPaint.shader = null
                barPaint.color = primaryColor
                peakPaint.color = accentColor
            }
            VisualizerMode.SPECTRUM_CYCLE -> {
                updateCycleColors()
                // One gradient over the full height, so only loud bars reach the top colour.
                barPaint.shader =
                    LinearGradient(0f, h, 0f, 0f, cycleColors, gradientStops, Shader.TileMode.CLAMP)
                peakPaint.color = cycleColors[2]
            }
        }

        val count = barCount
        val slot = width.toFloat() / count
        val gap = max(1f, slot * 0.2f)
        val peakHeight = max(2f, 3f * density)
        for (bar in 0 until count) {
            val left = bar * slot + gap / 2
            val right = (bar + 1) * slot - gap / 2
            if (barLevels[bar] > 0f) {
                barRect.set(left, h - barLevels[bar] * h, right, h)
                canvas.drawRect(barRect, barPaint)
            }
            val peakTop = (h - peakLevels[bar] * h - peakHeight).coerceAtLeast(0f)
            barRect.set(left, peakTop, right, peakTop + peakHeight)
            canvas.drawRect(barRect, peakPaint)
        }
    }

    /**
     * Work out the current three gradient colours: each palette shows for the interval, then fades
     * into the next one.
     */
    private fun updateCycleColors() {
        val interval = max(1000L, intervalMs)
        val fade = min(FADE_BETWEEN_PALETTES_MS, interval / 2)
        val position = SystemClock.elapsedRealtime() % (interval * PALETTES.size)
        val index = (position / interval).toInt()
        val within = position - index * interval
        val t =
            if (within > interval - fade) (within - (interval - fade)).toFloat() / fade else 0f
        val from = PALETTES[index]
        val to = PALETTES[(index + 1) % PALETTES.size]
        for (i in 0 until 3) {
            cycleColors[i] = ColorUtils.blendARGB(from[i], to[i], t)
        }
    }

    /** Map a loudness in decibels to a 0 to 1 level, with [FLOOR_DB] and below being 0. */
    private fun levelFor(decibels: Float) = ((decibels - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)

    private companion object {
        const val FFT_SIZE = 4096
        const val FLOOR_DB = -70f
        const val MIN_BARS = 16
        const val MAX_BARS = 64
        const val BAR_SPACING_DP = 7f
        const val BAR_FALL_PER_SECOND = 1.6f
        const val PEAK_HOLD_SECONDS = 0.35f
        const val PEAK_GRAVITY = 3f
        const val MAX_FRAME_TIME = 0.1f
        const val IDLE_CHECK_MS = 250L
        const val FADE_MS = 150L
        const val FADE_BETWEEN_PALETTES_MS = 1500L

        const val PALETTE_COUNT = 50

        /** Step between palettes around the colour wheel, so neighbours are always far apart. */
        const val GOLDEN_ANGLE = 137.508f

        /**
         * 50 palettes of three colours each, bottom to top. Each one starts from a different hue
         * and sweeps around the colour wheel by a varying amount and direction, getting lighter
         * towards the top.
         */
        val PALETTES =
            Array(PALETTE_COUNT) { i ->
                val hue = i * GOLDEN_ANGLE
                val sweep = (if (i % 2 == 0) 1f else -1f) * (30f + (i % 5) * 12f)
                val saturation = 0.7f + (i % 3) * 0.1f
                intArrayOf(
                    hsl(hue, saturation, 0.42f),
                    hsl(hue + sweep, saturation, 0.55f),
                    hsl(hue + 2 * sweep, saturation, 0.68f),
                )
            }

        private fun hsl(hue: Float, saturation: Float, lightness: Float) =
            ColorUtils.HSLToColor(floatArrayOf(((hue % 360f) + 360f) % 360f, saturation, lightness))
    }
}
