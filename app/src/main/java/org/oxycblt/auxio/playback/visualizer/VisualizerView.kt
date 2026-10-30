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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.google.android.material.R as MR
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.util.getAttrColorCompat

/**
 * Draws the audio that is currently playing as an oscilloscope, a spectrogram or a bar spectrum,
 * in place of the album cover. It shows and hides itself according to [UISettings.showVisualizer],
 * and only does any work while it is actually on screen.
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

    // --- OSCILLOSCOPE ---
    private val scopePath = Path()
    private val scopePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = primaryColor
        }

    // --- SPECTRUM ---
    private val barLevels = FloatArray(MAX_BARS)
    private val peakLevels = FloatArray(MAX_BARS)
    private val peakHolds = FloatArray(MAX_BARS)
    private val peakSpeeds = FloatArray(MAX_BARS)
    private val barRect = RectF()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primaryColor }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }

    // --- SPECTROGRAM ---
    private var spectrogram: Bitmap? = null
    private var spectrogramColumn = IntArray(0)
    private var spectrogramX = 0
    private var pendingColumns = 0f
    private val spectrogramPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val spectrogramSrc = Rect()
    private val spectrogramDst = RectF()
    private val colorMap = IntArray(256) { i -> colorFor(i / 255f) }

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Rebuilt at the new size on the next frame.
        spectrogram?.recycle()
        spectrogram = null
    }

    private fun update() {
        val newMode = uiSettings.visualizerMode
        if (newMode != mode) {
            mode = newMode
            reset()
        }
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
        spectrogram?.eraseColor(backgroundColor)
        spectrogramX = 0
        pendingColumns = 0f
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

        // When paused, act on silence so everything settles down instead of freezing.
        val playing = playbackManager.progression.isPlaying
        if (!playing || !buffer.read(samples, FFT_SIZE)) {
            samples.fill(0f)
        }

        when (mode) {
            VisualizerMode.OSCILLOSCOPE -> {}
            VisualizerMode.SPECTRUM -> updateSpectrum(dt)
            VisualizerMode.SPECTROGRAM -> if (playing) updateSpectrogram(dt)
        }

        invalidate()
        postOnAnimation(frame)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(backgroundColor)
        when (mode) {
            VisualizerMode.OSCILLOSCOPE -> drawOscilloscope(canvas)
            VisualizerMode.SPECTRUM -> drawSpectrum(canvas)
            VisualizerMode.SPECTROGRAM -> drawSpectrogram(canvas)
        }
    }

    // --- OSCILLOSCOPE ---

    private fun drawOscilloscope(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val mid = h / 2
        // Start the trace on a rising zero crossing so a steady tone stands still instead of
        // jittering from frame to frame.
        val searchEnd = FFT_SIZE - SCOPE_SAMPLES
        var start = searchEnd
        for (i in searchEnd downTo 1) {
            if (samples[i - 1] < 0f && samples[i] >= 0f) {
                start = i
                break
            }
        }
        if (start == searchEnd) start = FFT_SIZE - SCOPE_SAMPLES

        val amplitude = h * 0.45f
        scopePath.rewind()
        for (i in 0 until SCOPE_SAMPLES) {
            val x = i * w / (SCOPE_SAMPLES - 1)
            val y = mid - samples[start + i].coerceIn(-1f, 1f) * amplitude
            if (i == 0) scopePath.moveTo(x, y) else scopePath.lineTo(x, y)
        }
        canvas.drawPath(scopePath, scopePaint)
    }

    // --- SPECTRUM ---

    private val barCount: Int
        get() = (width / (BAR_SPACING_DP * density)).toInt().coerceIn(MIN_BARS, MAX_BARS)

    private fun updateSpectrum(dt: Float) {
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

    private fun drawSpectrum(canvas: Canvas) {
        val count = barCount
        val w = width.toFloat()
        val h = height.toFloat()
        val slot = w / count
        val gap = max(1f, slot * 0.2f)
        val peakHeight = max(2f, 3f * density)
        for (bar in 0 until count) {
            val left = bar * slot + gap / 2
            val right = (bar + 1) * slot - gap / 2
            val top = h - barLevels[bar] * h
            if (barLevels[bar] > 0f) {
                barRect.set(left, top, right, h)
                canvas.drawRect(barRect, barPaint)
            }
            val peakTop = (h - peakLevels[bar] * h - peakHeight).coerceAtLeast(0f)
            barRect.set(left, peakTop, right, peakTop + peakHeight)
            canvas.drawRect(barRect, peakPaint)
        }
    }

    // --- SPECTROGRAM ---

    private fun updateSpectrogram(dt: Float) {
        val bitmap = spectrogram ?: createSpectrogram() ?: return
        // Show about the last few seconds across the width of the view.
        pendingColumns += dt * bitmap.width / SPECTROGRAM_SECONDS
        val columns = pendingColumns.toInt()
        if (columns == 0) return
        pendingColumns -= columns

        spectrum.analyze(samples)
        val rows = bitmap.height
        val maxFrequency = min(Spectrum.MAX_FREQUENCY, buffer.sampleRate / 2f)
        for (row in 0 until rows) {
            // Low frequencies at the bottom.
            val fraction = (rows - 1 - row).toFloat() / rows
            val low = Spectrum.logFrequency(fraction, maxFrequency)
            val high = Spectrum.logFrequency(fraction + 1f / rows, maxFrequency)
            val level = levelFor(spectrum.peakDecibels(low, high, buffer.sampleRate))
            spectrogramColumn[row] = colorMap[(level * 255).roundToInt().coerceIn(0, 255)]
        }
        repeat(min(columns, bitmap.width)) {
            bitmap.setPixels(spectrogramColumn, 0, 1, spectrogramX, 0, 1, rows)
            spectrogramX = (spectrogramX + 1) % bitmap.width
        }
    }

    private fun createSpectrogram(): Bitmap? {
        if (width == 0 || height == 0) return null
        // Half resolution is plenty and keeps the per-frame work small.
        val bitmap =
            Bitmap.createBitmap(
                max(1, width / 2),
                max(1, height / 2),
                Bitmap.Config.ARGB_8888,
            )
        bitmap.eraseColor(backgroundColor)
        spectrogram = bitmap
        spectrogramColumn = IntArray(bitmap.height)
        spectrogramX = 0
        return bitmap
    }

    private fun drawSpectrogram(canvas: Canvas) {
        val bitmap = spectrogram ?: return
        // The bitmap is a circular buffer of columns: the oldest column is at spectrogramX.
        // Draw from there to the end on the left, then the start up to there on the right.
        val scale = width.toFloat() / bitmap.width
        val older = bitmap.width - spectrogramX
        spectrogramSrc.set(spectrogramX, 0, bitmap.width, bitmap.height)
        spectrogramDst.set(0f, 0f, older * scale, height.toFloat())
        canvas.drawBitmap(bitmap, spectrogramSrc, spectrogramDst, spectrogramPaint)
        if (spectrogramX > 0) {
            spectrogramSrc.set(0, 0, spectrogramX, bitmap.height)
            spectrogramDst.set(older * scale, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(bitmap, spectrogramSrc, spectrogramDst, spectrogramPaint)
        }
    }

    // --- SHARED ---

    /** Map a loudness in decibels to a 0 to 1 level, with [FLOOR_DB] and below being 0. */
    private fun levelFor(decibels: Float) = ((decibels - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)

    /** The spectrogram colour for a 0 to 1 level: background, to primary, to accent, to white. */
    private fun colorFor(level: Float): Int =
        when {
            level < 0.6f -> ColorUtils.blendARGB(backgroundColor, primaryColor, level / 0.6f)
            level < 0.85f -> ColorUtils.blendARGB(primaryColor, accentColor, (level - 0.6f) / 0.25f)
            else -> ColorUtils.blendARGB(accentColor, WHITE, (level - 0.85f) / 0.15f * 0.6f)
        }

    private companion object {
        const val FFT_SIZE = 4096
        const val SCOPE_SAMPLES = 1024
        const val FLOOR_DB = -70f
        const val MIN_BARS = 16
        const val MAX_BARS = 64
        const val BAR_SPACING_DP = 7f
        const val BAR_FALL_PER_SECOND = 1.6f
        const val PEAK_HOLD_SECONDS = 0.35f
        const val PEAK_GRAVITY = 3f
        const val SPECTROGRAM_SECONDS = 5f
        const val MAX_FRAME_TIME = 0.1f
        const val IDLE_CHECK_MS = 250L
        const val FADE_MS = 150L
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
