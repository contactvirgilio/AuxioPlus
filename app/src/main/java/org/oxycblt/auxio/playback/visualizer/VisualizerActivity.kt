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
import android.view.HapticFeedbackConstants
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
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ActivityVisualizerBinding
import org.oxycblt.auxio.music.FavoritesRepository
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.formatDurationMs
import org.oxycblt.auxio.playback.msToDs
import org.oxycblt.auxio.playback.stats.PlayCountRepository
import org.oxycblt.auxio.playback.stats.Trophies
import org.oxycblt.musikr.Song

/**
 * The chosen visualizer on the whole screen, with the artist, song and length in a pill at the
 * bottom. It has no buttons: the back gesture leaves it.
 * Only the middle of the screen (between the top quarter and the bottom fifth) reacts to touch.
 * It is split in three columns:
 * - Left: double tap for the previous song, drag up or down for the brightness.
 * - Middle: tap once to play or pause.
 * - Right: double tap for the next song, drag up or down for the volume.
 */
@AndroidEntryPoint
class VisualizerActivity : AppCompatActivity() {
    private val playbackModel: PlaybackViewModel by viewModels()
    @Inject lateinit var favoritesRepository: FavoritesRepository
    private lateinit var binding: ActivityVisualizerBinding
    private lateinit var audioManager: AudioManager
    private var downX = 0f
    private var downY = 0f
    private var adjusting = false
    private var adjustingBrightness = false
    private var startLevel = 0f
    private val preferences by lazy {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
    }
    @Inject lateinit var playCountRepository: PlayCountRepository
    private val countsListener =
        object : PlayCountRepository.Listener {
            override fun onPlayCountsChanged() {
                showSong(playbackModel.song.value)
                updateProgress(playbackModel.positionDs.value)
            }
        }
    private val favoritesListener =
        object : FavoritesRepository.Listener {
            override fun onFavoritesChanged() {
                showSong(playbackModel.song.value)
                updateProgress(playbackModel.positionDs.value)
            }
        }
    // The pill: what it says, and dragging along it to seek.
    private var songText: CharSequence = ""
    private var trophyText: CharSequence? = null
    private var trophyColor = Color.WHITE
    private var showingTrophy = false
    private var seeking = false
    private var seekDownX = 0f
    private var seekFraction = 0f
    private val flipPill =
        object : Runnable {
            override fun run() {
                showingTrophy = !showingTrophy
                renderPill()
                binding.visualizerPill.postDelayed(this, FLIP_MS)
            }
        }
    private val hideLevel = Runnable { binding.visualizerLevel.animate().alpha(0f).start() }
    private val hidePalette = Runnable { binding.visualizerPalette.animate().alpha(0f).setDuration(500).start() }

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

        // A rare, legendary or unique palette shows its name for a moment and gives a double tap of haptics.
        binding.visualizerFull.onPresetShown = { preset ->
            val label = binding.visualizerPalette
            label.text = preset.name
            label.removeCallbacks(hidePalette)
            label.animate().cancel()
            label.animate().alpha(1f).setDuration(500).start()
            label.postDelayed(hidePalette, 2000L)
            label.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            label.postDelayed(
                { label.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) },
                160L,
            )
        }

        binding.visualizerPill.isVisibleOrGone(false)

        // A tap on the pill does nothing. Dragging along it seeks: the time shows while the
        // finger is down and the song only jumps once it is lifted.
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        binding.visualizerPill.setOnTouchListener { view, event ->
            val duration = playbackModel.song.value?.durationMs ?: 0L
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    seekDownX = event.x
                    seeking = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!seeking && duration > 0L && abs(event.x - seekDownX) > slop) {
                        seeking = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (seeking) {
                        seekFraction = (event.x / view.width).coerceIn(0f, 1f)
                        pillFill()?.level = (seekFraction * 10_000).toInt()
                        renderPill()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (seeking) {
                        seeking = false
                        playbackModel.seekTo((seekFraction * duration).toLong().msToDs())
                        renderPill()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    seeking = false
                    renderPill()
                    updateProgress(playbackModel.positionDs.value)
                }
            }
            true
        }
        playCountRepository.addListener(countsListener)
        favoritesRepository.addListener(favoritesListener)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    playbackModel.song.collect { song ->
                        showSong(song)
                        updateProgress(playbackModel.positionDs.value)
                    }
                }
                launch { playbackModel.positionDs.collect { updateProgress(it) } }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playCountRepository.removeListener(countsListener)
        favoritesRepository.removeListener(favoritesListener)
        binding.visualizerPill.removeCallbacks(flipPill)
    }

    private fun pillFill() =
        (binding.visualizerPill.background as? LayerDrawable)?.findDrawableByLayerId(
            R.id.pill_progress
        )

    /** Fill the pill up to how far into the song it is. */
    private fun updateProgress(positionDs: Long) {
        // While the finger is on the pill, it shows where the song would jump to.
        if (seeking) return
        val fill = pillFill() ?: return
        val duration = playbackModel.song.value?.durationMs ?: 0L
        fill.level =
            if (duration <= 0L) 0
            else (positionDs * 1000_000L / duration).toInt().coerceIn(0, 10_000)
    }

    /** The pill of a song with a trophy: its colours around the edge and in the fill. */
    private fun trophyBackground(border: Int, fill: Int): LayerDrawable {
        val density = resources.displayMetrics.density
        fun shape() = GradientDrawable().apply { cornerRadius = 999f * density }
        val base = shape().apply { setColor(Color.BLACK) }
        val progress =
            ClipDrawable(shape().apply { setColor(fill) }, Gravity.LEFT, ClipDrawable.HORIZONTAL)
        val edge =
            shape().apply {
                setColor(Color.TRANSPARENT)
                setStroke((1.5f * density).toInt().coerceAtLeast(1), border)
            }
        return LayerDrawable(arrayOf(base, progress, edge)).apply { setId(1, R.id.pill_progress) }
    }

    /** Put the right words in the pill: the seek time, the trophy or the song. */
    private fun renderPill() {
        val pill = binding.visualizerPill
        val duration = playbackModel.song.value?.durationMs ?: 0L
        val trophy = trophyText
        when {
            seeking -> {
                pill.setTextColor(Color.WHITE)
                pill.text =
                    getString(
                        R.string.fmt_seek_position,
                        (seekFraction * duration).toLong().formatDurationMs(true),
                        duration.formatDurationMs(false),
                    )
            }
            showingTrophy && trophy != null -> {
                pill.setTextColor(trophyColor)
                pill.text = trophy
            }
            else -> {
                pill.setTextColor(Color.WHITE)
                pill.text = songText
            }
        }
    }

    private fun showSong(song: Song?) {
        val pill = binding.visualizerPill
        pill.removeCallbacks(flipPill)
        showingTrophy = false
        if (song == null) {
            pill.isVisibleOrGone(false)
            return
        }
        val artist = song.artists.firstOrNull()?.name?.resolve(this)
        songText =
            listOfNotNull(artist, song.name.resolve(this), song.durationMs.formatDurationMs(false))
                .joinToString(" – ")
        val trophy = Trophies.of(song)
        var horizontalPadding = pill.paddingLeft + pill.paddingRight
        // A small heart at the start of the pill for as long as the song is a favorite.
        val density = resources.displayMetrics.density
        val heart =
            if (favoritesRepository.isFavorite(song.uid)) {
                androidx.appcompat.content.res.AppCompatResources.getDrawable(
                    this,
                    R.drawable.ic_heart_red_24,
                )
            } else {
                null
            }
        if (heart != null) {
            val heartSize = (14 * density).toInt()
            heart.setBounds(0, 0, heartSize, heartSize)
            pill.compoundDrawablePadding = (6 * density).toInt()
            horizontalPadding += heartSize + pill.compoundDrawablePadding
        }
        pill.setCompoundDrawablesRelative(heart, null, null, null)
        if (trophy != null) {
            trophyText = trophy.describeShort(this)
            trophyColor = trophy.tier.bright
            pill.background = trophyBackground(trophy.tier.bright, trophy.tier.fill)
            // The words swap between the song and its trophy every few seconds.
            pill.postDelayed(flipPill, FLIP_MS)
        } else {
            trophyText = null
            pill.setBackgroundResource(R.drawable.bg_visualizer_pill_progress)
        }
        // The pill is as wide as what it says, up to a share of the narrow side of the screen.
        // Anything longer scrolls inside it. The song and its trophy text share one width, so
        // the pill does not jump about when the words swap.
        val metrics = resources.displayMetrics
        val maxWidth = (minOf(metrics.widthPixels, metrics.heightPixels) * PILL_MAX_WIDTH).toInt()
        val widest =
            maxOf(
                pill.paint.measureText(songText.toString()),
                trophyText?.let { pill.paint.measureText(it.toString()) } ?: 0f,
            )
        val pillWidth = (widest + horizontalPadding + 2).toInt().coerceAtMost(maxWidth)
        val roomForText = pillWidth - horizontalPadding
        pill.layoutParams = pill.layoutParams.apply { width = pillWidth }
        // Short text sits in the middle; long text starts at the left so it can scroll.
        pill.isSelected = true
        pill.gravity = if (widest <= roomForText) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
        renderPill()
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
                        if (zone(e.x) != Zone.MIDDLE) return false
                        playbackModel.togglePlaying()
                        showMessage(getString(R.string.desc_play_pause))
                        return true
                    }

                    override fun onLongPress(e: MotionEvent) {
                        if (zone(e.x) != Zone.MIDDLE) return
                        val song = playbackModel.song.value ?: return
                        val added = favoritesRepository.toggle(song.uid)
                        binding.visualizerLevel.performHapticFeedback(
                            android.view.HapticFeedbackConstants.LONG_PRESS
                        )
                        // A heart pops in, or breaks apart. The pill follows by itself.
                        if (added) binding.visualizerHeart.pop()
                        else binding.visualizerHeart.breakApart()
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        when (zone(e.x)) {
                            Zone.LEFT -> {
                                playbackModel.prev()
                                showMessage(getString(R.string.desc_skip_prev))
                            }
                            Zone.RIGHT -> {
                                playbackModel.next()
                                showMessage(getString(R.string.desc_skip_next))
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

    /** The screen is split in three equal columns. */
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

    /** Touches on the pill belong to the pill, wherever it ends up on the screen. */
    private fun onPill(event: MotionEvent): Boolean {
        val pill = binding.visualizerPill
        return pill.visibility == android.view.View.VISIBLE &&
            pill.getGlobalVisibleRect(pillRect) &&
            pillRect.contains(event.rawX.toInt(), event.rawY.toInt())
    }

    private val pillRect = android.graphics.Rect()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (
            event.actionMasked == MotionEvent.ACTION_DOWN &&
                (!inActiveBand(event.y) || onPill(event))
        ) {
            ignoring = true
        } else if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            ignoring = false
        }
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
                    if (
                        abs(dy) > slop &&
                            abs(dy) > abs(dx) * 1.5f &&
                            zone(downX) != Zone.MIDDLE
                    ) {
                        adjusting = true
                        adjustingBrightness = zone(downX) == Zone.LEFT
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

    private var ignoring = false

    private companion object {
        const val BAND_TOP = 0.25f
        const val BAND_BOTTOM = 0.82f
        const val LEVEL_MS = 900L
        const val FLIP_MS = 2600L
        const val PILL_MAX_WIDTH = 0.85f
    }
}
