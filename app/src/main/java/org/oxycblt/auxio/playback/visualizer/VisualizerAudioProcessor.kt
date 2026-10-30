/*
 * Copyright (c) 2026 Auxio Project
 * VisualizerAudioProcessor.kt is part of Auxio.
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

import android.media.AudioFormat
import android.media.AudioTrack
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject

/**
 * An [AudioProcessor] that passes audio through unchanged while copying it into the
 * [VisualizerBuffer] for the visualizer to draw. It only does any work while a visualizer is
 * showing.
 *
 * Reading the audio here instead of through Android's Visualizer API means no microphone
 * permission is needed, and the visuals reflect exactly what is played (after ReplayGain).
 */
class VisualizerAudioProcessor @Inject constructor(private val buffer: VisualizerBuffer) :
    BaseAudioProcessor() {
    private var channelCount = 2
    private var scratch = FloatArray(4096)

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        val sampleRate = inputAudioFormat.sampleRate
        buffer.configure(sampleRate, estimateOutputDelayFrames(sampleRate, channelCount))
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (buffer.isWanted) {
            val shorts = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val frames = shorts.remaining() / channelCount
            if (scratch.size < frames) {
                scratch = FloatArray(frames)
            }
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channelCount) {
                    sum += shorts.get(frame * channelCount + channel)
                }
                scratch[frame] = sum / (channelCount * 32768f)
            }
            buffer.write(scratch, frames)
        }

        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    override fun onFlush() {
        buffer.clear()
    }

    /**
     * Estimate how long audio waits in the audio output before it is heard. This mirrors how the
     * player sizes that buffer: four times the minimum, kept between 250ms and 750ms.
     */
    private fun estimateOutputDelayFrames(sampleRate: Int, channelCount: Int): Int {
        val channelMask =
            if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBufferBytes =
            AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val minFrames = (sampleRate * MIN_BUFFER_MS / 1000).toInt()
        val maxFrames = (sampleRate * MAX_BUFFER_MS / 1000).toInt()
        if (minBufferBytes <= 0) {
            return minFrames
        }
        val bytesPerFrame = 2 * if (channelCount == 1) 1 else 2
        return (minBufferBytes / bytesPerFrame * BUFFER_MULTIPLIER).coerceIn(minFrames, maxFrames)
    }

    private companion object {
        const val MIN_BUFFER_MS = 250L
        const val MAX_BUFFER_MS = 750L
        const val BUFFER_MULTIPLIER = 4
    }
}
