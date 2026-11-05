/*
 * Copyright (c) 2025 Auxio Project
 * StepperOverlay.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.ui.stepper

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.provider.Settings
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.withStyledAttributes
import androidx.dynamicanimation.animation.SpringAnimation
import com.google.android.material.R as MR
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import org.oxycblt.auxio.R
import org.oxycblt.auxio.ui.Effect
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.util.getAttrColorCompat

enum class Direction {
    FORWARDS,
    BACKWARDS,
}

@AndroidEntryPoint
class StepperOverlay(context: Context, attrs: AttributeSet?) :
    ConstraintLayout(context, attrs), GestureDetector.OnDoubleTapListener {
    private sealed interface OverlayState {
        data object Invisible : OverlayState

        data class Entering(val tap: SpringAnimation, val seconds: SpringAnimation) : OverlayState

        data class Wait(val runnable: Runnable) : OverlayState

        data class Exiting(val tap: SpringAnimation, val seconds: SpringAnimation) : OverlayState
    }

    @Inject lateinit var uiSettings: UISettings

    private var leftSecondsView: SecondsView
    private var rightSecondsView: SecondsView
    private var leftTapView: TapView
    private var rightTapView: TapView
    private val gestureDetector: GestureDetector

    var listener: Listener? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 14f * resources.displayMetrics.scaledDensity
        }
    private val panelRect = RectF()
    private val fillRect = RectF()
    private val hideIndicator = Runnable {
        indicatorLabel = null
        invalidate()
    }

    private var downX = 0f
    private var downY = 0f
    private var adjusting = false
    private var adjustingBrightness = false
    private var startLevel = 0f
    private var indicatorLevel = 0f
    private var indicatorLabel: String? = null

    private val alphaSpring = Effect.FAST

    private var leftOverlayState: OverlayState = OverlayState.Invisible
    private var rightOverlayState: OverlayState = OverlayState.Invisible

    interface Listener {
        fun seek(direction: Direction)
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.player_fast_seek_overlay, this, true)

        // Set up shape appearance based on UISettings similar to CoverView
        context.withStyledAttributes(attrs, R.styleable.PlayerFastSeekOverlay) {
            val shapeAppearanceRes =
                getResourceId(
                    R.styleable.PlayerFastSeekOverlay_shapeAppearance,
                    com.google.android.material.R.style.ShapeAppearance_Material3_Corner_Medium,
                )

            background =
                MaterialShapeDrawable().apply {
                    shapeAppearanceModel =
                        if (uiSettings.roundMode) {
                            ShapeAppearanceModel.builder(context, shapeAppearanceRes, -1).build()
                        } else {
                            ShapeAppearanceModel.builder().build()
                        }
                    // Set transparent background as we only want the clipping
                    fillColor = ColorStateList.valueOf(Color.TRANSPARENT)
                }
        }

        leftSecondsView = findViewById(R.id.left_seconds_view)
        rightSecondsView = findViewById(R.id.right_seconds_view)
        leftTapView = findViewById(R.id.left_circle_clip_tap_view)
        rightTapView = findViewById(R.id.right_circle_clip_tap_view)

        // Apply shape clipping to the overlay
        clipToOutline = true

        // Hide overlay elements by default
        leftSecondsView.alpha = 0f
        rightSecondsView.alpha = 0f
        leftTapView.alpha = 0f
        rightTapView.alpha = 0f

        // Set up gesture detection
        gestureDetector =
            GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent) = true
                },
            )
        gestureDetector.setOnDoubleTapListener(this)

        addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            leftTapView.updateArcSize(view)
            rightTapView.updateArcSize(view)
        }

        // Initialize directions for each side
        leftSecondsView.setDirection(Direction.BACKWARDS)
        rightSecondsView.setDirection(Direction.FORWARDS)
        leftTapView.setDirection(Direction.BACKWARDS) // isLeft = true for left side
        rightTapView.setDirection(Direction.FORWARDS) // isLeft = false for right side
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
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
                    if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.5f) {
                        // A mostly vertical drag: the left half changes the brightness and the
                        // right half the volume.
                        adjusting = true
                        adjustingBrightness = downX < width * 0.5f
                        startLevel =
                            if (adjustingBrightness) currentBrightness() else currentVolume()
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (adjusting) {
                    val level = (startLevel + (downY - event.y) / (height * 0.8f)).coerceIn(0f, 1f)
                    if (adjustingBrightness) setBrightness(level) else setVolume(level)
                    return true
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (adjusting) {
                    adjusting = false
                    return true
                }
            }
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    private fun currentVolume() =
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private fun setVolume(level: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt(), 0)
        showIndicator(context.getString(R.string.lbl_volume), level)
    }

    private fun hostActivity(): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun currentBrightness(): Float {
        val window = hostActivity()?.window
        val set = window?.attributes?.screenBrightness ?: -1f
        if (set >= 0f) return set
        // Follow the system setting until the first change.
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) /
                255f
        } catch (e: Settings.SettingNotFoundException) {
            0.5f
        }
    }

    private fun setBrightness(level: Float) {
        val window = hostActivity()?.window ?: return
        val attributes = window.attributes
        // Never go fully black, or the screen would be unusable.
        attributes.screenBrightness = level.coerceAtLeast(0.01f)
        window.attributes = attributes
        showIndicator(context.getString(R.string.lbl_brightness), level)
    }

    private fun showIndicator(label: String, level: Float) {
        indicatorLabel = "$label ${(level * 100).roundToInt()}%"
        indicatorLevel = level
        removeCallbacks(hideIndicator)
        postDelayed(hideIndicator, INDICATOR_MS)
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val label = indicatorLabel ?: return
        val panelWidth = 168f * density
        val panelHeight = 44f * density
        val left = (width - panelWidth) / 2
        val top = 16f * density
        panelPaint.color = context.getAttrColorCompat(MR.attr.colorSurface).defaultColor
        panelPaint.alpha = 220
        fillPaint.color =
            context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary).defaultColor
        fillPaint.alpha = 90
        labelPaint.color = context.getAttrColorCompat(MR.attr.colorOnSurface).defaultColor
        panelRect.set(left, top, left + panelWidth, top + panelHeight)
        val radius = panelHeight / 2
        canvas.drawRoundRect(panelRect, radius, radius, panelPaint)
        canvas.save()
        canvas.clipRect(left, top, left + panelWidth * indicatorLevel, top + panelHeight)
        fillRect.set(panelRect)
        canvas.drawRoundRect(fillRect, radius, radius, fillPaint)
        canvas.restore()
        val baseline = top + panelHeight / 2 - (labelPaint.ascent() + labelPaint.descent()) / 2
        canvas.drawText(label, left + panelWidth / 2, baseline, labelPaint)
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        // A single tap switches between the album cover and the visualizer.
        uiSettings.showVisualizer = !uiSettings.showVisualizer
        return true
    }

    private fun enter(
        secondsView: SecondsView,
        tapView: TapView,
        overlayState: (OverlayState) -> Unit,
    ) {
        // start animating the seconds icon
        secondsView.startAnimation()
        val secondsIn = alphaSpring.alpha(secondsView, 1.0f)
        val tapIn = alphaSpring.alpha(tapView, 1.0f)
        // will execute after a delay
        val out = Runnable { exit(secondsView, tapView, overlayState) }
        tapIn.addEndListener { _, cancelled, _, _ ->
            if (!cancelled) {
                postDelayed(out, EXIT_DELAY_MS)
                overlayState(OverlayState.Wait(out))
            }
        }
        overlayState(OverlayState.Entering(secondsIn, tapIn))
    }

    private fun exit(
        secondsView: SecondsView,
        tapView: TapView,
        overlayState: (OverlayState) -> Unit,
    ) {
        val secondsOut = alphaSpring.alpha(secondsView, 0.0f)
        val tapOut = alphaSpring.alpha(tapView, 0.0f)
        tapOut.addEndListener { _, cancelled, _, _ ->
            if (!cancelled) {
                secondsView.stopAnimation()
                secondsView.seconds = 0
                overlayState(OverlayState.Invisible)
            }
        }
        overlayState(OverlayState.Exiting(secondsOut, tapOut))
    }

    private fun tap(
        tappedSecondsView: SecondsView,
        tappedTapView: TapView,
        tappedOverlayState: OverlayState,
        setTappedOverlayState: (OverlayState) -> Unit,
        idleSecondsView: SecondsView,
        idleTapView: TapView,
        idleOverlayState: OverlayState,
        setIdleOverlayState: (OverlayState) -> Unit,
    ) {
        // (re)activate the side that was tapped on
        when (tappedOverlayState) {
            is OverlayState.Invisible -> {
                enter(tappedSecondsView, tappedTapView, setTappedOverlayState)
            }

            is OverlayState.Entering -> {
                // do nothing
            }

            is OverlayState.Wait -> {
                // refresh the exit timeout
                removeCallbacks(tappedOverlayState.runnable)
                postDelayed(tappedOverlayState.runnable, EXIT_DELAY_MS)
            }

            is OverlayState.Exiting -> {
                // cancel exit and re-enter
                tappedOverlayState.seconds.cancel()
                tappedOverlayState.tap.cancel()
                enter(tappedSecondsView, tappedTapView, setTappedOverlayState)
            }
        }

        // clear out the idle side if it's active
        when (idleOverlayState) {
            is OverlayState.Invisible -> {
                // nothing to clear
            }

            is OverlayState.Entering -> {
                // cancel enter and exit immediately
                idleOverlayState.seconds.cancel()
                idleOverlayState.tap.cancel()
                exit(idleSecondsView, idleTapView, setIdleOverlayState)
            }

            is OverlayState.Wait -> {
                // dont wait for an exit, exit immediately
                removeCallbacks(idleOverlayState.runnable)
                exit(idleSecondsView, idleTapView, setIdleOverlayState)
            }

            is OverlayState.Exiting -> {
                // nothing to clear
            }
        }

        // update seconds from tap - reset when exit anim clears
        // largely implicit shared understanding that seeking occurs by 10s
        // todo: probably share this constant
        tappedSecondsView.seconds += 10
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        val width = width
        val tapX = e.x

        val direction =
            when {
                tapX < width * 0.5 -> Direction.BACKWARDS
                tapX > width * 0.5 -> Direction.FORWARDS
                // dont care
                else -> return false
            }

        // a bit duplicated but already the generic `enter`/`exit` logic
        // requires a good amount of params and trying to share the helpe
        when (direction) {
            Direction.BACKWARDS -> {
                tap(
                    leftSecondsView,
                    leftTapView,
                    leftOverlayState,
                    { leftOverlayState = it },
                    rightSecondsView,
                    rightTapView,
                    rightOverlayState,
                    { rightOverlayState = it },
                )
            }

            Direction.FORWARDS -> {
                // forwards, we tap on the right
                tap(
                    rightSecondsView,
                    rightTapView,
                    rightOverlayState,
                    { rightOverlayState = it },
                    leftSecondsView,
                    leftTapView,
                    leftOverlayState,
                    { leftOverlayState = it },
                )
            }
        }

        listener?.seek(direction)

        return false
    }

    override fun onDoubleTapEvent(e: MotionEvent) = false

    private companion object {
        private const val EXIT_DELAY_MS = 500L
        private const val INDICATOR_MS = 900L
    }
}
