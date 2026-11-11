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
 * A key's touch, apart from whatever draws the key (060-render-p2 S1): a [CustomGestureView]'s, or a
 * key a scene draws (canvas/SceneTouch's SceneKey). It decodes the finger's steps, runs the long
 * press's and the repeat's timers as coroutines in [Key.timers] and tells [key] what they come to,
 * while every decision about what a gesture *means* comes from the recognizer ([KeyGestureRecognizer]).
 */
class KeyGesture(private val key: Key, touchSlop: Float) {

    /** What a touch reads of its key and does to it. */
    interface Key {
        /** Its size, px: a finger past it has left the key. */
        val keyWidth: Int
        val keyHeight: Int
        val keyEnabled: Boolean

        /** Shown pressed or not; told every time, a change or not, as View.setPressed is. */
        fun showPressed(pressed: Boolean)

        /** Where the finger is on it, for a ripple's start (a view's drawable hotspot). */
        fun hotspot(x: Float, y: Float) = Unit

        /** What buzzes ([InputFeedbacks.hapticFeedback]). */
        val feedback: View

        /** Where the long press's and the repeat's timers run: the main thread's, the window's lifetime. */
        val timers: CoroutineScope

        /** Its click (View.performClick); whether anything took it. */
        fun click(): Boolean

        /** Its long press (View.performLongClick); whether it took the touch. */
        fun longClick(): Boolean
        fun doubleTap()
        fun repeat()

        /** [event] to its listener: whether it consumed it, null where it has none. */
        fun onGesture(event: Event): Boolean?
    }

    private val recognizer = KeyGestureRecognizer().also { it.touchSlop = touchSlop }

    @Volatile
    private var longPressTriggered = false

    /** the finger on this key, by the id the keyboard's touch gives it */
    var touchPointer = MotionEvent.INVALID_POINTER_ID
        private set

    // where across the key the finger came down, 0 its left edge and 1 its right: nearest what was
    // aimed at, a lift is dragged off it. The key's width, its margins too (its cell's)
    private var downAt = Float.NaN

    // where down the key it came down, 0 its top and 1 its bottom: its cell's height, as [downAt]'s width
    private var downDown = Float.NaN

    // each finger's own [downAt] and [downDown] by its id: typed fast, a second finger comes down on the
    // same key (14 键's UI for "ui") before the first lifts, and the first's lift is its own place, not the
    // second's (060-fast-typing)
    private val downs = HashMap<Int, Pair<Float, Float>>(2)

    /**
     * Where across the key the touch that clicks it came down ([downAt]), for the click listener
     * alone: set as the finger lifts, before the touch's state is reset, cleared once the click
     * returns. NaN for a click no touch made (TalkBack), one that left the key or swiped past the
     * threshold, and for a swipe's own click (KeySwipe's Tap, made at the Up before this is set).
     */
    var clickAt = Float.NaN
        private set

    /** Where down the key that touch came down ([clickAt]'s y: 0 its top, 1 its bottom), NaN as [clickAt] is: a layout's own groups' taps (060-render-p3 T9b). */
    var clickDown = Float.NaN
        private set

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

    /** Vertical steps after a sideways one ([KeyGestureRecognizer.crossStepY]): the space's cursor up and down. */
    var crossStepY
        get() = recognizer.crossStepY
        set(value) {
            recognizer.crossStepY = value
        }

    /** One axis a touch ([KeyGestureRecognizer.axisLock]): a key swiped sideways and up. */
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

    /** The class of key it sounds as (060-theme-sounds-icons: SoundKey.of). */
    var soundEffect: SoundKey = SoundKey.LETTER

    /** The system's click it plays where no theme's sound does (SceneBehavior.systemSound). */
    var systemSound: SoundKey = SoundKey.LETTER

    private fun across(x: Float) = if (key.keyWidth > 0) x / key.keyWidth else Float.NaN

    private fun downward(y: Float) = if (key.keyHeight > 0) y / key.keyHeight else Float.NaN

    // where the touch came down ([downAt] across, [downDown] down), if it stayed a tap: on the key, swiped past no threshold
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
        downs.clear()
        key.showPressed(false)
        stopTimers()
        // unlike a normal lift, a cancel also forgets a pending double tap (and resets the rest)
        recognizer.cancel()
    }

    /**
     * A finger's step on the key, [action] a [MotionEvent]'s masked action, at ([x], [y]) in the key's
     * pixels, finger [pointerId]'s, at [eventTime]: what the key's own onTouchEvent did with it.
     */
    fun onTouch(action: Int, x: Float, y: Float, pointerId: Int, eventTime: Long): Boolean {
        // Refreshed per touch: the key's size changes with the scene's (another height, one-handed)
        recognizer.viewWidth = key.keyWidth
        recognizer.viewHeight = key.keyHeight
        // longPressDelay is a live preference; the timers below read it per touch, so the
        // double-tap window must too or the two drift apart after the user changes it (the
        // recognizer freezes the value written at ACTION_DOWN for the rest of the touch)
        recognizer.doubleTapTimeoutMs = longPressDelay.toLong()
        when (action) {
            MotionEvent.ACTION_DOWN -> down(x, y, pointerId)
            MotionEvent.ACTION_UP -> up(x, y, pointerId, eventTime)
            MotionEvent.ACTION_MOVE -> return move(x, y)
            MotionEvent.ACTION_CANCEL -> {
                clickAt = Float.NaN
                clickDown = Float.NaN
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
        downs[pointerId] = downAt to downDown
        clickAt = Float.NaN
        clickDown = Float.NaN
        key.hotspot(x, y)
        key.showPressed(true)
        InputFeedbacks.hapticFeedback(key.feedback)
        InputFeedbacks.soundEffect(soundEffect, systemSound)
        dispatchGestureEvent(GestureType.Down, x, y)
        if (longPressEnabled) {
            longPressJob?.cancel()
            longPressJob = key.timers.launch {
                delay(longPressDelay.toLong())
                if (!recognizer.longPressMayFire()) return@launch
                if (longPressFeedbackEnabled) {
                    InputFeedbacks.hapticFeedback(key.feedback, true)
                }
                longPressTriggered = key.longClick()
            }
        }
        if (repeatEnabled) {
            repeatJob?.cancel()
            repeatJob = key.timers.launch {
                delay(longPressDelay.toLong())
                repeatStarted = true
                var lastTriggerTime: Long
                while (isActive && key.keyEnabled) {
                    lastTriggerTime = SystemClock.uptimeMillis()
                    key.repeat()
                    val t = lastTriggerTime + RepeatInterval - SystemClock.uptimeMillis()
                    if (t > 0) delay(t)
                }
            }
        }
        recognizer.onDown(x, y)
    }

    private fun up(x: Float, y: Float, pointerId: Int, eventTime: Long) {
        val (at, atDown) = downs.remove(pointerId) ?: (downAt to downDown)
        key.showPressed(false)
        InputFeedbacks.hapticFeedback(key.feedback, longPress = true, keyUp = true)
        dispatchGestureEvent(GestureType.Up, x, y)
        val outcome = recognizer.onUp(
            nowMs = eventTime,
            longPressTriggered = longPressTriggered,
            repeatStarted = repeatStarted,
        )
        // not on every Move, which a grouped key dispatches however little the finger moved: only
        // a swipe past the threshold, or off the key, is no tap. The total is signed, so one
        // swiped a step and back counts as a tap, as it clicks anyway
        clickAt = tapPlace(at)
        clickDown = tapPlace(atDown)
        resetState()
        if (outcome.performClick) {
            if (outcome.isDoubleTap) key.doubleTap() else key.click()
        }
        clickAt = Float.NaN
        clickDown = Float.NaN
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
    }
}
