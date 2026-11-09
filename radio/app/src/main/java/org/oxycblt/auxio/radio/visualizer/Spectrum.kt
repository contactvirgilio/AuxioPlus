package org.oxycblt.auxio.radio.visualizer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a window of audio samples into per-frequency loudness, using a Hann window and a radix-2
 * FFT.
 *
 * @param size The number of samples analysed at once. Must be a power of two.
 */
class Spectrum(val size: Int) {
    private val window =
        FloatArray(size) { i -> (0.5 - 0.5 * cos(2 * PI * i / (size - 1))).toFloat() }
    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val cosTable = FloatArray(size / 2) { i -> cos(2 * PI * i / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { i -> sin(2 * PI * i / size).toFloat() }
    private val bitReversed = IntArray(size)

    /** Loudness of each frequency bin from the last [analyze], in decibels (0 = full scale). */
    val decibels = FloatArray(size / 2)

    init {
        require(size >= 2 && size and (size - 1) == 0) { "Size must be a power of two" }
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) {
            bitReversed[i] = Integer.reverse(i) ushr (32 - bits)
        }
    }

    /**
     * Analyse [samples] (at least [size] long) and fill [decibels].
     *
     * @param samples Mono samples in the range -1 to 1.
     */
    fun analyze(samples: FloatArray) {
        for (i in 0 until size) {
            re[bitReversed[i]] = samples[i] * window[i]
            im[bitReversed[i]] = 0f
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = -sinTable[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = wr * re[b] - wi * im[b]
                    val ti = wr * im[b] + wi * re[b]
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += half * 2
            }
            half *= 2
        }
        // A full-scale sine wave peaks at size / 4 once windowed, so scale to that.
        val scale = 4f / size
        for (i in 0 until size / 2) {
            val magnitude = sqrt(re[i] * re[i] + im[i] * im[i]) * scale
            decibels[i] = 20f * log10(max(magnitude, MIN_MAGNITUDE))
        }
    }

    /**
     * The loudest level in the frequency range [low] to [high] Hz, in decibels.
     *
     * @param sampleRate The sample rate of the analysed audio.
     */
    fun peakDecibels(low: Float, high: Float, sampleRate: Int): Float {
        val binWidth = sampleRate.toFloat() / size
        val first = (low / binWidth).toInt().coerceIn(1, size / 2 - 1)
        val last = (high / binWidth).toInt().coerceIn(first, size / 2 - 1)
        var peak = decibels[first]
        for (i in first + 1..last) {
            if (decibels[i] > peak) peak = decibels[i]
        }
        return peak
    }

    companion object {
        private const val MIN_MAGNITUDE = 1e-7f

        /** The lowest frequency shown, in Hz. */
        const val MIN_FREQUENCY = 30f

        /** The highest frequency shown, in Hz. */
        const val MAX_FREQUENCY = 16000f

        /**
         * The frequency at [fraction] (0 to 1) of the way from [MIN_FREQUENCY] to [maxFrequency] on
         * a logarithmic scale, like the frequency axis of most spectrum analysers.
         */
        fun logFrequency(fraction: Float, maxFrequency: Float): Float {
            val lowLog = ln(MIN_FREQUENCY)
            val highLog = ln(maxFrequency)
            return exp(lowLog + (highLog - lowLog) * fraction)
        }
    }
}
