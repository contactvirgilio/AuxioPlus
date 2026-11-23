package org.oxycblt.auxio.radio.visualizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import java.util.Random
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import org.oxycblt.auxio.radio.NowPlaying

/**
 * A speaker grille: a grid of dots over the whole screen, corner to corner, with no frame. The
 * middle of the grille is the bass and the edge the treble, like a speaker cone, and each dot grows and lights up with its band. Colours are
 * AuxioPlus palettes, fading to a new one on each new song (or every 2.5 minutes); now and then a
 * rare, legendary or unique preset turns up and the grille sparkles. Nothing is shown in words.
 */
class SpeakerView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    View(context, attrs, defStyleAttr) {
    private val buffer = VisualizerBuffer
    private val spectrum = Spectrum(FFT_SIZE)
    private val samples = FloatArray(FFT_SIZE)
    private val density = resources.displayMetrics.density
    private var acquired = false
    private var running = false
    private var lastFrameNanos = 0L
    private val frame = Runnable { onFrame() }

    private val levels = FloatArray(BANDS)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val frameRect = RectF()

    // The grid, worked out again when the size changes.
    private var dotX = FloatArray(0)
    private var dotY = FloatArray(0)
    private var dotBand = IntArray(0)
    private var spacing = 0f

    // Palettes: the current one fades into the next.
    private val current = IntArray(Ramp.SIZE)
    private val next = IntArray(Ramp.SIZE)
    private val shown = IntArray(Ramp.SIZE)
    private var nextPreset: Preset? = null
    private var paletteReady = false
    private var paletteStart = 0L
    private var fadeStart = 0L
    private var lastSong: String? = null
    private var lastPaletteNow = 0L

    // Sparks for a fresh preset.
    private val random = Random()
    private val sparkX = FloatArray(MAX_SPARKS)
    private val sparkY = FloatArray(MAX_SPARKS)
    private val sparkVx = FloatArray(MAX_SPARKS)
    private val sparkVy = FloatArray(MAX_SPARKS)
    private val sparkLife = FloatArray(MAX_SPARKS)
    private var sparkUntil = 0L
    private var sparkLimit = 0
    private var sparkRate = 0f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        update()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        update()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    private fun update() {
        if (isAttachedToWindow && windowVisibility == VISIBLE) start() else stop()
    }

    private fun start() {
        if (running) return
        running = true
        if (!acquired) {
            buffer.acquire()
            acquired = true
        }
        lastFrameNanos = 0L
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // No frame: the grille runs from corner to corner, half a step in from each edge.
        frameRect.set(0f, 0f, w.toFloat(), h.toFloat())
        spacing = max(14f * density, min(w, h) / 15f)
        val inset = spacing / 2
        val cols = ((frameRect.width() - inset * 2) / spacing).toInt() + 1
        val rows = ((frameRect.height() - inset * 2) / spacing).toInt() + 1
        val left = frameRect.centerX() - (cols - 1) * spacing / 2
        val top = frameRect.centerY() - (rows - 1) * spacing / 2
        val cx = frameRect.centerX()
        val cy = frameRect.centerY()
        val far = hypot((cols - 1) * spacing / 2, (rows - 1) * spacing / 2)
        val n = cols * rows
        dotX = FloatArray(n)
        dotY = FloatArray(n)
        dotBand = IntArray(n)
        for (r in 0 until rows) for (c in 0 until cols) {
            val i = r * cols + c
            dotX[i] = left + c * spacing
            dotY[i] = top + r * spacing
            val d = hypot(dotX[i] - cx, dotY[i] - cy) / far
            dotBand[i] = (d * BANDS).toInt().coerceIn(0, BANDS - 1)
        }
    }

    private fun onFrame() {
        if (!running) return
        val now = System.nanoTime()
        val dt =
            if (lastFrameNanos == 0L) 1f / 60f
            else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, MAX_FRAME_TIME)
        lastFrameNanos = now
        // When stopped, act on silence so the dots settle down instead of freezing.
        if (!NowPlaying.isPlaying || !buffer.read(samples, FFT_SIZE)) samples.fill(0f)
        spectrum.analyze(samples)
        val maxFrequency = min(Spectrum.MAX_FREQUENCY, buffer.sampleRate / 2f)
        for (band in 0 until BANDS) {
            val low = Spectrum.logFrequency(band.toFloat() / BANDS, maxFrequency)
            val high = Spectrum.logFrequency((band + 1f) / BANDS, maxFrequency)
            val db = spectrum.peakDecibels(low, high, buffer.sampleRate)
            val target = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
            levels[band] = if (target > levels[band]) target else max(target, levels[band] - FALL_PER_SECOND * dt)
        }
        updatePalette()
        invalidate()
        postOnAnimation(frame)
    }

    private fun updatePalette() {
        val now = SystemClock.elapsedRealtime()
        if (!paletteReady) {
            PaletteMaker.next(current)
            nextPreset = PaletteMaker.next(next)
            paletteStart = now
            lastSong = NowPlaying.title
            paletteReady = true
        }
        // A new song, or a long time on one palette, fades into the next. The timer waits while stopped.
        val song = NowPlaying.title
        if (!NowPlaying.isPlaying && lastPaletteNow != 0L) paletteStart += now - lastPaletteNow
        lastPaletteNow = now
        if (fadeStart == 0L && (song != lastSong || now - paletteStart >= MAX_PALETTE_MS)) {
            lastSong = song
            fadeStart = now
            nextPreset?.let(::sparkle)
        }
        var t = 0f
        if (fadeStart != 0L) {
            t = ((now - fadeStart).toFloat() / FADE_MS).coerceIn(0f, 1f)
            if (t >= 1f) {
                next.copyInto(current)
                nextPreset = PaletteMaker.next(next)
                fadeStart = 0L
                paletteStart = now
                t = 0f
            }
        }
        for (i in 0 until Ramp.SIZE) shown[i] = ColorUtils.blendARGB(current[i], next[i], t)
    }

    /** The rarer the preset, the more sparks and the longer they last. */
    private fun sparkle(preset: Preset) {
        val (limit, rate, ms) =
            when (preset.tier) {
                Tier.RARE -> Triple(30, 0.12f, 2200L)
                Tier.LEGENDARY -> Triple(70, 0.3f, 3200L)
                Tier.UNIQUE -> Triple(MAX_SPARKS, 0.5f, 4500L)
            }
        sparkLimit = limit
        sparkRate = rate
        sparkUntil = SystemClock.elapsedRealtime() + FADE_MS + ms
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        if (!paletteReady) return
        val minR = spacing * 0.1f
        val maxR = spacing * 0.46f
        for (i in dotX.indices) {
            val level = levels[dotBand[i]]
            // Quiet dots stay small and dim in the palette's darkest colour.
            dotPaint.color = shown[(level * (Ramp.SIZE - 1)).toInt()]
            dotPaint.alpha = (70 + 185 * min(1f, level * 1.4f)).toInt()
            canvas.drawCircle(dotX[i], dotY[i], minR + (maxR - minR) * level, dotPaint)
        }
        drawSparks(canvas)
    }

    private fun drawSparks(canvas: Canvas) {
        val dt = 1f / 60f
        val sparkling = SystemClock.elapsedRealtime() < sparkUntil
        val size = 1.5f * density
        sparkPaint.color = shown[Ramp.SIZE - 1]
        for (i in 0 until MAX_SPARKS) {
            if (sparkLife[i] <= 0f) {
                if (!sparkling || i >= sparkLimit || dotX.isEmpty() || random.nextFloat() > sparkRate) continue
                val dot = random.nextInt(dotX.size)
                sparkX[i] = dotX[dot]
                sparkY[i] = dotY[dot]
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
    }

    private companion object {
        const val FFT_SIZE = 4096
        const val FLOOR_DB = -70f
        const val BANDS = 14
        const val FALL_PER_SECOND = 1.6f
        const val MAX_FRAME_TIME = 0.1f
        const val MAX_PALETTE_MS = 150_000L
        const val FADE_MS = 1500L
        const val MAX_SPARKS = 120
    }
}
