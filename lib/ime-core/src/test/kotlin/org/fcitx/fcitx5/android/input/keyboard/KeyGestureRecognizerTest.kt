/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyGestureRecognizerTest {

    @Test
    fun theKeyIsItsAreaGrownByTheSlop() = assertRows(
        "a point inside the key is inside" to {
            val r = recognizer()
            assertTrue(r.pointInView(0f, 0f))
            assertTrue(r.pointInView(50f, 25f))
            assertTrue(r.pointInView(99f, 49f))
        },
        "the slop margin counts as inside" to {
            val r = recognizer()
            assertTrue("left edge minus slop", r.pointInView(-8f, 25f))
            assertTrue("top edge minus slop", r.pointInView(50f, -8f))
            assertTrue("right edge plus slop", r.pointInView(107f, 25f))
            assertTrue("bottom edge plus slop", r.pointInView(50f, 57f))
        },
        "beyond the slop margin is outside" to {
            val r = recognizer()
            assertFalse(r.pointInView(-9f, 25f))
            assertFalse(r.pointInView(50f, -9f))
            assertFalse(r.pointInView(108f, 25f))
            assertFalse(r.pointInView(50f, 58f))
        },
    )

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

    /** A swipeable key keeps looking pressed when the finger wanders off; a plain key does not. */
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

    @Test
    fun movementBelowTheThresholdCountsNothing() {
        val r = recognizer()
        r.onDown(0f, 0f)
        val outcome = r.move(23f, 0f)
        assertEquals(0, outcome.countX)
        assertEquals(0, r.swipeTotalX)
    }

    @Test
    fun eachThresholdCrossedCountsAStep() = assertRows(
        "crossing the threshold counts one step" to {
            val r = recognizer()
            r.onDown(0f, 0f)
            assertEquals(1, r.move(24f, 0f).countX)
            assertEquals(1, r.swipeTotalX)
        },
        "one big jump counts every threshold it crossed" to {
            val r = recognizer()
            r.onDown(0f, 0f)
            assertEquals(3, r.move(72f, 0f).countX)
            assertEquals(3, r.swipeTotalX)
        },
    )

    /** The remainder carries forward: three 10px samples cross the 24px threshold once. */
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

    /** Both axes consume against the *previous* anchor, or a diagonal swipe mis-counts. */
    @Test
    fun aDiagonalSwipeCountsBothAxesAgainstTheSameAnchor() {
        val r = recognizer()
        r.onDown(0f, 0f)
        val outcome = r.move(24f, 24f)
        assertEquals(1, outcome.countX)
        assertEquals(1, outcome.countY)
    }

    /** Each axis has its own remainder, or a diagonal wobble types a symbol. */
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

    @Test
    fun whatTheKeyDidInsteadIsNotAlsoAClick() = assertRows(
        "a long press is not also a click" to {
            val r = recognizer(longPress = true)
            r.onDown(50f, 25f)
            assertFalse(r.onUp(1000L, longPressTriggered = true, repeatStarted = false).performClick)
        },
        "a repeat is not also a click" to {
            val r = recognizer(repeat = true)
            r.onDown(50f, 25f)
            assertFalse(r.onUp(1000L, longPressTriggered = false, repeatStarted = true).performClick)
        },
        "holding for repeat does not also click on release" to {
            val r = recognizer(swipe = false, repeat = true)
            r.onDown(50f, 25f)
            r.onMove(51f, 25f, longPressTriggered = false, repeatStarted = true)
            assertFalse(r.onUp(2000L, longPressTriggered = false, repeatStarted = true).performClick)
        },
        "a gesture the listener consumed is not also a click" to {
            val r = recognizer()
            r.onDown(50f, 25f)
            r.markGestureConsumed()
            assertFalse(r.onUp(1000L, longPressTriggered = false, repeatStarted = false).performClick)
        },
        "a swipe repeat is not also a click" to {
            val r = recognizer(swipeRepeat = true)
            r.onDown(0f, 0f)
            r.move(24f, 0f)
            assertFalse(r.onUp(1000L, longPressTriggered = false, repeatStarted = false).performClick)
        },
        "a tap that wandered off the key is not a click" to {
            val r = recognizer()
            r.onDown(50f, 25f)
            r.move(200f, 25f)
            assertFalse(r.onUp(1000L, longPressTriggered = false, repeatStarted = false).performClick)
        },
    )

    /** The consumed flag clears between touches on every key, not only swiping ones. */
    @Test
    fun onAKeyThatDoesNotSwipeTheConsumedFlagIsClearedForTheNextTouch() {
        val r = recognizer(swipe = false)
        r.onDown(50f, 25f)
        r.markGestureConsumed()
        assertFalse(r.onUp(1000L, longPressTriggered = false, repeatStarted = false).performClick)
        r.resetForNextTouch()
        assertFalse(r.gestureConsumed)
        r.onDown(50f, 25f)
        assertTrue(r.onUp(2000L, longPressTriggered = false, repeatStarted = false).performClick)
    }

    @Test
    fun onAKeyThatSwipesTheConsumedFlagIsClearedForTheNextTouch() {
        val r = recognizer(swipe = true)
        r.onDown(50f, 25f)
        r.markGestureConsumed()
        r.onUp(1000L, longPressTriggered = false, repeatStarted = false)
        r.resetForNextTouch()
        r.onDown(50f, 25f)
        assertTrue(r.onUp(2000L, longPressTriggered = false, repeatStarted = false).performClick)
    }

    @Test
    fun anUnconsumedSwipeStillAllowsTheClick() {
        val r = recognizer(swipeRepeat = false)
        r.onDown(0f, 0f)
        r.move(24f, 0f)
        assertTrue(r.onUp(1000L, longPressTriggered = false, repeatStarted = false).performClick)
    }

    @Test
    fun twoTapsInsideTheWindowAreADoubleTap() {
        val r = recognizer(doubleTap = true)
        r.onDown(50f, 25f)
        val first = r.onUp(1000L, longPressTriggered = false, repeatStarted = false)
        assertTrue(first.performClick)
        assertFalse("the first tap is an ordinary click", first.isDoubleTap)
        r.resetForNextTouch()

        r.onDown(50f, 25f)
        val second = r.onUp(1200L, longPressTriggered = false, repeatStarted = false)
        assertTrue(second.isDoubleTap)
    }

    @Test
    fun theWindowIsInclusiveAtItsEdge() {
        val r = recognizer(doubleTap = true)
        r.onDown(50f, 25f)
        r.onUp(1000L, longPressTriggered = false, repeatStarted = false)
        r.resetForNextTouch()
        r.onDown(50f, 25f)
        assertTrue("exactly at the timeout still counts", r.onUp(1300L, longPressTriggered = false, repeatStarted = false).isDoubleTap)
    }

    @Test
    fun twoTapsTooFarApartAreTwoSingleTaps() {
        val r = recognizer(doubleTap = true)
        r.onDown(50f, 25f)
        r.onUp(1000L, longPressTriggered = false, repeatStarted = false)
        r.resetForNextTouch()
        r.onDown(50f, 25f)
        assertFalse(r.onUp(1301L, longPressTriggered = false, repeatStarted = false).isDoubleTap)
    }

    /** A touch that is no click disarms a pending double tap: shift, tap, wander, tap must not lock caps. */
    @Test
    fun aNonClickBetweenTwoTapsDisarmsTheDoubleTap() {
        val r = recognizer(doubleTap = true)
        r.onDown(50f, 25f)
        r.onUp(1000L, longPressTriggered = false, repeatStarted = false)
        r.resetForNextTouch()
        r.onDown(50f, 25f)
        r.move(500f, 25f)
        assertFalse("the wander is no click", r.onUp(1100L, longPressTriggered = false, repeatStarted = false).performClick)
        r.resetForNextTouch()
        r.onDown(50f, 25f)
        assertFalse("a tap again, not the second of a double tap", r.onUp(1200L, longPressTriggered = false, repeatStarted = false).isDoubleTap)
        r.resetForNextTouch()
        r.onDown(50f, 25f)
        assertTrue("which it starts", r.onUp(1300L, longPressTriggered = false, repeatStarted = false).isDoubleTap)
    }

    @Test
    fun threeQuickTapsDoNotProduceTwoDoubleTaps() {
        val r = recognizer(doubleTap = true)
        r.onDown(50f, 25f)
        assertFalse(r.onUp(1000L, longPressTriggered = false, repeatStarted = false).isDoubleTap)
        r.resetForNextTouch()
        r.onDown(50f, 25f)
        assertTrue(r.onUp(1100L, longPressTriggered = false, repeatStarted = false).isDoubleTap)
        r.resetForNextTouch()
        r.onDown(50f, 25f)
        assertFalse(r.onUp(1200L, longPressTriggered = false, repeatStarted = false).isDoubleTap)
    }

    @Test
    fun aTapThatIsNotAClickCannotStartADoubleTap() {
        val r = recognizer(doubleTap = true)
        r.onDown(50f, 25f)
        r.move(200f, 25f)
        assertFalse(r.onUp(1000L, longPressTriggered = false, repeatStarted = false).performClick)
        r.resetForNextTouch()

        r.onDown(50f, 25f)
        assertFalse("the discarded tap does not arm the double tap", r.onUp(1100L, longPressTriggered = false, repeatStarted = false).isDoubleTap)
    }

    @Test
    fun doubleTapIsNeverReportedWhenDisabled() {
        val r = recognizer(doubleTap = false)
        r.onDown(50f, 25f)
        r.onUp(1000L, longPressTriggered = false, repeatStarted = false)
        r.resetForNextTouch()
        r.onDown(50f, 25f)
        val outcome = r.onUp(1100L, longPressTriggered = false, repeatStarted = false)
        assertTrue(outcome.performClick)
        assertFalse(outcome.isDoubleTap)
    }

    @Test
    fun resetClearsEveryPerTouchField() {
        val r = recognizer(swipeRepeat = true)
        r.onDown(0f, 0f)
        r.move(48f, 24f)
        r.move(200f, 25f)
        r.markGestureConsumed()
        r.onUp(1000L, longPressTriggered = false, repeatStarted = false)

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
        assertTrue("a clean tap arms the double tap", r.onUp(1000L, longPressTriggered = false, repeatStarted = false).performClick)

        r.resetForNextTouch()

        r.onDown(50f, 25f)
        assertTrue("the armed double tap survived the reset", r.onUp(1100L, longPressTriggered = false, repeatStarted = false).isDoubleTap)
    }

    @Test
    fun cancelAlsoForgetsTheArmedDoubleTap() {
        val r = recognizer(doubleTap = true)
        r.onDown(50f, 25f)
        r.onUp(1000L, longPressTriggered = false, repeatStarted = false)
        r.cancel()

        r.onDown(50f, 25f)
        assertFalse(r.onUp(1100L, longPressTriggered = false, repeatStarted = false).isDoubleTap)
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

    @Test
    fun swipingUpForTheAlternateSymbolSuppressesTheClick() {
        val r = recognizer(swipe = true, swipeRepeat = true)
        r.onDown(50f, 40f)
        val outcome = r.move(50f, -8f)
        assertEquals(-2, outcome.countY)
        assertFalse(r.onUp(1000L, longPressTriggered = false, repeatStarted = false).performClick)
    }

    @Test
    fun aSlightlyImpreciseTapIsStillAClick() {
        val clean = recognizer()
        clean.onDown(50f, 25f)
        assertTrue(clean.onUp(1000L, longPressTriggered = false, repeatStarted = false).performClick)
        val r = recognizer()
        r.onDown(50f, 25f)
        r.move(53f, 27f)
        r.move(51f, 24f)
        assertTrue(r.onUp(1000L, longPressTriggered = false, repeatStarted = false).performClick)
        assertEquals(0, r.swipeTotalX)
    }

    @Test
    fun turningSwipeOffMidTouchDoesNotStopTheSwipe() {
        val r = recognizer(swipe = true)
        r.onDown(50f, 50f)
        r.swipeEnabled = false
        val outcome = r.onMove(50f + 3 * 24f, 50f, longPressTriggered = false, repeatStarted = false)
        assertEquals("judged by the settings the touch began with", 3, outcome.countX)
        assertTrue(outcome.dispatchMove)
    }

    @Test
    fun aChangedThresholdAppliesFromTheNextTouch() {
        val r = recognizer(swipe = true)
        r.onDown(50f, 50f)
        r.swipeThresholdX = 48f
        assertEquals(2, r.onMove(50f + 48f, 50f, longPressTriggered = false, repeatStarted = false).countX)
        r.onUp(0L, longPressTriggered = false, repeatStarted = false)
        r.resetForNextTouch()
        r.onDown(50f, 50f)
        assertEquals(1, r.onMove(50f + 48f, 50f, longPressTriggered = false, repeatStarted = false).countX)
    }

    @Test
    fun turningSwipeOnMidTouchWaitsForTheNextTouch() {
        val r = recognizer(swipe = false)
        r.onDown(50f, 50f)
        r.swipeEnabled = true
        assertFalse(r.onMove(50f + 3 * 24f, 50f, longPressTriggered = false, repeatStarted = false).dispatchMove)
        r.onUp(0L, longPressTriggered = false, repeatStarted = false)
        r.resetForNextTouch()
        r.onDown(50f, 50f)
        assertEquals(3, r.onMove(50f + 3 * 24f, 50f, longPressTriggered = false, repeatStarted = false).countX)
    }

    @Test
    fun turningLongPressOffMidTouchStillCancelsItsTimer() {
        val r = recognizer(swipe = false, longPress = true)
        r.onDown(50f, 25f)
        r.longPressEnabled = false
        assertTrue(r.onMove(500f, 25f, longPressTriggered = false, repeatStarted = false).cancelLongPress)
    }

    @Test
    fun turningRepeatOffMidTouchStillCancelsItsTimer() {
        val r = recognizer(swipe = false, repeat = true)
        r.onDown(50f, 25f)
        r.repeatEnabled = false
        assertTrue(r.onMove(500f, 25f, longPressTriggered = false, repeatStarted = false).cancelRepeat)
    }

    @Test
    fun turningSwipeRepeatOffMidTouchStillMarksTheSwipe() {
        val r = recognizer(swipe = true, swipeRepeat = true)
        r.onDown(50f, 25f)
        r.swipeRepeatEnabled = false
        r.onMove(50f + 24f, 25f, longPressTriggered = false, repeatStarted = false)
        assertTrue(r.swipeRepeatTriggered)
        assertFalse("a swipe is not a click", r.onUp(0L, longPressTriggered = false, repeatStarted = false).performClick)
    }

    @Test
    fun theDoubleTapWindowIsTheOneTheTouchBeganWith() {
        val r = recognizer(swipe = false, doubleTap = true).apply { doubleTapTimeoutMs = 300 }
        r.onDown(50f, 25f)
        r.onUp(1000L, longPressTriggered = false, repeatStarted = false)
        r.resetForNextTouch()
        r.onDown(50f, 25f)
        r.doubleTapTimeoutMs = 100
        assertTrue(r.onUp(1250L, longPressTriggered = false, repeatStarted = false).isDoubleTap)
    }

    @Test
    fun turningDoubleTapOffMidTouchStillCompletesTheDoubleTap() {
        val r = recognizer(swipe = false, doubleTap = true).apply { doubleTapTimeoutMs = 300 }
        r.onDown(50f, 25f)
        r.onUp(1000L, longPressTriggered = false, repeatStarted = false)
        r.resetForNextTouch()
        r.onDown(50f, 25f)
        r.doubleTapEnabled = false
        assertTrue(r.onUp(1100L, longPressTriggered = false, repeatStarted = false).isDoubleTap)
    }

    @Test
    fun aCancelUsesTheTouchsSettingsToo() {
        val r = recognizer(swipe = false, doubleTap = true).apply { doubleTapTimeoutMs = 300 }
        r.onDown(50f, 25f)
        r.onUp(1000L, longPressTriggered = false, repeatStarted = false)
        r.resetForNextTouch()
        r.onDown(50f, 25f)
        r.doubleTapEnabled = false
        r.cancel()
        r.doubleTapEnabled = true
        r.onDown(50f, 25f)
        assertFalse(r.onUp(1100L, longPressTriggered = false, repeatStarted = false).isDoubleTap)
    }

    @Test
    fun aSwipeCutShortBySettingsStillLeavesNoTotalsBehind() {
        val r = recognizer(swipe = true)
        r.onDown(50f, 50f)
        r.onMove(50f + 2 * 24f, 50f, longPressTriggered = false, repeatStarted = false)
        r.swipeEnabled = false
        r.onUp(0L, longPressTriggered = false, repeatStarted = false)
        r.resetForNextTouch()
        assertEquals(0, r.swipeTotalX)
    }

}
