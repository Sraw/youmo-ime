/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.Event
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.fcitx.fcitx5.android.scene.theme.SoundKey

/**
 * A key's touch, apart from whatever draws it: decodes the finger's steps and runs the long press and
 * repeat timers in [Key.timers]; what a gesture *means* is the recognizer's ([KeyGestureRecognizer]).
 */
class KeyGesture(private val key: Key, touchSlop: Float) {

    interface Key {
        /** Px: a finger past it has left the key. */
        val keyWidth: Int
        val keyHeight: Int
        val keyEnabled: Boolean

        /** Told every time, a change or not, as View.setPressed is. */
        fun showPressed(pressed: Boolean)

        /** For a ripple's start. */
        fun hotspot(x: Float, y: Float) = Unit

        val feedback: View

        /** The main thread's, the window's lifetime. */
        val timers: CoroutineScope

        fun click(): Boolean

        fun longClick(): Boolean
        fun doubleTap()
        fun repeat()

        /** Null where it has none. */
        fun onGesture(event: Event): Boolean?
    }

    private val recognizer = KeyGestureRecognizer().also { it.touchSlop = touchSlop }

    @Volatile
    private var longPressTriggered = false

    var touchPointer = MotionEvent.INVALID_POINTER_ID
        private set

    // 0 left edge, 1 right, across its cell: nearest what was aimed at, as a lift is dragged off it
    private var downAt = Float.NaN

    private var downDown = Float.NaN

    // per finger id: typed fast, a second finger comes down on the same key (14 键's UI for "ui") before the first
    // lifts, and each lift keeps its own place. Two floats per id (0 to 31), NaN where none: no allocation
    private val downs = FloatArray(POINTER_IDS * 2) { Float.NaN }

    /**
     * For the click listener alone: set as the finger lifts, cleared once the click returns. NaN for a
     * click no touch made (TalkBack), and where the touch left the key or swiped past the threshold. A
     * swipe's own click and a stroke lifted short have it: neighbour correction reads where it landed.
     */
    var clickAt = Float.NaN
        private set

    /** 0 top, 1 bottom; for a layout's own groups' taps. */
    var clickDown = Float.NaN
        private set

    /** However far the touch went; NaN for a click no touch made. The engine reads a neighbouring key by it. */
    var fellAt = Float.NaN
        private set

    var fellDown = Float.NaN
        private set

    var longPressEnabled
        get() = recognizer.longPressEnabled
        set(value) {
            recognizer.longPressEnabled = value
        }
    private var longPressJob: Job? = null

    @Volatile
    var longPressFeedbackEnabled = true

    /** Asked as it would fire: where not, neither felt nor told, the touch still a tap (a named key's list off or empty). */
    var longPressNow: () -> Boolean = { true }

    @Volatile
    private var repeatStarted = false
    var repeatEnabled
        get() = recognizer.repeatEnabled
        set(value) {
            recognizer.repeatEnabled = value
        }
    private var repeatJob: Job? = null

    /**
     * Made anew at each down. Once seen stopped (what was typed as it began all deleted) the key repeats no
     * more; it stops on the host's thread, so a repeat or two on their way are felt, not sent.
     */
    var hold = BackspaceHold()
        private set

    var holdBegan: ((BackspaceHold) -> Unit)? = null

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

    /** The space's cursor up and down. */
    var crossStepY
        get() = recognizer.crossStepY
        set(value) {
            recognizer.crossStepY = value
        }

    /** A key swiped sideways and up. */
    var axisLock
        get() = recognizer.axisLock
        set(value) {
            recognizer.axisLock = value
        }

    var doubleTapEnabled
        get() = recognizer.doubleTapEnabled
        set(value) {
            recognizer.doubleTapEnabled = value
        }

    var soundEffect: SoundKey = SoundKey.LETTER

    /** Where no theme's sound plays. */
    var systemSound: SoundKey = SoundKey.LETTER

    private fun across(x: Float) = if (key.keyWidth > 0) x / key.keyWidth else Float.NaN

    private fun downward(y: Float) = if (key.keyHeight > 0) y / key.keyHeight else Float.NaN

    // if it stayed a tap: on the key, past no threshold
    private fun tapPlace(at: Float = downAt) =
        if (!recognizer.touchMovedOutside && recognizer.swipeTotalX == 0 && recognizer.swipeTotalY == 0) at else Float.NaN

    private fun resetState() {
        stopTimers()
        recognizer.resetForNextTouch()
    }

    /**
     * Unconditionally: had a flag been turned off while the finger was down, a check here would
     * leave a running repeat job behind for good.
     */
    private fun stopTimers() {
        longPressTriggered = false
        longPressJob?.cancel()
        longPressJob = null
        repeatStarted = false
        repeatJob?.cancel()
        repeatJob = null
    }

    fun cancelGestures() {
        downs.fill(Float.NaN)
        key.showPressed(false)
        stopTimers()
        // unlike a normal lift, a cancel also forgets a pending double tap (and resets the rest)
        recognizer.cancel()
    }

    fun onTouch(action: Int, x: Float, y: Float, pointerId: Int, eventTime: Long): Boolean {
        // per touch: the key's size changes with the scene's
        recognizer.viewWidth = key.keyWidth
        recognizer.viewHeight = key.keyHeight
        // a live preference: frozen per touch like the timers, else the two drift apart after the user changes it
        recognizer.doubleTapTimeoutMs = longPressDelay.toLong()
        when (action) {
            MotionEvent.ACTION_DOWN -> down(x, y, pointerId)
            MotionEvent.ACTION_UP -> up(x, y, pointerId, eventTime)
            MotionEvent.ACTION_MOVE -> return move(x, y)
            MotionEvent.ACTION_CANCEL -> {
                clickAt = Float.NaN
                clickDown = Float.NaN
                fellAt = Float.NaN
                fellDown = Float.NaN
                dispatchGestureEvent(GestureType.Up, x, y, cancelled = true)
                cancelGestures()
            }
        }
        return action != MotionEvent.ACTION_DOWN || key.keyEnabled
    }

    private fun down(x: Float, y: Float, pointerId: Int) {
        if (!key.keyEnabled) return
        // split from the keyboard's touch, the event keeps the finger's id
        touchPointer = pointerId
        downAt = across(x)
        downDown = downward(y)
        if (pointerId in 0 until POINTER_IDS) {
            downs[pointerId * 2] = downAt
            downs[pointerId * 2 + 1] = downDown
        }
        // a click that threw last time leaves none of its places to this touch
        clickAt = Float.NaN
        clickDown = Float.NaN
        fellAt = Float.NaN
        fellDown = Float.NaN
        key.hotspot(x, y)
        key.showPressed(true)
        InputFeedbacks.hapticFeedback(key.feedback)
        InputFeedbacks.soundEffect(soundEffect, systemSound)
        dispatchGestureEvent(GestureType.Down, x, y)
        if (longPressEnabled) {
            longPressJob?.cancel()
            longPressJob = key.timers.launch {
                delay(longPressDelay.toLong())
                if (!recognizer.longPressMayFire() || !longPressNow()) return@launch
                if (longPressFeedbackEnabled) {
                    InputFeedbacks.hapticFeedback(key.feedback, true)
                }
                longPressTriggered = key.longClick()
            }
        }
        if (repeatEnabled) {
            repeatJob?.cancel()
            hold = BackspaceHold().also { holdBegan?.invoke(it) }
            repeatJob = key.timers.launch {
                delay(longPressDelay.toLong())
                repeatStarted = true
                var lastTriggerTime: Long
                while (isActive && key.keyEnabled && !hold.stopped) {
                    lastTriggerTime = SystemClock.uptimeMillis()
                    key.repeat()
                    val t = lastTriggerTime + RepeatInterval - SystemClock.uptimeMillis()
                    if (t > 0) delay(t)
                }
            }
        }
        recognizer.onDown(x, y)
    }

    /** After its touch was cancelled for a stroke that, lifted short, was none (GlideTouch.Route.TAP_KEY). */
    fun tap(x: Float, y: Float) {
        if (!key.keyEnabled) return
        clickAt = across(x)
        clickDown = downward(y)
        fellAt = clickAt
        fellDown = clickDown
        key.click()
        clickAt = Float.NaN
        clickDown = Float.NaN
        fellAt = Float.NaN
        fellDown = Float.NaN
    }

    private fun up(x: Float, y: Float, pointerId: Int, eventTime: Long) {
        var at = downAt
        var atDown = downDown
        if (pointerId in 0 until POINTER_IDS && !downs[pointerId * 2].isNaN()) {
            at = downs[pointerId * 2]
            atDown = downs[pointerId * 2 + 1]
            downs[pointerId * 2] = Float.NaN
            downs[pointerId * 2 + 1] = Float.NaN
        }
        key.showPressed(false)
        // a swipe whose slot is nothing clicks the key at its Up: where it came down
        clickAt = at
        clickDown = atDown
        fellAt = at
        fellDown = atDown
        dispatchGestureEvent(GestureType.Up, x, y)
        val outcome = recognizer.onUp(
            nowMs = eventTime,
            longPressTriggered = longPressTriggered,
            repeatStarted = repeatStarted,
        )
        // not on every Move, which a grouped key dispatches however little the finger moved. The total is
        // signed, so a swipe a step and back counts as a tap, as it clicks anyway
        clickAt = tapPlace(at)
        clickDown = tapPlace(atDown)
        resetState()
        if (outcome.performClick) {
            if (outcome.isDoubleTap) key.doubleTap() else key.click()
        }
        clickAt = Float.NaN
        clickDown = Float.NaN
        fellAt = Float.NaN
        fellDown = Float.NaN
    }

    private fun move(x: Float, y: Float): Boolean {
        if (!key.keyEnabled) return false
        key.hotspot(x, y)
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
            key.showPressed(false)
        }
        if (!outcome.dispatchMove) return true
        dispatchGestureEvent(GestureType.Move, x, y, outcome.countX, outcome.countY, countCrossY = outcome.countCrossY)
        return true
    }

    private fun dispatchGestureEvent(
        type: GestureType,
        x: Float,
        y: Float,
        countX: Int = 0,
        countY: Int = 0,
        cancelled: Boolean = false,
        countCrossY: Int = 0,
    ) {
        val event = Event(
            type, recognizer.gestureConsumed, x, y,
            countX, countY, recognizer.swipeTotalX, recognizer.swipeTotalY, cancelled, recognizer.swipedY, countCrossY
        )
        val consumed = key.onGesture(event) ?: return
        if (consumed) {
            recognizer.markGestureConsumed()
        }
    }

    companion object {
        val longPressDelay by AppPrefs.getInstance().keyboard.longPressDelay
        const val RepeatInterval = 50L

        // MotionEvent pointer ids run 0 to 31
        private const val POINTER_IDS = 32
    }
}
