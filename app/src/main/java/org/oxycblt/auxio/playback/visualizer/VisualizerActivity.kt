/*
 * Copyright (c) 2026 Auxio Project
 * VisualizerActivity.kt is part of Auxio.
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

import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ActivityVisualizerBinding
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.formatDurationMs
import org.oxycblt.musikr.Song

/**
 * The chosen visualizer on the whole screen, with the artist, song and length in a pill at the
 * bottom. It has no buttons: the back gesture leaves it.
 * - Double tap the right half for the next song, the left half for the previous one.
 * - Tap once to play or pause.
 * - Drag up or down on the left half for the brightness, on the right half for the volume.
 */
@AndroidEntryPoint
class VisualizerActivity : AppCompatActivity() {
    private val playbackModel: PlaybackViewModel by viewModels()
    private lateinit var binding: ActivityVisualizerBinding
    private lateinit var audioManager: AudioManager
    private var downX = 0f
    private var downY = 0f
    private var adjusting = false
    private var adjustingBrightness = false
    private var startLevel = 0f
    private val hideLevel = Runnable { binding.visualizerLevel.animate().alpha(0f).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVisualizerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        volumeControlStream = AudioManager.STREAM_MUSIC

        // Use the whole screen, including behind the camera cutout, with the system bars hidden.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowInsetsControllerCompat(window, binding.root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        binding.visualizerPill.maxWidth = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        binding.visualizerPill.isVisibleOrGone(false)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playbackModel.song.collect { song -> showSong(song) }
            }
        }
    }

    private fun showSong(song: Song?) {
        val pill = binding.visualizerPill
        if (song == null) {
            pill.isVisibleOrGone(false)
            return
        }
        val artist = song.artists.firstOrNull()?.name?.resolve(this)
        pill.text =
            listOfNotNull(artist, song.name.resolve(this), song.durationMs.formatDurationMs(false))
                .joinToString(" – ")
        pill.isVisibleOrGone(true)
    }

    private fun android.view.View.isVisibleOrGone(visible: Boolean) {
        visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    private val gestures by lazy {
        GestureDetector(
                this,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent) = true

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        playbackModel.togglePlaying()
                        showMessage(getString(R.string.desc_play_pause))
                        return true
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        if (e.x < binding.root.width * 0.5f) {
                            playbackModel.prev()
                            showMessage(getString(R.string.desc_skip_prev))
                        } else {
                            playbackModel.next()
                            showMessage(getString(R.string.desc_skip_next))
                        }
                        return true
                    }
                },
            )
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                adjusting = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!adjusting) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dy) > slop && abs(dy) > abs(dx) * 1.5f) {
                        adjusting = true
                        adjustingBrightness = downX < binding.root.width * 0.5f
                        startLevel = if (adjustingBrightness) brightness() else volume()
                    }
                }
                if (adjusting) {
                    val level =
                        (startLevel + (downY - event.y) / (binding.root.height * 0.7f))
                            .coerceIn(0f, 1f)
                    if (adjustingBrightness) setBrightness(level) else setVolume(level)
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun volume() =
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private fun setVolume(level: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt(), 0)
        showLevel(getString(R.string.lbl_volume), level)
    }

    private fun brightness(): Float {
        val set = window.attributes.screenBrightness
        if (set >= 0f) return set
        // Follow the system setting until the first change.
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Settings.SettingNotFoundException) {
            0.5f
        }
    }

    private fun setBrightness(level: Float) {
        val attributes = window.attributes
        // Never go fully black, or the screen would be unusable.
        attributes.screenBrightness = level.coerceAtLeast(0.01f)
        window.attributes = attributes
        showLevel(getString(R.string.lbl_brightness), level)
    }

    private fun showLevel(label: String, level: Float) {
        showMessage("$label ${(level * 100).roundToInt()}%")
    }

    private fun showMessage(text: String) {
        val view = binding.visualizerLevel
        view.text = text
        view.removeCallbacks(hideLevel)
        view.animate().cancel()
        view.alpha = 1f
        view.postDelayed(hideLevel, LEVEL_MS)
    }

    private companion object {
        const val LEVEL_MS = 900L
    }
}
