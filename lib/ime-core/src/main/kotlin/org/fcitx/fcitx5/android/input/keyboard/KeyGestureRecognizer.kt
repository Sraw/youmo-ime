/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

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

    enum class SwipeAxis { X, Y }

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

    var swipeTotalX: Int = 0
        private set

    var swipeTotalY: Int = 0
        private set

    private var swipeLastX = -1f
    private var swipeLastY = -1f
    private var swipeXUnconsumed = 0f
    private var swipeYUnconsumed = 0f

    private var maybeDoubleTap = false
    private var lastClickTime = 0L

    // endregion

    /** The key's bounds are expanded by [touchSlop] on every side, matching the view. */
    fun pointInView(x: Float, y: Float): Boolean =
        -touchSlop <= x && -touchSlop <= y &&
                x < (viewWidth + touchSlop) && y < (viewHeight + touchSlop)

    fun onDown(x: Float, y: Float) {
        frozen = live()
        if (settings.swipe) {
            swipeLastX = x
            swipeLastY = y
        }
    }

    /**
     * Records that the gesture listener consumed an event, which disqualifies the eventual
     * tap from becoming a click.
     */
    fun markGestureConsumed() {
        gestureConsumed = true
    }

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
        val countX = consumeSwipe(x, SwipeAxis.X, settings)
        val countY = consumeSwipe(y, SwipeAxis.Y, settings)
        if (countX != 0 || countY != 0) {
            if (settings.swipeRepeat) swipeRepeatTriggered = true
            if (settings.longPress && !longPressTriggered) cancelLongPress = true
            if (settings.repeat && !repeatStarted) cancelRepeat = true
        }
        swipeLastX = x
        swipeLastY = y

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
     * @param nowMs wall-clock milliseconds, used only for the double-tap window
     */
    fun onUp(nowMs: Long, longPressTriggered: Boolean, repeatStarted: Boolean): UpOutcome {
        val shouldPerformClick = !(touchMovedOutside ||
                longPressTriggered ||
                repeatStarted ||
                swipeRepeatTriggered ||
                gestureConsumed)
        if (!shouldPerformClick) return UpOutcome(performClick = false, isDoubleTap = false)
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
            swipeXUnconsumed = 0f
            swipeYUnconsumed = 0f
            swipeTotalX = 0
            swipeTotalY = 0
            gestureConsumed = false
        }
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

    /**
     * How many whole thresholds the finger crossed on [axis] since the last sample, carrying
     * the remainder forward so a slow drag still accumulates.
     */
    private fun consumeSwipe(current: Float, axis: SwipeAxis, settings: Settings): Int {
        val unconsumed: Float
        val threshold: Float
        when (axis) {
            SwipeAxis.X -> {
                unconsumed = current - swipeLastX + swipeXUnconsumed
                threshold = settings.swipeThresholdX
            }
            SwipeAxis.Y -> {
                unconsumed = current - swipeLastY + swipeYUnconsumed
                threshold = settings.swipeThresholdY
            }
        }
        val remains = unconsumed % threshold
        val count = (unconsumed / threshold).toInt()
        when (axis) {
            SwipeAxis.X -> {
                swipeXUnconsumed = remains
                swipeTotalX += count
            }
            SwipeAxis.Y -> {
                swipeYUnconsumed = remains
                swipeTotalY += count
            }
        }
        return count
    }
}
