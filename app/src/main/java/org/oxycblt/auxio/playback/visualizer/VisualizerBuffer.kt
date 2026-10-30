/*
 * Copyright (c) 2026 Auxio Project
 * VisualizerBuffer.kt is part of Auxio.
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

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A ring buffer of the most recent audio sent to the audio output, mixed down to mono, that the
 * visualizer reads from.
 *
 * Audio reaches this buffer well before it is heard, since the audio output keeps its own buffer
 * of a few hundred milliseconds. [read] compensates for that so the visuals line up with what is
 * actually playing.
 */
@Singleton
class VisualizerBuffer @Inject constructor() {
    private val lock = Any()
    private val ring = FloatArray(RING_SIZE)
    private var written = 0L
    private var lastWriteNanos = 0L
    private var delayFrames = 0
    private val users = AtomicInteger(0)

    /** The sample rate of the audio in the buffer. */
    @Volatile
    var sampleRate = 44100
        private set

    /** Whether anything is currently showing a visualizer, and so wants audio to be collected. */
    val isWanted: Boolean
        get() = users.get() > 0

    /** Start collecting audio for a visualizer. Must be balanced with a call to [release]. */
    fun acquire() {
        users.incrementAndGet()
    }

    /** Stop collecting audio for a visualizer that previously called [acquire]. */
    fun release() {
        if (users.decrementAndGet() <= 0) {
            users.set(0)
            clear()
        }
    }

    /**
     * Set up the buffer for a new audio format.
     *
     * @param sampleRate The sample rate of the incoming audio.
     * @param delayFrames How many frames the audio output buffers before they are heard.
     */
    fun configure(sampleRate: Int, delayFrames: Int) {
        synchronized(lock) {
            this.sampleRate = sampleRate
            this.delayFrames = delayFrames.coerceIn(0, RING_SIZE / 2)
            written = 0
            lastWriteNanos = 0
        }
    }

    /** Throw away all buffered audio, for example after a seek. */
    fun clear() {
        synchronized(lock) {
            written = 0
            lastWriteNanos = 0
        }
    }

    /**
     * Add new audio to the buffer.
     *
     * @param samples Mono samples in the range -1 to 1.
     * @param count How many samples of [samples] to add.
     */
    fun write(samples: FloatArray, count: Int) {
        synchronized(lock) {
            for (i in 0 until count) {
                ring[((written + i) and MASK).toInt()] = samples[i]
            }
            written += count
            lastWriteNanos = System.nanoTime()
        }
    }

    /**
     * Copy the [count] samples that are being heard right now into [out], oldest first. Parts of
     * [out] that there is no audio for are filled with silence.
     *
     * @return False if there is no audio at all, true otherwise.
     */
    fun read(out: FloatArray, count: Int): Boolean {
        synchronized(lock) {
            if (written == 0L) {
                out.fill(0f, 0, count)
                return false
            }
            // The audio output plays the buffered audio at the sample rate, so since the last
            // write the playhead has moved on by however much time has passed, up to the point
            // where the buffered audio would have run out.
            val elapsedNanos = (System.nanoTime() - lastWriteNanos).coerceIn(0L, 1_000_000_000L)
            val elapsedFrames =
                (elapsedNanos * sampleRate / 1_000_000_000L).coerceIn(0L, delayFrames.toLong())
            val end = written - delayFrames + elapsedFrames
            val oldest = written - RING_SIZE
            for (i in 0 until count) {
                val index = end - count + i
                out[i] = if (index < 0 || index < oldest) 0f else ring[(index and MASK).toInt()]
            }
            return true
        }
    }

    private companion object {
        // Enough for the largest audio output buffer (750ms) at 96kHz plus a full analysis window.
        const val RING_SIZE = 1 shl 17
        const val MASK = (RING_SIZE - 1).toLong()
    }
}
