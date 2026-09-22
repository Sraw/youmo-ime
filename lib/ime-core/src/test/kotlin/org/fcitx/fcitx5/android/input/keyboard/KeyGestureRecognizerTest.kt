/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every one of these used to require a device: the logic lived inside a `View`, driven by
 * `MotionEvent`s and a real clock. The swipe arithmetic in particular (fractional remainders
 * carried between samples, both axes reading the same anchor) is the sort of thing that is
 * nearly impossible to exercise by hand on an emulator.
 */
class KeyGestureRecognizerTest {

    /** A key 100x50, slop 8, swipe thresholds 24, everything enabled unless a test says otherwise. */
    private fun recognizer(
        swipe: Boolean = true,
        longPress: Boolean = false,
        repeat: Boolean = false,
        swipeRepeat: Boolean = false,
        doubleTap: Boolean = false,
    ) = KeyGestureRecognizer().apply {
        touchSlop = 8f
        viewWidth = 100
        viewHeight = 50
        swipeEnabled = swipe
        longPressEnabled = longPress
        repeatEnabled = repeat
        swipeRepeatEnabled = swipeRepeat
        doubleTapEnabled = doubleTap
        swipeThresholdX = 24f
        swipeThresholdY = 24f
        doubleTapTimeoutMs = 300L
    }

    private fun KeyGestureRecognizer.move(x: Float, y: Float) =
        onMove(x, y, longPressTriggered = false, repeatStarted = false)

    // region bounds

    @Test
    fun aPointInsideTheKeyIsInside() {
        val r = recognizer()
        assertTrue(r.pointInView(0f, 0f))
        assertTrue(r.pointInView(50f, 25f))
        assertTrue(r.pointInView(99f, 49f))
    }

    /** The hit area is grown by the touch slop on every side. */
    @Test
    fun theSlopMarginCountsAsInside() {
        val r = recognizer()
        assertTrue("left edge minus slop", r.pointInView(-8f, 25f))
        assertTrue("top edge minus slop", r.pointInView(50f, -8f))
        assertTrue("right edge plus slop", r.pointInView(107f, 25f))
        assertTrue("bottom edge plus slop", r.pointInView(50f, 57f))
    }

    @Test
    fun beyondTheSlopMarginIsOutside() {
        val r = recognizer()
        assertFalse(r.pointInView(-9f, 25f))
        assertFalse(r.pointInView(50f, -9f))
        assertFalse(r.pointInView(108f, 25f))
        assertFalse(r.pointInView(50f, 58f))
    }

    // endregion

    // region leaving the key

    @Test
    fun leavingTheKeyIsReportedOnceAndCancelsPendingTimers() {
        val r = recognizer(longPress = true, repeat = true)
        r.onDown(50f, 25f)

        val leaving = r.move(200f, 25f)
        assertTrue(leaving.movedOutsideNow)
        assertTrue(leaving.cancelLongPress)
        assertTrue(leaving.cancelRepeat)
        assertTrue(r.touchMovedOutside)

        val stillOutside = r.move(300f, 25f)
        assertFalse("only the first sample reports the crossing", stillOutside.movedOutsideNow)
    }

    @Test
    fun stayingInsideDoesNotCancelTimers() {
        val r = recognizer(longPress = true, repeat = true)
        r.onDown(50f, 25f)
        val outcome = r.move(52f, 26f)
        assertFalse(outcome.movedOutsideNow)
        assertFalse(outcome.cancelLongPress)
        assertFalse(outcome.cancelRepeat)
    }

    /**
     * A swipeable key keeps looking pressed when the finger wanders off, because the swipe is
     * still in progress; a plain key does not.
     */
    @Test
    fun leavingReleasesThePressedLookOnlyWhenThereIsNoSwipe() {
        val swipeable = recognizer(swipe = true)
        swipeable.onDown(50f, 25f)
        assertFalse(swipeable.move(200f, 25f).releasePressedState)

        val plain = recognizer(swipe = false)
        plain.onDown(50f, 25f)
        assertTrue(plain.move(200f, 25f).releasePressedState)
    }

    @Test
    fun leavingReleasesThePressedLookOnceARepeatIsRunning() {
        val r = recognizer(swipe = true, repeat = true)
        r.onDown(50f, 25f)
        val outcome = r.onMove(200f, 25f, longPressTriggered = false, repeatStarted = true)
        assertTrue(outcome.releasePressedState)
    }

    // endregion

    // region swipe arithmetic

    @Test
    fun movementBelowTheThresholdCountsNothing() {
        val r = recognizer()
        r.onDown(0f, 0f)
        val outcome = r.move(23f, 0f)
        assertEquals(0, outcome.countX)
        assertEquals(0, r.swipeTotalX)
    }

    @Test
    fun crossingTheThresholdCountsOneStep() {
        val r = recognizer()
        r.onDown(0f, 0f)
        assertEquals(1, r.move(24f, 0f).countX)
        assertEquals(1, r.swipeTotalX)
    }

    @Test
    fun oneBigJumpCountsEveryThresholdItCrossed() {
        val r = recognizer()
        r.onDown(0f, 0f)
        assertEquals(3, r.move(72f, 0f).countX)
        assertEquals(3, r.swipeTotalX)
    }

    /**
     * The remainder carries forward, so a slow drag still accumulates: three 10px samples add
     * up to 30px, which crosses the 24px threshold once.
     */
    @Test
    fun theRemainderCarriesAcrossSamplesSoASlowDragStillCounts() {
        val r = recognizer()
        r.onDown(0f, 0f)
        assertEquals(0, r.move(10f, 0f).countX)
        assertEquals(0, r.move(20f, 0f).countX)
        assertEquals(1, r.move(30f, 0f).countX)
        assertEquals(1, r.swipeTotalX)
    }

    @Test
    fun swipingBackCountsNegativeSteps() {
        val r = recognizer()
        r.onDown(100f, 0f)
        assertEquals(-1, r.move(76f, 0f).countX)
        assertEquals(-1, r.swipeTotalX)
    }

    @Test
    fun swipingOutAndBackNetsToZero() {
        val r = recognizer()
        r.onDown(0f, 0f)
        r.move(48f, 0f)
        r.move(0f, 0f)
        assertEquals(0, r.swipeTotalX)
    }

    @Test
    fun theTwoAxesAreCountedIndependently() {
        val r = recognizer()
        r.onDown(0f, 0f)
        val outcome = r.move(48f, 24f)
        assertEquals(2, outcome.countX)
        assertEquals(1, outcome.countY)
        assertEquals(2, r.swipeTotalX)
        assertEquals(1, r.swipeTotalY)
    }

    /**
     * Both axes are consumed against the *previous* anchor before it advances. If the anchor
     * moved between the two calls, a diagonal swipe would mis-count.
     */
    @Test
    fun aDiagonalSwipeCountsBothAxesAgainstTheSameAnchor() {
        val r = recognizer()
        r.onDown(0f, 0f)
        val outcome = r.move(24f, 24f)
        assertEquals(1, outcome.countX)
        assertEquals(1, outcome.countY)
    }

    /**
     * Each axis carries its own remainder. If they shared one, a sub-threshold drag on X would
     * push Y over its threshold -- a diagonal wobble would type a symbol the user never swiped for.
     */
    @Test
    fun theCarriedRemaindersDoNotLeakBetweenAxes() {
        val r = recognizer()
        r.onDown(0f, 0f)
        val outcome = r.move(10f, 20f)
        assertEquals("10px is below the 24px threshold", 0, outcome.countX)
        assertEquals("20px is below it too, and must not borrow X's 10px", 0, outcome.countY)
        assertEquals(0, r.swipeTotalY)
    }

    @Test
    fun eachAxisAccumulatesItsOwnRemainderAcrossSamples() {
        val r = recognizer()
        r.onDown(0f, 0f)
        r.move(20f, 4f)   // X carries 20, Y carries 4
        val second = r.move(28f, 8f) // X: 8+20=28 -> 1 step; Y: 4+4=8 -> none
        assertEquals(1, second.countX)
        assertEquals(0, second.countY)
    }

    @Test
    fun theAxesUseTheirOwnThresholds() {
        val r = recognizer().apply {
            swipeThresholdX = 10f
            swipeThresholdY = 100f
        }
        r.onDown(0f, 0f)
        val outcome = r.move(50f, 50f)
        assertEquals(5, outcome.countX)
        assertEquals(0, outcome.countY)
    }

    @Test
    fun aSwipeIsNotTrackedWhenSwipingIsDisabled() {
        val r = recognizer(swipe = false)
        r.onDown(0f, 0f)
        val outcome = r.move(100f, 0f)
        assertFalse("no Move gesture is dispatched", outcome.dispatchMove)
        assertEquals(0, outcome.countX)
        assertEquals(0, r.swipeTotalX)
    }

    /** Once a long press or repeat owns the gesture, movement stops producing swipe steps. */
    @Test
    fun swipeStepsStopOnceALongPressHasFired() {
        val r = recognizer(longPress = true)
        r.onDown(0f, 0f)
        val outcome = r.onMove(100f, 0f, longPressTriggered = true, repeatStarted = false)
        assertFalse(outcome.dispatchMove)
        assertEquals(0, outcome.countX)
    }

    @Test
    fun swipeStepsStopOnceARepeatIsRunning() {
        val r = recognizer(repeat = true)
        r.onDown(0f, 0f)
        val outcome = r.onMove(100f, 0f, longPressTriggered = false, repeatStarted = true)
        assertFalse(outcome.dispatchMove)
        assertEquals(0, outcome.countX)
    }

    @Test
    fun crossingAThresholdCancelsAPendingLongPress() {
        val r = recognizer(longPress = true, repeat = true)
        r.onDown(0f, 0f)
        val outcome = r.move(24f, 0f)
        assertTrue("a deliberate swipe is not a long press", outcome.cancelLongPress)
        assertTrue(outcome.cancelRepeat)
    }

    @Test
    fun movementBelowTheThresholdLeavesAPendingLongPressAlone() {
        val r = recognizer(longPress = true, repeat = true)
        r.onDown(0f, 0f)
        val outcome = r.move(5f, 0f)
        assertFalse(outcome.cancelLongPress)
        assertFalse(outcome.cancelRepeat)
    }

    @Test
    fun swipeRepeatIsMarkedOnTheFirstStep() {
        val r = recognizer(swipeRepeat = true)
        r.onDown(0f, 0f)
        assertFalse(r.swipeRepeatTriggered)
        r.move(24f, 0f)
        assertTrue(r.swipeRepeatTriggered)
    }

    // endregion

    // region what counts as a click

    @Test
    fun aCleanTapIsAClick() {
        val r = recognizer()
        r.onDown(50f, 25f)
        assertTrue(r.onUp(1000L, longPressTriggered = false, repeatStarted = false).performClick)
    }

    @Test
    fun aTapThatWanderedOffTheKeyIsNotAClick() {
        val r = recognizer()
        r.onDown(50f, 25f)
        r.move(200f, 25f)
        assertFalse(r.onUp(1000L, longPressTriggered = false, repeatStarted = false).performClick)
    }

    @Test
    fun aLongPressIsNotAlsoAClick() {
        val r = recognizer(longPress = true)
        r.onDown(50f, 25f)
        assertFalse(r.onUp(1000L, longPressTriggered = true, repeatStarted = false).performClick)
    }

    @Test
    fun aRepeatIsNotAlsoAClick() {
        val r = recognizer(repeat = true)
        r.onDown(50f, 25f)
        assertFalse(r.onUp(1000L, longPressTriggered = false, repeatStarted = true).performClick)
    }

    @Test
    fun aSwipeRepeatIsNotAlsoAClick() {
        val r = recognizer(swipeRepeat = true)
        r.onDown(0f, 0f)
        r.move(24f, 0f)
        assertFalse(r.onUp(1000L, longPressTriggered = false, repeatStarted = false).performClick)
    }

    @Test
    fun aGestureTheListenerConsumedIsNotAlsoAClick() {
        val r = recognizer()
        r.onDown(50f, 25f)
        r.markGestureConsumed()
        assertFalse(r.onUp(1000L, longPressTriggered = false, repeatStarted = false).performClick)
    }

    /** A swipe the listener ignored still leaves the tap eligible to be a click. */
    @Test
    fun anUnconsumedSwipeStillAllowsTheClick() {
        val r = recognizer(swipeRepeat = false)
        r.onDown(0f, 0f)
        r.move(24f, 0f)
        assertTrue(r.onUp(1000L, longPressTriggered = false, repeatStarted = false).performClick)
    }

    // endregion

    // region double tap

    @Test
    fun twoTapsInsideTheWindowAreADoubleTap() {
        val r = recognizer(doubleTap = true)
        r.onDown(50f, 25f)
        val first = r.onUp(1000L, false, false)
        assertTrue(first.performClick)
        assertFalse("the first tap is an ordinary click", first.isDoubleTap)
        r.resetForNextTouch()

        r.onDown(50f, 25f)
        val second = r.onUp(1200L, false, false)
        assertTrue(second.isDoubleTap)
    }

    @Test
    fun theWindowIsInclusiveAtItsEdge() {
        val r = recognizer(doubleTap = true)
        r.onDown(50f, 25f); r.onUp(1000L, false, false); r.resetForNextTouch()
        r.onDown(50f, 25f)
        assertTrue("exactly at the timeout still counts", r.onUp(1300L, false, false).isDoubleTap)
    }

    @Test
    fun twoTapsTooFarApartAreTwoSingleTaps() {
        val r = recognizer(doubleTap = true)
        r.onDown(50f, 25f); r.onUp(1000L, false, false); r.resetForNextTouch()
        r.onDown(50f, 25f)
        assertFalse(r.onUp(1301L, false, false).isDoubleTap)
    }

    /** Three quick taps are one double tap then a fresh single, not two overlapping doubles. */
    @Test
    fun threeQuickTapsDoNotProduceTwoDoubleTaps() {
        val r = recognizer(doubleTap = true)
        r.onDown(50f, 25f); assertFalse(r.onUp(1000L, false, false).isDoubleTap); r.resetForNextTouch()
        r.onDown(50f, 25f); assertTrue(r.onUp(1100L, false, false).isDoubleTap); r.resetForNextTouch()
        r.onDown(50f, 25f); assertFalse(r.onUp(1200L, false, false).isDoubleTap)
    }

    @Test
    fun aTapThatIsNotAClickCannotStartADoubleTap() {
        val r = recognizer(doubleTap = true)
        r.onDown(50f, 25f)
        r.move(200f, 25f)
        assertFalse(r.onUp(1000L, false, false).performClick)
        r.resetForNextTouch()

        r.onDown(50f, 25f)
        assertFalse("the discarded tap does not arm the double tap", r.onUp(1100L, false, false).isDoubleTap)
    }

    @Test
    fun doubleTapIsNeverReportedWhenDisabled() {
        val r = recognizer(doubleTap = false)
        r.onDown(50f, 25f); r.onUp(1000L, false, false); r.resetForNextTouch()
        r.onDown(50f, 25f)
        val outcome = r.onUp(1100L, false, false)
        assertTrue(outcome.performClick)
        assertFalse(outcome.isDoubleTap)
    }

    // endregion

    // region resetting

    @Test
    fun resetClearsEveryPerTouchField() {
        val r = recognizer(swipeRepeat = true)
        r.onDown(0f, 0f)
        r.move(48f, 24f)
        r.move(200f, 25f)
        r.markGestureConsumed()
        r.onUp(1000L, false, false)

        r.resetForNextTouch()
        assertFalse(r.touchMovedOutside)
        assertFalse(r.gestureConsumed)
        assertFalse(r.swipeRepeatTriggered)
        assertEquals(0, r.swipeTotalX)
        assertEquals(0, r.swipeTotalY)
    }

    @Test
    fun resetKeepsTheDoubleTapArmed() {
        val r = recognizer(doubleTap = true)
        r.onDown(50f, 25f)
        assertTrue("a clean tap arms the double tap", r.onUp(1000L, false, false).performClick)

        r.resetForNextTouch()

        r.onDown(50f, 25f)
        assertTrue("the armed double tap survived the reset", r.onUp(1100L, false, false).isDoubleTap)
    }

    @Test
    fun cancelAlsoForgetsTheArmedDoubleTap() {
        val r = recognizer(doubleTap = true)
        r.onDown(50f, 25f)
        r.onUp(1000L, false, false)
        r.cancel()

        r.onDown(50f, 25f)
        assertFalse(r.onUp(1100L, false, false).isDoubleTap)
    }

    @Test
    fun resetClearsTheCarriedSwipeRemainder() {
        val r = recognizer()
        r.onDown(0f, 0f)
        r.move(20f, 0f) // 20px carried, not yet a step
        r.resetForNextTouch()

        r.onDown(0f, 0f)
        assertEquals("the carried remainder must not leak into the next touch", 0, r.move(20f, 0f).countX)
    }

    // endregion

    // region realistic sequences

    /** Long-press backspace: hold, repeat fires, lift produces no extra click. */
    @Test
    fun holdingForRepeatDoesNotAlsoClickOnRelease() {
        val r = recognizer(swipe = false, repeat = true)
        r.onDown(50f, 25f)
        r.onMove(51f, 25f, longPressTriggered = false, repeatStarted = true)
        assertFalse(r.onUp(2000L, longPressTriggered = false, repeatStarted = true).performClick)
    }

    /** Swipe up on a letter key to type its digit: two steps up, no click on release. */
    @Test
    fun swipingUpForTheAlternateSymbolSuppressesTheClick() {
        val r = recognizer(swipe = true, swipeRepeat = true)
        r.onDown(50f, 40f)
        val outcome = r.move(50f, -8f)
        assertEquals(-2, outcome.countY)
        assertFalse(r.onUp(1000L, false, false).performClick)
    }

    /** Tap and drag slightly, staying within threshold: still an ordinary click. */
    @Test
    fun aSlightlyImpreciseTapIsStillAClick() {
        val r = recognizer()
        r.onDown(50f, 25f)
        r.move(53f, 27f)
        r.move(51f, 24f)
        assertTrue(r.onUp(1000L, false, false).performClick)
        assertEquals(0, r.swipeTotalX)
    }

    // endregion
}
