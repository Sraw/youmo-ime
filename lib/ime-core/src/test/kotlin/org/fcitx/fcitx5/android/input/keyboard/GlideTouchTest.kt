/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.input.keyboard.GlideTouch.Route
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideTouchTest {

    private fun touch(onLetter: Boolean = true) = GlideTouch().apply {
        down(pointerId = 0, x = 50f, y = 70f, time = 0, onLetter = onLetter, keyWidth = 100f, keyHeight = 140f, longPressMillis = 300)
    }

    @Test
    fun aStrokeCancelsTheKeysSoonerAndTheNextTouchGlidesAsBefore() {
        val t = GlideTouch()
        t.down(0, 50f, 70f, 0, onLetter = true, keyWidth = 100f, keyHeight = 140f, longPressMillis = 300, stroke = true)
        assertEquals(Route.KEYS, t.move(50f, 100f, 20))
        assertEquals(Route.CANCEL_KEYS, t.move(50f, 120f, 40))
        assertEquals(Route.GLIDE, t.up(50f, 140f, cancelled = false))
        // down, two moves, the lift: four places
        assertEquals(8, t.takeGlided()?.size)
        // the same keys, no stroke: as far down is the keys'
        t.down(0, 50f, 70f, 0, onLetter = true, keyWidth = 100f, keyHeight = 140f, longPressMillis = 300)
        assertEquals(Route.KEYS, t.move(50f, 120f, 40))
    }

    // 061-handwriting-toolbar §3.2: handwriting on, a touch on the letter area is a stroke from its down, never a key
    @Test
    fun handwritingOnATouchOnTheLettersIsAStrokeFromItsDown() {
        val t = GlideTouch()
        t.down(0, 50f, 70f, 0, onLetter = true, keyWidth = 100f, keyHeight = 140f, longPressMillis = 300, writes = true)
        // the caller asks at once: no key pressed
        assertTrue(t.gliding)
        // not moved: a stroke still (a dot, 主's first)
        assertEquals(Route.GLIDE, t.move(50f, 70f, 10))
        // past a long press: still the stroke's
        assertEquals(Route.GLIDE, t.move(51f, 71f, 1000))
        // out of the letter area: the same stroke
        assertEquals(Route.GLIDE, t.move(50f, 500f, 1100))
        assertEquals(Route.GLIDE, t.up(50f, 500f, cancelled = false))
        assertFalse(t.gliding)
        // a second finger is no key either while it writes
        t.down(0, 50f, 70f, 2000, onLetter = true, keyWidth = 100f, keyHeight = 140f, longPressMillis = 300, writes = true)
        assertEquals(Route.GLIDE, t.otherDown())
        assertEquals(Route.GLIDE, t.pointerUp(1, 0f, 0f))
    }

    @Test
    fun handwritingOnATouchOffTheLettersIsTheKeys() {
        // ⌫, space: onLetter false (the caller's letter area), no gap
        val t = GlideTouch()
        t.down(0, 50f, 70f, 0, onLetter = false, keyWidth = 100f, keyHeight = 140f, longPressMillis = 300, writes = true)
        assertFalse(t.gliding)
        assertEquals(Route.KEYS, t.move(50f, 300f, 20))
        assertEquals(Route.KEYS, t.up(50f, 300f, cancelled = false))
        // a split keyboard's gap is written on
        t.down(0, 50f, 70f, 0, onLetter = false, keyWidth = 100f, keyHeight = 140f, longPressMillis = 300, inGap = true, writes = true)
        assertTrue(t.gliding)
    }

    @Test
    fun handwritingOffTheLettersGlideAsBefore() {
        val t = GlideTouch()
        t.down(0, 50f, 70f, 0, onLetter = true, keyWidth = 100f, keyHeight = 140f, longPressMillis = 300, writes = false)
        assertFalse(t.gliding)
        assertEquals(Route.KEYS, t.move(52f, 70f, 10))
        assertEquals(Route.KEYS, t.up(52f, 70f, cancelled = false))
    }

    @Test
    fun aGlideCancelsTheKeysOnceThenIsItsOwn() {
        val t = touch()
        assertEquals(Route.KEYS, t.move(60f, 70f, 20))
        assertEquals(Route.CANCEL_KEYS, t.move(150f, 70f, 40))
        assertEquals(Route.GLIDE, t.move(250f, 70f, 60))
        assertEquals(Route.GLIDE, t.up(350f, 140f, cancelled = false))
        assertFalse(t.gliding)
    }

    @Test
    fun thePathIsInKeyWidthsAndRows() {
        val t = touch()
        t.move(150f, 70f, 40)
        t.up(250f, 140f, cancelled = false)
        val unitY = 140f / GlideLayout.ROW_HEIGHT
        assertArrayEquals(floatArrayOf(0.5f, 70f / unitY, 1.5f, 70f / unitY, 2.5f, 140f / unitY), t.takeGlided(), 1e-5f)
        // given once
        assertNull(t.takeGlided())
    }

    @Test
    fun aTapIsTheKeysAndLeavesNoPath() {
        val t = touch()
        assertEquals(Route.KEYS, t.up(52f, 70f, cancelled = false))
        assertNull(t.takeGlided())
    }

    @Test
    fun aCancelledGlideIsSwallowedButGivesNoPath() {
        val t = touch()
        t.move(150f, 70f, 40)
        assertEquals(Route.GLIDE, t.up(150f, 70f, cancelled = true))
        assertNull(t.takeGlided())
    }

    @Test
    fun offTheLettersNothingIsKept() {
        val t = touch(onLetter = false)
        assertEquals(Route.KEYS, t.move(250f, 70f, 40))
        assertEquals(0, t.count)
        assertEquals(GlideTouch.NO_POINTER, t.pointer)
    }

    @Test
    fun inTheSplitGapAStrokeStartsAGlideDoesNotATapIsTheKeys() {
        val t = GlideTouch()
        // a stroke drawn from the gap
        t.down(0, 50f, 70f, 0, onLetter = false, keyWidth = 100f, keyHeight = 140f, longPressMillis = 300, stroke = true, inGap = true)
        assertEquals(0, t.pointer)
        assertEquals(Route.CANCEL_KEYS, t.move(50f, 120f, 40))
        assertEquals(Route.GLIDE, t.up(50f, 140f, cancelled = false))
        assertEquals(6, t.takeGlided()?.size)
        // a tap there: the keys' as ever, no path
        t.down(0, 50f, 70f, 0, onLetter = false, keyWidth = 100f, keyHeight = 140f, longPressMillis = 300, stroke = true, inGap = true)
        assertEquals(Route.KEYS, t.up(50f, 70f, cancelled = false))
        assertNull(t.takeGlided())
        // glide typing: not from the gap
        t.down(0, 50f, 70f, 0, onLetter = false, keyWidth = 100f, keyHeight = 140f, longPressMillis = 300, inGap = true)
        assertEquals(GlideTouch.NO_POINTER, t.pointer)
        assertEquals(Route.KEYS, t.move(250f, 70f, 40))
        assertEquals(Route.KEYS, t.up(250f, 70f, cancelled = false))
        assertNull(t.takeGlided())
    }

    @Test
    fun twoFingersRollingAreTheKeys() {
        val t = touch()
        assertEquals(Route.KEYS, t.otherDown())
        assertEquals(Route.KEYS, t.move(250f, 70f, 40))
        assertEquals(Route.KEYS, t.pointerUp(1, 0f, 0f))
        // and it stops keeping places
        assertEquals(0, t.count)
    }

    @Test
    fun aHeldKeyMovedAfterItsLongPressIsTheKeys() {
        val t = touch()
        assertEquals(Route.KEYS, t.move(250f, 70f, 400))
        assertEquals(0, t.count)
        t.add(300f, 70f)
        assertEquals(0, t.count)
    }

    // a thumb resting while another finger writes (0.6.0's pad's): each lift of the writing finger a stroke, its next
    // down on the letters the next one, off them nothing; the thumb's lift ends it all, and a glide never writes again
    @Test
    fun handwritingWithAThumbRestingEachDownOnTheLettersWritesAgain() {
        val t = GlideTouch()
        t.down(0, 50f, 70f, 0, onLetter = true, keyWidth = 100f, keyHeight = 140f, longPressMillis = 300, writes = true)
        assertEquals(Route.GLIDE, t.otherDown())
        assertEquals(Route.GLIDE, t.pointerUp(0, 50f, 140f))
        assertTrue(t.writesAgain)
        assertTrue(t.swallows(first = false, last = false))
        assertFalse("off the letters", t.downAgain(0, 50f, 900f, 100, onLetter = false))
        assertTrue(t.downAgain(2, 150f, 70f, 200, onLetter = true))
        assertTrue(t.gliding)
        assertEquals(2, t.pointer)
        assertFalse(t.swallows(first = false, last = false))
        assertEquals(Route.GLIDE, t.move(250f, 70f, 220))
        assertEquals(Route.GLIDE, t.pointerUp(2, 250f, 70f))
        assertTrue(t.writesAgain)
        assertTrue(t.swallows(first = false, last = true))
        assertFalse(t.writesAgain)
        // a glide's finger lifting with another down: swallowed, nothing written again
        val g = touch()
        g.move(150f, 70f, 40)
        g.otherDown()
        g.pointerUp(0, 250f, 70f)
        assertFalse(g.writesAgain)
        assertFalse(g.downAgain(1, 50f, 70f, 100, onLetter = true))
    }

    @Test
    fun aFingerDownWhileGlidingIsSwallowed() {
        val t = touch()
        t.move(150f, 70f, 40)
        assertEquals(Route.GLIDE, t.otherDown())
        assertEquals(Route.GLIDE, t.pointerUp(1, 0f, 0f))
        assertTrue(t.gliding)
    }

    @Test
    fun theGlidingFingerLiftingFirstEndsItAndTheOtherIsSwallowedTillAllLift() {
        val t = touch()
        t.move(150f, 70f, 40)
        t.otherDown()
        assertEquals(Route.GLIDE, t.pointerUp(0, 250f, 70f))
        assertTrue(t.takeGlided() != null)
        assertTrue(t.swallows(first = false, last = false))
        assertTrue(t.swallows(first = false, last = true))
        assertFalse(t.swallows(first = false, last = false))
    }

    @Test
    fun aNewTouchEndsTheSwallowing() {
        val t = touch()
        t.move(150f, 70f, 40)
        t.otherDown()
        t.pointerUp(0, 250f, 70f)
        assertFalse(t.swallows(first = true, last = false))
    }

    @Test
    fun aMoveWithoutTheGlidingFingerIsStillTheGlides() {
        val t = touch()
        assertEquals(Route.KEYS, t.missing())
        t.move(150f, 70f, 40)
        assertEquals(Route.GLIDE, t.missing())
    }

    @Test
    fun aLongGlideKeepsEveryPlace() {
        val t = touch()
        t.move(150f, 70f, 40)
        repeat(1000) { t.add(it.toFloat(), 1f) }
        assertEquals(2 + 2 + 2000, t.count)
        t.up(0f, 0f, cancelled = false)
        assertEquals(2006, t.takeGlided()!!.size)
    }

    @Test
    fun theNextTouchStartsAfreshAfterAGlide() {
        val t = touch()
        t.move(150f, 70f, 40)
        t.up(250f, 70f, cancelled = false)
        t.down(pointerId = 0, x = 50f, y = 70f, time = 1000, onLetter = true, keyWidth = 100f, keyHeight = 140f, longPressMillis = 300)
        assertFalse(t.gliding)
        assertEquals(Route.KEYS, t.move(60f, 70f, 1020))
        assertEquals(Route.KEYS, t.up(60f, 70f, cancelled = false))
    }

    @Test
    fun newKeySizesTakeEffectOnTheNextTouch() {
        val t = touch()
        t.up(50f, 70f, cancelled = false)
        // keys half as wide: 50 across is a glide now
        t.down(pointerId = 0, x = 50f, y = 70f, time = 1000, onLetter = true, keyWidth = 50f, keyHeight = 140f, longPressMillis = 300)
        assertEquals(Route.CANCEL_KEYS, t.move(100f, 70f, 1020))
    }
}
