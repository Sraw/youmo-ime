/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlin.math.abs

/**
 * The bookkeeping behind a key's touch gestures, with no Android types involved.
 *
 * A key has to decide, from a stream of touch samples, whether the finger left the key, how
 * many swipe steps were crossed on each axis, whether a tap still counts as a click after all
 * that, and whether two taps were close enough together to be a double tap. That is arithmetic
 * over coordinates and timestamps, and it is where the subtle bugs live -- but in a `View` it
 * can only be exercised on a device.
 *
 * What stays outside: the timers themselves (they are coroutines on the view's lifecycle),
 * haptics, sound, pressed state, and `performClick`. This class does not start or run timers;
 * it only says *when* they should be cancelled, and it is told after the fact whether a long
 * press or a repeat actually fired.
 *
 * All times are caller-supplied milliseconds, so tests control the clock exactly.
 */
class KeyGestureRecognizer {

    /** Which pending timers this sample invalidates. */
    data class MoveOutcome(
        /** The finger has just left the key's (slop-expanded) bounds on this sample. */
        val movedOutsideNow: Boolean,
        /** Swipe steps crossed on each axis since the previous sample. */
        val countX: Int,
        val countY: Int,
        /** Whether the caller should dispatch a Move gesture for this sample at all. */
        val dispatchMove: Boolean,
        /** Pending long-press / repeat timers that this sample invalidates. */
        val cancelLongPress: Boolean,
        val cancelRepeat: Boolean,
        /** Whether the key should stop rendering as pressed. */
        val releasePressedState: Boolean,
    )

    data class UpOutcome(
        /** A plain click should be performed. */
        val performClick: Boolean,
        /** ...and it completes a double tap rather than starting one. */
        val isDoubleTap: Boolean,
    )

    // region configuration, mirrored from the view

    var touchSlop: Float = 0f
    var viewWidth: Int = 0
    var viewHeight: Int = 0

    var longPressEnabled: Boolean = false
    var repeatEnabled: Boolean = false
    var swipeEnabled: Boolean = false
    var swipeRepeatEnabled: Boolean = false
    var doubleTapEnabled: Boolean = false

    var swipeThresholdX: Float = 24f
    var swipeThresholdY: Float = 24f

    /** How close two taps must be to count as a double tap. */
    var doubleTapTimeoutMs: Long = 0L

    /**
     * The configuration above, as it stood when the touch went down. A setting that changes
     * mid-touch (a preference flipped while a finger is on the key) takes effect from the next
     * touch, so one gesture is never judged half by one rule and half by another -- a swipe
     * cannot stop counting halfway, nor a key's state be left for a reset that no longer runs.
     */
    private data class Settings(
        val longPress: Boolean,
        val repeat: Boolean,
        val swipe: Boolean,
        val swipeRepeat: Boolean,
        val doubleTap: Boolean,
        val swipeThresholdX: Float,
        val swipeThresholdY: Float,
        val doubleTapTimeoutMs: Long,
    )

    private fun live() = Settings(
        longPressEnabled, repeatEnabled, swipeEnabled, swipeRepeatEnabled, doubleTapEnabled,
        swipeThresholdX, swipeThresholdY, doubleTapTimeoutMs,
    )

    /** Frozen at [onDown]; between touches the live configuration applies. */
    private var frozen: Settings? = null

    private val settings: Settings get() = frozen ?: live()

    // endregion

    // region state

    var touchMovedOutside: Boolean = false
        private set

    var gestureConsumed: Boolean = false
        private set

    var swipeRepeatTriggered: Boolean = false
        private set

    /** One axis of a swipe: where the finger last was, what is carried toward the next step, and the steps so far. */
    private class SwipeAxis {
        var last = -1f
        var unconsumed = 0f
        var total = 0

        /**
         * How many whole [threshold]s the finger crossed since the last sample, carrying the
         * remainder forward so a slow drag still accumulates.
         */
        fun consume(current: Float, threshold: Float): Int {
            val moved = current - last + unconsumed
            unconsumed = moved % threshold
            val count = (moved / threshold).toInt()
            total += count
            return count
        }

        fun reset() {
            unconsumed = 0f
            total = 0
        }
    }

    private val swipeX = SwipeAxis()
    private val swipeY = SwipeAxis()

    val swipeTotalX: Int get() = swipeX.total

    val swipeTotalY: Int get() = swipeY.total

    private var maybeDoubleTap = false

    // where the touch went down and where it is now, for a drag past the slop on a key whose
    // swipe repeats
    private var downX = 0f
    private var lastX = 0f
    private var lastClickTime = 0L

    // endregion

    /** The key's bounds are expanded by [touchSlop] on every side, matching the view. */
    fun pointInView(x: Float, y: Float): Boolean =
        -touchSlop <= x && -touchSlop <= y &&
                x < (viewWidth + touchSlop) && y < (viewHeight + touchSlop)

    fun onDown(x: Float, y: Float) {
        frozen = live()
        downX = x
        lastX = x
        if (settings.swipe) {
            swipeX.last = x
            swipeY.last = y
        }
    }

    /**
     * Records that the gesture listener consumed an event, which disqualifies the eventual
     * tap from becoming a click.
     */
    fun markGestureConsumed() {
        gestureConsumed = true
    }

    private fun draggedPastSlop(x: Float) =
        settings.swipe && settings.swipeRepeat && abs(x - downX) > touchSlop

    /**
     * Whether the long press timer, now due, may fire: not when the finger has already dragged
     * sideways past the slop on a key whose swipe repeats. Asked at the timer as well as on each
     * move, so the outcome does not hang on which comes first, the timer or the next sample.
     */
    fun longPressMayFire(): Boolean = !draggedPastSlop(lastX)

    fun onMove(x: Float, y: Float, longPressTriggered: Boolean, repeatStarted: Boolean): MoveOutcome {
        val settings = settings
        var movedOutsideNow = false
        var cancelLongPress = false
        var cancelRepeat = false
        var releasePressed = false

        if (!touchMovedOutside && !pointInView(x, y)) {
            touchMovedOutside = true
            movedOutsideNow = true
            if (settings.longPress) cancelLongPress = true
            if (settings.repeat) cancelRepeat = true
            if (repeatStarted || !settings.swipe) releasePressed = true
        }

        // A drag sideways past the slop on a key whose swipe repeats (the space's cursor, the
        // backspace's selection) is that swipe, though its first step is not reached yet: a slow
        // one took longer than the long press to reach it, and the long press then took the touch
        // (PY-41: 330 px in 4 s moved no cursor, where 300 px in 2.5 s did). Only the long
        // press: a pending repeat (holding the backspace) is cancelled by a real swipe step, as
        // before, so a finger that drifts a little still deletes on.
        lastX = x
        if (!longPressTriggered && !repeatStarted && draggedPastSlop(x)) {
            cancelLongPress = settings.longPress
        }

        // once a long press or repeat has fired, the gesture belongs to them
        if (!settings.swipe || longPressTriggered || repeatStarted) {
            return MoveOutcome(
                movedOutsideNow = movedOutsideNow,
                countX = 0,
                countY = 0,
                dispatchMove = false,
                cancelLongPress = cancelLongPress,
                cancelRepeat = cancelRepeat,
                releasePressedState = releasePressed,
            )
        }

        // both axes read the *previous* anchor, so consume before moving it
        val countX = swipeX.consume(x, settings.swipeThresholdX)
        val countY = swipeY.consume(y, settings.swipeThresholdY)
        if (countX != 0 || countY != 0) {
            if (settings.swipeRepeat) swipeRepeatTriggered = true
            if (settings.longPress && !longPressTriggered) cancelLongPress = true
            if (settings.repeat && !repeatStarted) cancelRepeat = true
        }
        swipeX.last = x
        swipeY.last = y

        return MoveOutcome(
            movedOutsideNow = movedOutsideNow,
            countX = countX,
            countY = countY,
            dispatchMove = true,
            cancelLongPress = cancelLongPress,
            cancelRepeat = cancelRepeat,
            releasePressedState = releasePressed,
        )
    }

    /**
     * Decides what a finger lift means. Call before [resetForNextTouch]: the decision depends
     * on state this touch accumulated.
     *
     * @param nowMs the lift's time on a clock that does not jump (the touch's eventTime), used
     * only for the double-tap window
     */
    fun onUp(nowMs: Long, longPressTriggered: Boolean, repeatStarted: Boolean): UpOutcome {
        val shouldPerformClick = !(touchMovedOutside ||
                longPressTriggered ||
                repeatStarted ||
                swipeRepeatTriggered ||
                gestureConsumed)
        if (!shouldPerformClick) {
            // a touch that was no click ends a pending double tap: tap, slide off, tap does not lock caps
            maybeDoubleTap = false
            return UpOutcome(performClick = false, isDoubleTap = false)
        }
        if (!settings.doubleTap) return UpOutcome(performClick = true, isDoubleTap = false)

        val isDoubleTap = maybeDoubleTap && nowMs - lastClickTime <= settings.doubleTapTimeoutMs
        maybeDoubleTap = !isDoubleTap
        lastClickTime = nowMs
        return UpOutcome(performClick = true, isDoubleTap = isDoubleTap)
    }

    /**
     * Clears per-touch state. Double-tap state deliberately survives -- that is what lets the
     * *next* touch complete a double tap.
     */
    fun resetForNextTouch() {
        val settings = settings
        frozen = null
        touchMovedOutside = false
        if (settings.swipe) {
            if (settings.swipeRepeat) swipeRepeatTriggered = false
            swipeX.reset()
            swipeY.reset()
        }
        // on any key: a listener may consume a gesture on one that does not swipe
        gestureConsumed = false
    }

    /** A cancelled gesture also forgets the pending double tap. */
    fun cancel() {
        val doubleTap = settings.doubleTap
        resetForNextTouch()
        if (doubleTap) {
            maybeDoubleTap = false
            lastClickTime = 0
        }
    }
}
