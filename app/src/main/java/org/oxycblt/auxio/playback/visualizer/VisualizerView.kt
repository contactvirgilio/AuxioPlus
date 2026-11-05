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
import java.util.Random
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
    private val random = Random()
    private var lastHue = random.nextFloat() * 360f
    private var currentPalette = IntArray(3).also { randomPalette(it) }
    private var nextPalette = IntArray(3).also { randomPalette(it) }
    private var paletteStart = SystemClock.elapsedRealtime()

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
     * Work out the current three gradient colours: each random palette shows for the interval, then
     * fades into the next one, which is only picked once it's needed.
     */
    private fun updateCycleColors() {
        val interval = max(1000L, intervalMs)
        val fade = min(FADE_BETWEEN_PALETTES_MS, interval / 2)
        val now = SystemClock.elapsedRealtime()
        while (now - paletteStart >= interval) {
            paletteStart += interval
            val done = currentPalette
            currentPalette = nextPalette
            nextPalette = done
            randomPalette(nextPalette)
        }
        val within = now - paletteStart
        val t = if (within > interval - fade) (within - (interval - fade)).toFloat() / fade else 0f
        for (i in 0 until 3) {
            cycleColors[i] = ColorUtils.blendARGB(currentPalette[i], nextPalette[i], t)
        }
    }

    /**
     * Fill [into] with a brand new random palette of three colours, bottom to top. The hue always
     * jumps well away from the previous palette so that two in a row never look alike.
     */
    private fun randomPalette(into: IntArray) {
        if (random.nextFloat() < RARE_CHANCE) {
            RARE_PALETTES[random.nextInt(RARE_PALETTES.size)].copyInto(into)
            return
        }
        val hue: Float
        val sweep: Float
        val saturation: Float
        val dark: Float
        val lift: Float
        if (random.nextFloat() < EARTHY_CHANCE) {
            // Brown is just dark, muted orange, which the bright palettes never reach.
            hue = 15f + random.nextFloat() * 30f
            sweep = (random.nextFloat() - 0.5f) * 30f
            saturation = 0.4f + random.nextFloat() * 0.3f
            dark = 0.2f + random.nextFloat() * 0.08f
            lift = 0.09f + random.nextFloat() * 0.05f
        } else {
            hue = (lastHue + MIN_HUE_JUMP + random.nextFloat() * (360f - 2 * MIN_HUE_JUMP)) % 360f
            // From nearly the same colour all the way to opposites on the colour wheel.
            sweep = (if (random.nextBoolean()) 1f else -1f) * (15f + random.nextFloat() * 90f)
            saturation = 0.5f + random.nextFloat() * 0.45f
            dark = 0.28f + random.nextFloat() * 0.2f
            lift = 0.08f + random.nextFloat() * 0.1f
        }
        lastHue = hue
        for (i in 0 until 3) {
            // Every stop gets a little of its own saturation and lightness, so palettes differ in
            // more than just their hue.
            val s = (saturation + (random.nextFloat() - 0.5f) * 0.2f).coerceIn(0.3f, 1f)
            val l = (dark + i * lift + (random.nextFloat() - 0.5f) * 0.06f).coerceIn(0.15f, 0.8f)
            into[i] = hsl(hue + i * sweep, s, l)
        }
        // Half the time run it light to dark instead of dark to light.
        if (random.nextBoolean()) {
            val first = into[0]
            into[0] = into[2]
            into[2] = first
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

        const val MIN_HUE_JUMP = 60f

        /** How often a palette is one of the hand-picked [RARE_PALETTES]: about 1 in 1000. */
        const val RARE_CHANCE = 0.001f

        /**
         * Hand-picked palettes, bottom to top, that only turn up now and then. They don't follow
         * the rules of the random ones: some are two-tone, some clash on purpose.
         */
        val RARE_PALETTES =
            arrayOf(
                // Sunset.
                intArrayOf(0xFF2B1055.toInt(), 0xFFD53369.toInt(), 0xFFFFB347.toInt()),
                // Vaporwave.
                intArrayOf(0xFFFF71CE.toInt(), 0xFFB967FF.toInt(), 0xFF01CDFE.toInt()),
                // Green, red and gold, like a certain flag.
                intArrayOf(0xFF006600.toInt(), 0xFFD7141A.toInt(), 0xFFFFCC00.toInt()),
                // Aurora.
                intArrayOf(0xFF0B6E4F.toInt(), 0xFF08D9D6.toInt(), 0xFFB8F2E6.toInt()),
                // Ember.
                intArrayOf(0xFF1A0000.toInt(), 0xFFC1121F.toInt(), 0xFFFFB703.toInt()),
                // Two-tone: hot pink with a lime tip.
                intArrayOf(0xFFFF1493.toInt(), 0xFFFF1493.toInt(), 0xFFB6FF00.toInt()),
                // Two-tone: blood red into ice.
                intArrayOf(0xFF8B0000.toInt(), 0xFF8B0000.toInt(), 0xFFA7E8FF.toInt()),
                // Two-tone: deep purple with acid green on top.
                intArrayOf(0xFF4B0082.toInt(), 0xFF4B0082.toInt(), 0xFF39FF14.toInt()),
                // Clash: orange, teal, orange.
                intArrayOf(0xFFFF6A00.toInt(), 0xFF00B3B3.toInt(), 0xFFFF6A00.toInt()),
                // Black and white, no colour at all.
                intArrayOf(0xFF303030.toInt(), 0xFF9E9E9E.toInt(), 0xFFFFFFFF.toInt()),
                // Pastel sweets: bubblegum, mint, lemon.
                intArrayOf(0xFFFFB3D9.toInt(), 0xFFB3FFE6.toInt(), 0xFFFFF7A8.toInt()),
            )

        /** How often a palette is a dark, muted brown instead of a bright colour. */
        const val EARTHY_CHANCE = 0.18f

        private fun hsl(hue: Float, saturation: Float, lightness: Float) =
            ColorUtils.HSLToColor(floatArrayOf(((hue % 360f) + 360f) % 360f, saturation, lightness))
    }
}
