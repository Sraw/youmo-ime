/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

open class CustomGestureView(ctx: Context) : FrameLayout(ctx) {

    enum class GestureType { Down, Move, Up }

    data class Event(
        val type: GestureType,
        val consumed: Boolean,
        val x: Float,
        val y: Float,
        val countX: Int,
        val countY: Int,
        val totalX: Int,
        val totalY: Int
    )

    fun interface OnGestureListener {
        fun onGesture(view: View, event: Event): Boolean

        companion object {
            val Empty = OnGestureListener { _, _ -> false }
        }
    }

    private val lifecycleScope by lazy {
        findViewTreeLifecycleOwner()?.lifecycleScope!!
    }

    /**
     * All the coordinate and timing bookkeeping. This view is the adapter: it decodes
     * [MotionEvent]s, runs the timers as coroutines and performs the Android-side effects,
     * while every decision about what a gesture *means* comes from here.
     */
    private val recognizer = KeyGestureRecognizer()

    @Volatile
    private var longPressTriggered = false
    var longPressEnabled
        get() = recognizer.longPressEnabled
        set(value) {
            recognizer.longPressEnabled = value
        }
    private var longPressJob: Job? = null

    @Volatile
    var longPressFeedbackEnabled = true

    @Volatile
    private var repeatStarted = false
    var repeatEnabled
        get() = recognizer.repeatEnabled
        set(value) {
            recognizer.repeatEnabled = value
        }
    private var repeatJob: Job? = null

    var swipeEnabled
        get() = recognizer.swipeEnabled
        set(value) {
            recognizer.swipeEnabled = value
        }
    var swipeRepeatEnabled
        get() = recognizer.swipeRepeatEnabled
        set(value) {
            recognizer.swipeRepeatEnabled = value
        }
    var swipeThresholdX
        get() = recognizer.swipeThresholdX
        set(value) {
            recognizer.swipeThresholdX = value
        }
    var swipeThresholdY
        get() = recognizer.swipeThresholdY
        set(value) {
            recognizer.swipeThresholdY = value
        }

    var doubleTapEnabled
        get() = recognizer.doubleTapEnabled
        set(value) {
            recognizer.doubleTapEnabled = value
        }

    var onDoubleTapListener: ((View) -> Unit)? = null
    var onRepeatListener: ((View) -> Unit)? = null
    var onGestureListener: OnGestureListener? = null

    var soundEffect: InputFeedbacks.SoundEffect = InputFeedbacks.SoundEffect.Standard

    init {
        recognizer.touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
        // disable system sound effect and haptic feedback
        isSoundEffectsEnabled = false
        isHapticFeedbackEnabled = false
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) {
            isPressed = false
        }
    }

    private fun resetState() {
        if (longPressEnabled) {
            longPressTriggered = false
            longPressJob?.cancel()
            longPressJob = null
        }
        if (repeatEnabled) {
            repeatStarted = false
            repeatJob?.cancel()
            repeatJob = null
        }
        recognizer.resetForNextTouch()
    }

    fun cancelGestures() {
        isPressed = false
        resetState()
        // unlike a normal lift, a cancel also forgets a pending double tap
        recognizer.cancel()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Refreshed here rather than in onSizeChanged: KeyView overrides that without calling
        // super, so a hook there silently never runs and every move looks like it left the key.
        recognizer.viewWidth = width
        recognizer.viewHeight = height
        // longPressDelay is a live preference; the timers below read it per touch, so the
        // double-tap window must too or the two drift apart after the user changes it
        recognizer.doubleTapTimeoutMs = longPressDelay.toLong()
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isEnabled) return false
                drawableHotspotChanged(x, y)
                isPressed = true
                InputFeedbacks.hapticFeedback(this)
                InputFeedbacks.soundEffect(soundEffect)
                dispatchGestureEvent(GestureType.Down, x, y)
                if (longPressEnabled) {
                    longPressJob?.cancel()
                    longPressJob = lifecycleScope.launch {
                        delay(longPressDelay.toLong())
                        if (longPressFeedbackEnabled) {
                            InputFeedbacks.hapticFeedback(this@CustomGestureView, true)
                        }
                        longPressTriggered = performLongClick()
                    }
                }
                if (repeatEnabled) {
                    repeatJob?.cancel()
                    repeatJob = lifecycleScope.launch {
                        delay(longPressDelay.toLong())
                        repeatStarted = true
                        var lastTriggerTime: Long
                        while (isActive && isEnabled) {
                            lastTriggerTime = SystemClock.uptimeMillis()
                            onRepeatListener?.invoke(this@CustomGestureView)
                            val t = lastTriggerTime + RepeatInterval - SystemClock.uptimeMillis()
                            if (t > 0) delay(t)
                        }
                    }
                }
                recognizer.onDown(x, y)
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                InputFeedbacks.hapticFeedback(this, longPress = true, keyUp = true)
                dispatchGestureEvent(GestureType.Up, event.x, event.y)
                val outcome = recognizer.onUp(
                    nowMs = System.currentTimeMillis(),
                    longPressTriggered = longPressTriggered,
                    repeatStarted = repeatStarted,
                )
                resetState()
                if (outcome.performClick) {
                    if (outcome.isDoubleTap) onDoubleTapListener?.invoke(this) else performClick()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isEnabled) return false
                drawableHotspotChanged(x, y)
                val outcome = recognizer.onMove(x, y, longPressTriggered, repeatStarted)
                if (outcome.cancelLongPress) {
                    longPressJob?.cancel()
                    longPressJob = null
                }
                if (outcome.cancelRepeat) {
                    repeatJob?.cancel()
                    repeatJob = null
                }
                if (outcome.releasePressedState) {
                    isPressed = false
                }
                if (!outcome.dispatchMove) return true
                dispatchGestureEvent(GestureType.Move, x, y, outcome.countX, outcome.countY)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dispatchGestureEvent(GestureType.Up, event.x, event.y)
                cancelGestures()
                return true
            }
        }
        return true
    }

    private fun dispatchGestureEvent(
        type: GestureType,
        x: Float,
        y: Float,
        countX: Int = 0,
        countY: Int = 0
    ) {
        val event = Event(
            type, recognizer.gestureConsumed, x, y,
            countX, countY, recognizer.swipeTotalX, recognizer.swipeTotalY
        )
        val consumed = onGestureListener?.onGesture(this, event) ?: return
        if (consumed) {
            recognizer.markGestureConsumed()
        }
    }

    override fun setOnLongClickListener(l: OnLongClickListener?) {
        longPressEnabled = l != null
        super.setOnLongClickListener(l)
    }

    companion object {
        val longPressDelay by AppPrefs.getInstance().keyboard.longPressDelay
        const val RepeatInterval = 50L
    }
}
