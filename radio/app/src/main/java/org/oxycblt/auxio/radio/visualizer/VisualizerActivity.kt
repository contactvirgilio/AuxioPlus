package org.oxycblt.auxio.radio.visualizer

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import kotlin.math.abs
import kotlin.math.roundToInt
import org.oxycblt.auxio.radio.Favorites
import org.oxycblt.auxio.radio.PlayerConnection
import org.oxycblt.auxio.radio.R
import org.oxycblt.auxio.radio.databinding.ActivityVisualizerBinding
import org.oxycblt.auxio.radio.statusText

/**
 * The visualizer on the whole screen, with the station and what's playing in a pill at the bottom.
 * It has no buttons: the back gesture leaves it. Only the middle of the screen (between the top
 * quarter and the bottom fifth) reacts to touch, split in three columns:
 * - Left: double tap for the previous favourite, drag up or down for the brightness.
 * - Middle: tap to play or stop, long press to add or remove the station from favourites.
 * - Right: double tap for the next favourite, drag up or down for the volume.
 *
 * Tapping the pill switches between the colour cycling and the theme coloured visualizer.
 */
class VisualizerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVisualizerBinding
    private lateinit var audioManager: AudioManager
    private lateinit var player: PlayerConnection
    private val prefs by lazy { getSharedPreferences("visualizer", Context.MODE_PRIVATE) }
    private var downX = 0f
    private var downY = 0f
    private var adjusting = false
    private var adjustingBrightness = false
    private var ignoring = false
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

        applyMode()
        binding.visualizerPill.maxWidth = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        binding.visualizerPill.isSelected = true // Lets the marquee scroll.
        binding.visualizerPill.isVisible = false
        binding.visualizerPill.setOnClickListener {
            prefs.edit { putBoolean(KEY_COLOURED, !prefs.getBoolean(KEY_COLOURED, true)) }
            applyMode()
        }

        player =
            PlayerConnection(this) { c ->
                val name = c.currentMediaItem?.mediaMetadata?.station?.toString()
                binding.visualizerPill.isVisible = name != null
                if (name != null) {
                    val text = "$name – ${c.statusText(this)}"
                    if (binding.visualizerPill.text.toString() != text) binding.visualizerPill.text = text
                }
            }
    }

    private fun applyMode() {
        binding.visualizerFull.setMode(
            if (prefs.getBoolean(KEY_COLOURED, true)) VisualizerMode.SPECTRUM_CYCLE
            else VisualizerMode.SPECTRUM
        )
    }

    private val gestures by lazy {
        GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (zone(e.x) != Zone.MIDDLE) return false
                    player.togglePlaying()
                    showMessage(getString(R.string.desc_play_pause))
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (zone(e.x) != Zone.MIDDLE || adjusting) return
                    val station = player.currentStation() ?: return
                    val added = Favorites.toggle(station)
                    binding.visualizerLevel.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showMessage(
                        getString(if (added) R.string.lng_favorite_added else R.string.lng_favorite_removed)
                    )
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    when (zone(e.x)) {
                        Zone.LEFT -> {
                            player.previous()
                            showMessage(getString(R.string.desc_prev))
                        }
                        Zone.RIGHT -> {
                            player.next()
                            showMessage(getString(R.string.desc_next))
                        }
                        Zone.MIDDLE -> {}
                    }
                    return true
                }
            },
        )
    }

    private enum class Zone {
        LEFT,
        MIDDLE,
        RIGHT,
    }

    private fun zone(x: Float): Zone {
        val third = binding.root.width / 3f
        return when {
            x < third -> Zone.LEFT
            x < third * 2 -> Zone.MIDDLE
            else -> Zone.RIGHT
        }
    }

    /** Only the middle of the screen reacts, so the system edge gestures are never in the way. */
    private fun inActiveBand(y: Float): Boolean {
        val h = binding.root.height
        return y >= h * BAND_TOP && y <= h * BAND_BOTTOM
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) ignoring = !inActiveBand(event.y)
        if (ignoring) return super.dispatchTouchEvent(event)
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
                    if (abs(dy) > slop && abs(dy) > abs(dx) * 1.5f && zone(downX) != Zone.MIDDLE) {
                        adjusting = true
                        adjustingBrightness = zone(downX) == Zone.LEFT
                        startLevel = if (adjustingBrightness) brightness() else volume()
                    }
                }
                if (adjusting) {
                    val level =
                        (startLevel + (downY - event.y) / (binding.root.height * 0.7f)).coerceIn(0f, 1f)
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
        const val BAND_TOP = 0.25f
        const val BAND_BOTTOM = 0.82f
        const val LEVEL_MS = 900L
        const val KEY_COLOURED = "coloured"
    }
}
