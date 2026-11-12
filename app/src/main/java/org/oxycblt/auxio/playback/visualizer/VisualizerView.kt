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
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.use
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.google.android.material.R as MR
import dagger.hilt.android.AndroidEntryPoint
import java.util.Random
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.oxycblt.auxio.R
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

    /** Whether this is the full screen one: always showing and on black. */
    private val alwaysOn =
        context.obtainStyledAttributes(attrs, R.styleable.VisualizerView).use {
            it.getBoolean(R.styleable.VisualizerView_alwaysOn, false)
        }
    private var mode = VisualizerMode.SPECTRUM
    private var intervalMs = 60_000L
    private var acquired = false
    private var running = false
    private var lastFrameNanos = 0L
    private val visibleRect = Rect()

    private val spectrum = Spectrum(FFT_SIZE)
    private val samples = FloatArray(FFT_SIZE)

    private val backgroundColor =
        if (alwaysOn) Color.BLACK
        else context.getAttrColorCompat(MR.attr.colorSurfaceContainerHigh).defaultColor
    private val primaryColor =
        context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary).defaultColor
    private val accentColor = context.getAttrColorCompat(MR.attr.colorTertiary).defaultColor
    private val density = resources.displayMetrics.density

    private val barLevels = FloatArray(MAX_BARS)
    private val peakLevels = FloatArray(MAX_BARS)
    private val peakHolds = FloatArray(MAX_BARS)
    private val peakSpeeds = FloatArray(MAX_BARS)
    private val firstBins = IntArray(MAX_BARS)
    private val lastBins = IntArray(MAX_BARS)
    private var binsForCount = -1
    private var binsForRate = -1
    private val barRect = RectF()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cycleColors = IntArray(Ramp.SIZE)
    private val gradientColors = IntArray(Ramp.SIZE)
    private var gradientHeight = -1f

    /** Called when a hand-picked palette shows up, so the full screen page can say so. */
    var onPresetShown: ((Preset) -> Unit)? = null

    private var announced: Preset? = null
    private var sparkleUntil = 0L
    private val sparkX = FloatArray(MAX_SPARKS)
    private val sparkY = FloatArray(MAX_SPARKS)
    private val sparkVx = FloatArray(MAX_SPARKS)
    private val sparkVy = FloatArray(MAX_SPARKS)
    private val sparkLife = FloatArray(MAX_SPARKS)
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var sparkDt = 1f / 60f
    private var sparkLimit = 0
    private var sparkRate = 0f

    private val frame = Runnable { onFrame() }

    init {
        // Stay hidden until the setting says otherwise.
        isVisible = alwaysOn
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        uiSettings.registerListener(this)
        update()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Nothing is drawn while the app is in the background or covered by the full screen page.
        if (visibility == VISIBLE) update() else stop()
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
        // 0 means a new colour scheme for every song.
        intervalMs = uiSettings.visualizerIntervalSeconds * 1000L
        val show = alwaysOn || uiSettings.showVisualizer
        if (show != isVisible) {
            if (show) {
                alpha = 0f
                isVisible = true
                animate().alpha(1f).setDuration(FADE_MS).start()
            } else {
                animate().alpha(0f).setDuration(FADE_MS).withEndAction { isVisible = false }.start()
            }
        }
        if (show && isAttachedToWindow && windowVisibility == VISIBLE) start() else stop()
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

    private fun announce(preset: Preset) {
        PaletteCollection.get(context).add(preset.id)
        // The harder a palette is to come across, the more it sparkles and the longer it does.
        when (preset.tier) {
            Tier.RARE -> {
                sparkLimit = 30
                sparkRate = 0.12f
                sparkleUntil = SystemClock.elapsedRealtime() + 2200L
            }
            Tier.LEGENDARY -> {
                sparkLimit = 70
                sparkRate = 0.3f
                sparkleUntil = SystemClock.elapsedRealtime() + 3200L
            }
            Tier.UNIQUE -> {
                sparkLimit = MAX_SPARKS
                sparkRate = 0.5f
                sparkleUntil = SystemClock.elapsedRealtime() + 4500L
            }
        }
        onPresetShown?.invoke(preset)
    }

    /** Throw sparks off the peak caps while a hand-picked palette is fresh. */
    private fun drawSparks(canvas: Canvas, h: Float, slot: Float, count: Int) {
        val sparkling = SystemClock.elapsedRealtime() < sparkleUntil
        val size = max(1.5f, 1.5f * density)
        for (i in 0 until MAX_SPARKS) {
            if (sparkLife[i] <= 0f) {
                if (!sparkling || i >= sparkLimit || random.nextFloat() > sparkRate) continue
                val bar = random.nextInt(count)
                sparkX[i] = bar * slot + slot / 2
                sparkY[i] = h - peakLevels[bar] * h
                sparkVx[i] = (random.nextFloat() - 0.5f) * 60f * density
                sparkVy[i] = -(20f + random.nextFloat() * 70f) * density
                sparkLife[i] = 1f
            }
            sparkX[i] += sparkVx[i] * sparkDt
            sparkY[i] += sparkVy[i] * sparkDt
            sparkVy[i] += 120f * density * sparkDt
            sparkLife[i] -= sparkDt * 1.6f
            if (sparkLife[i] <= 0f) continue
            sparkPaint.alpha = (sparkLife[i] * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(sparkX[i], sparkY[i], size, sparkPaint)
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
        sparkDt = dt
        updateBars(dt)

        invalidate()
        postOnAnimation(frame)
    }

    private val barCount: Int
        get() = (width / (BAR_SPACING_DP * density)).toInt().coerceIn(MIN_BARS, MAX_BARS)

    private fun updateBars(dt: Float) {
        spectrum.analyze(samples)
        val count = barCount
        val sampleRate = buffer.sampleRate
        // Which frequencies belong to which bar only changes with the width or the audio format.
        if (count != binsForCount || sampleRate != binsForRate) {
            binsForCount = count
            binsForRate = sampleRate
            val maxFrequency = min(Spectrum.MAX_FREQUENCY, sampleRate / 2f)
            for (bar in 0 until count) {
                val low = Spectrum.logFrequency(bar.toFloat() / count, maxFrequency)
                val high = Spectrum.logFrequency((bar + 1f) / count, maxFrequency)
                firstBins[bar] = spectrum.binFor(low, sampleRate)
                lastBins[bar] = max(firstBins[bar], spectrum.binFor(high, sampleRate))
            }
        }
        for (bar in 0 until count) {
            val target = levelFor(spectrum.peakDecibels(firstBins[bar], lastBins[bar]))

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
                // The gradient is only rebuilt when the colours or the height changed.
                if (
                    barPaint.shader == null ||
                        gradientHeight != h ||
                        !cycleColors.contentEquals(gradientColors)
                ) {
                    cycleColors.copyInto(gradientColors)
                    gradientHeight = h
                    barPaint.shader =
                        LinearGradient(
                            0f,
                            h,
                            0f,
                            0f,
                            gradientColors.copyOf(),
                            null,
                            Shader.TileMode.CLAMP,
                        )
                }
                peakPaint.color = cycleColors[Ramp.SIZE - 1]
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
        if (mode == VisualizerMode.SPECTRUM_CYCLE) drawSparks(canvas, h, slot, count)
    }

    /**
     * Work out the current three gradient colours: each random palette shows for the interval, then
     * fades into the next one, which is only picked once it's needed.
     */
    private fun updateCycleColors() {
        if (!paletteReady) {
            currentPalette = IntArray(Ramp.SIZE)
            currentPreset = randomPalette(currentPalette)
            nextPalette = IntArray(Ramp.SIZE)
            nextPreset = randomPalette(nextPalette)
            paletteStart = SystemClock.elapsedRealtime()
            paletteReady = true
        }
        val now = SystemClock.elapsedRealtime()
        val t: Float
        if (intervalMs <= 0L) {
            // Every song: hold the palette until the song changes, then fade into the next one.
            val song = playbackManager.currentSong?.uid
            if (song != lastSong) {
                val firstSong = lastSong == null
                lastSong = song
                if (!firstSong && song != null) {
                    if (songFadeStart != 0L) rotatePalettes()
                    songFadeStart = now
                }
            }
            if (songFadeStart != 0L && now - songFadeStart >= FADE_BETWEEN_PALETTES_MS) {
                rotatePalettes()
                songFadeStart = 0L
            }
            t =
                if (songFadeStart == 0L) 0f
                else (now - songFadeStart).toFloat() / FADE_BETWEEN_PALETTES_MS
        } else {
            songFadeStart = 0L
            val interval = max(1000L, intervalMs)
            val fade = min(FADE_BETWEEN_PALETTES_MS, interval / 2)
            while (now - paletteStart >= interval) {
                paletteStart += interval
                rotatePalettes()
            }
            val within = now - paletteStart
            t =
                if (within > interval - fade) (within - (interval - fade)).toFloat() / fade
                else 0f
        }
        if (t <= 0f) {
            currentPalette.copyInto(cycleColors)
        } else {
            for (i in 0 until Ramp.SIZE) {
                cycleColors[i] = ColorUtils.blendARGB(currentPalette[i], nextPalette[i], t)
            }
        }
        // A preset counts as seen once it's more than half faded in.
        val showing = if (t >= 0.5f) nextPreset else currentPreset
        if (showing !== announced) {
            announced = showing
            if (showing != null) announce(showing)
        }
    }

    /** The next palette becomes the current one, and a new random next one is picked. */
    private fun rotatePalettes() {
        val done = currentPalette
        currentPalette = nextPalette
        currentPreset = nextPreset
        nextPalette = done
        nextPreset = randomPalette(nextPalette)
    }

    /**
     * Fill [into] with a new palette as a [Ramp]. Nearly always that is a brand new random one of
     * three colours; now and then it is one of the hand-picked [Presets], which is then returned.
     * The hue of a random palette always jumps well away from the previous one so that two in a
     * row never look alike.
     */
    private fun randomPalette(into: IntArray): Preset? {
        val roll = random.nextFloat()
        val tier =
            when {
                roll < UNIQUE_CHANCE -> Tier.UNIQUE
                roll < UNIQUE_CHANCE + LEGENDARY_CHANCE -> Tier.LEGENDARY
                roll < UNIQUE_CHANCE + LEGENDARY_CHANCE + RARE_CHANCE -> Tier.RARE
                else -> null
            }
        if (tier != null) {
            val presets = Presets.of(tier)
            return presets[random.nextInt(presets.size)].also {
                Ramp.fill(into, it.colors, it.banded)
            }
        }
        // Everything is random across the whole colour wheel, with no preset colours and no
        // favourites. Brown is not special: it is dark, muted orange, which turns up as often as
        // that part of the colour space is big. A palette never starts close to the hue of the
        // previous one.
        var hue: Float
        var tries = 0
        do {
            hue = random.nextFloat() * 360f
            val distance = abs(((hue - lastHue + 540f) % 360f) - 180f)
        } while (distance < MIN_HUE_JUMP && ++tries < 50)
        // From nearly the same colour all the way to opposites on the colour wheel.
        val sweep = (if (random.nextBoolean()) 1f else -1f) * (15f + random.nextFloat() * 90f)
        val saturation = 0.35f + random.nextFloat() * 0.6f
        val dark = 0.2f + random.nextFloat() * 0.3f
        val lift = 0.08f + random.nextFloat() * 0.1f
        lastHue = hue
        val colors = randomColors
        for (i in 0 until 3) {
            // Every stop gets a little of its own saturation and lightness, so palettes differ in
            // more than just their hue.
            val s = (saturation + (random.nextFloat() - 0.5f) * 0.2f).coerceIn(0.3f, 1f)
            val l = (dark + i * lift + (random.nextFloat() - 0.5f) * 0.06f).coerceIn(0.15f, 0.8f)
            colors[i] = hsl(hue + i * sweep, s, l)
        }
        // Half the time run it light to dark instead of dark to light.
        if (random.nextBoolean()) {
            val first = colors[0]
            colors[0] = colors[2]
            colors[2] = first
        }
        Ramp.fill(into, colors, false)
        return null
    }

    /** Map a loudness in decibels to a 0 to 1 level, with [FLOOR_DB] and below being 0. */
    private fun levelFor(decibels: Float) = ((decibels - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)

    private companion object {
        // The palettes are shared by every visualizer, so the small one and the full screen one
        // always show the same colours at the same moment.
        val random = Random()
        val randomColors = IntArray(3)
        var lastHue = random.nextFloat() * 360f
        var paletteReady = false
        lateinit var currentPalette: IntArray
        lateinit var nextPalette: IntArray
        var currentPreset: Preset? = null
        var nextPreset: Preset? = null
        var paletteStart = 0L
        var lastSong: org.oxycblt.musikr.Music.UID? = null
        var songFadeStart = 0L

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

        /** How often a palette is one of the rare presets: 1 in 50. */
        const val RARE_CHANCE = 0.02f

        /** How often a palette is one of the legendary presets: 1 in 200. */
        const val LEGENDARY_CHANCE = 0.005f

        /** How often a palette is one of the unique presets: 1 in 500. */
        const val UNIQUE_CHANCE = 0.002f

        const val ANNOUNCE_MS = 2000L
        const val MAX_SPARKS = 120

        private fun hsl(hue: Float, saturation: Float, lightness: Float) =
            ColorUtils.HSLToColor(floatArrayOf(((hue % 360f) + 360f) % 360f, saturation, lightness))
    }
}
