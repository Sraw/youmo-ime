/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlin.math.abs

/**
 * A key's touch-gesture arithmetic, outside the View so it can be tested: left the key, swipe steps,
 * click, double tap. It runs no timers, only says when to cancel them; times are caller-supplied ms.
 */
class KeyGestureRecognizer {

    data class MoveOutcome(
        /** Left the key's slop-expanded bounds on this sample. */
        val movedOutsideNow: Boolean,
        val countX: Int,
        val countY: Int,
        /** Vertical steps of [crossStepY] once this touch has taken a sideways step. */
        val countCrossY: Int = 0,
        val dispatchMove: Boolean,
        val cancelLongPress: Boolean,
        val cancelRepeat: Boolean,
        val releasePressedState: Boolean,
    )

    data class UpOutcome(
        val performClick: Boolean,
        /** ...and it completes a double tap rather than starting one. */
        val isDoubleTap: Boolean,
    )

    var touchSlop: Float = 0f
    var viewWidth: Int = 0
    var viewHeight: Int = 0

    var longPressEnabled: Boolean = false
    var repeatEnabled: Boolean = false
    var swipeEnabled: Boolean = false
    var swipeRepeatEnabled: Boolean = false
    var doubleTapEnabled: Boolean = false

    /** One axis per touch, picked at the first move past the slop: a cursor moved then lifted a little is no swipe up. */
    var axisLock: Boolean = false

    var swipeThresholdX: Float = 24f
    var swipeThresholdY: Float = 24f

    /** After a sideways step, vertical steps of this size: the space's cursor moves up and down. 0 counts none. */
    var crossStepY: Float = 0f

    var doubleTapTimeoutMs: Long = 0L

    /** Frozen at touch-down: a setting flipped mid-touch never judges half a gesture by another rule. */
    private data class Settings(
        val longPress: Boolean,
        val repeat: Boolean,
        val swipe: Boolean,
        val swipeRepeat: Boolean,
        val doubleTap: Boolean,
        val axisLock: Boolean,
        val swipeThresholdX: Float,
        val swipeThresholdY: Float,
        val crossStepY: Float,
        val doubleTapTimeoutMs: Long,
    )

    private fun live() = Settings(
        longPressEnabled, repeatEnabled, swipeEnabled, swipeRepeatEnabled, doubleTapEnabled, axisLock,
        swipeThresholdX, swipeThresholdY, crossStepY, doubleTapTimeoutMs,
    )

    private var frozen: Settings? = null

    private val settings: Settings get() = frozen ?: live()

    var touchMovedOutside: Boolean = false
        private set

    var gestureConsumed: Boolean = false
        private set

    var swipeRepeatTriggered: Boolean = false
        private set

    private val swipeX = SwipeSteps()
    private val swipeY = SwipeSteps()

    // [crossStepY]'s, started at the first sideways step
    private val crossY = CrossSteps()

    val swipeTotalX: Int get() = swipeX.total

    val swipeTotalY: Int get() = swipeY.total

    /** Reached a step at some point, though maybe back at 0: swiped and taken back. */
    var swipedY: Boolean = false
        private set

    private enum class Axis { Undecided, X, Y }

    private var lockedAxis = Axis.Undecided

    private var maybeDoubleTap = false

    // for a drag past the slop on a key whose swipe repeats
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastClickTime = 0L

    /** Expanded by [touchSlop] on every side, matching the view. */
    fun pointInView(x: Float, y: Float): Boolean =
        -touchSlop <= x && -touchSlop <= y &&
                x < (viewWidth + touchSlop) && y < (viewHeight + touchSlop)

    fun onDown(x: Float, y: Float) {
        frozen = live()
        downX = x
        downY = y
        lastX = x
        if (settings.swipe) {
            swipeX.last = x
            swipeY.last = y
        }
    }

    /** A consumed gesture keeps the tap from being a click. */
    fun markGestureConsumed() {
        gestureConsumed = true
    }

    private fun draggedPastSlop(x: Float) =
        settings.swipe && settings.swipeRepeat && abs(x - downX) > touchSlop

    /** Asked at the timer as on each move, so the outcome does not hang on which comes first. */
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

        // a slow sideways drag past the slop on a repeating swipe is that swipe though no step is reached yet:
        // it cancels the long press. A pending repeat is still cancelled only by a real step
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

        // both axes read the *previous* anchor, so consume before moving it; none moves while the axis is unpicked
        val held = axisPending(x, y)
        val countX = stepsX(x, held)
        val countY = stepsY(y, held)
        val countCrossY = stepsCrossY(y, countX)
        if (countX != 0 || countY != 0) {
            if (settings.swipeRepeat) swipeRepeatTriggered = true
            if (settings.longPress && !longPressTriggered) cancelLongPress = true
            if (settings.repeat && !repeatStarted) cancelRepeat = true
        }
        return MoveOutcome(
            movedOutsideNow = movedOutsideNow,
            countX = countX,
            countY = countY,
            countCrossY = countCrossY,
            dispatchMove = true,
            cancelLongPress = cancelLongPress,
            cancelRepeat = cancelRepeat,
            releasePressedState = releasePressed,
        )
    }

    // [axisLock]: still unpicked within the slop; picked by which way it went further
    private fun axisPending(x: Float, y: Float): Boolean {
        if (!settings.axisLock || lockedAxis != Axis.Undecided) return false
        val dx = abs(x - downX)
        val dy = abs(y - downY)
        if (maxOf(dx, dy) <= touchSlop) return true
        lockedAxis = if (dx >= dy) Axis.X else Axis.Y
        return false
    }

    private fun stepsX(x: Float, held: Boolean): Int {
        if (held) return 0
        val steps = if (lockedAxis == Axis.Y) 0 else swipeX.consume(x, settings.swipeThresholdX)
        swipeX.last = x
        return steps
    }

    private fun stepsY(y: Float, held: Boolean): Int {
        if (held) return 0
        val steps = if (lockedAxis == Axis.X) 0 else swipeY.consume(y, settings.swipeThresholdY)
        swipeY.last = y
        if (swipeY.total != 0) swipedY = true
        return steps
    }

    private fun stepsCrossY(y: Float, countX: Int): Int = crossY.step(y, countX, settings.crossStepY)

    /** Call before [resetForNextTouch]. [nowMs] on a clock that does not jump (the eventTime). */
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

    /** Double-tap state survives: that is what lets the next touch complete one. */
    fun resetForNextTouch() {
        val settings = settings
        frozen = null
        touchMovedOutside = false
        swipedY = false
        lockedAxis = Axis.Undecided
        crossY.reset()
        if (settings.swipe) {
            if (settings.swipeRepeat) swipeRepeatTriggered = false
            swipeX.reset()
            swipeY.reset()
        }
        // on any key: a listener may consume a gesture on one that does not swipe
        gestureConsumed = false
    }

    fun cancel() {
        val doubleTap = settings.doubleTap
        resetForNextTouch()
        if (doubleTap) {
            maybeDoubleTap = false
            lastClickTime = 0
        }
    }
}
