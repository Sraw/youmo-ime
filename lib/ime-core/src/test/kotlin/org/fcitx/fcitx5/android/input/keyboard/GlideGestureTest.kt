/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideGestureTest {

    private fun gesture() = GlideGesture(keyWidth = 100f, keyHeight = 150f, longPressMillis = 300)

    @Test
    fun aFingerAcrossTheLettersGlides() {
        val g = gesture()
        g.down(50f, 75f, 0, onLetter = true)
        assertFalse(g.move(100f, 75f, 50))
        assertTrue(g.move(140f, 75f, 80))
        // till it lifts, wherever it goes
        assertTrue(g.move(140f, 75f, 900))
        g.up()
        assertFalse(g.gliding)
    }

    @Test
    fun aSwipeUpOrDownIsTheKeysButFarDownGlides() {
        val g = gesture()
        g.down(50f, 75f, 0, onLetter = true)
        assertFalse(g.move(60f, 0f, 50))
        assertFalse(g.move(60f, 200f, 60))
        assertTrue(g.move(60f, 320f, 70))
    }

    @Test
    fun theNineKeysGlideARowUpOrDown() {
        val g = GlideGesture(keyWidth = 100f, keyHeight = 150f, longPressMillis = 300, downKeys = GlideGesture.NINE_KEYS_DOWN)
        g.down(50f, 75f, 0, onLetter = true)
        assertFalse(g.move(50f, 150f, 50))
        assertTrue(g.move(50f, 200f, 60))
    }

    @Test
    fun noGlideOffTheLettersAfterTheLongPressOrWithTwoFingers() {
        val off = gesture()
        off.down(50f, 75f, 0, onLetter = false)
        assertFalse(off.move(400f, 75f, 50))
        val held = gesture()
        held.down(50f, 75f, 0, onLetter = true)
        assertFalse(held.move(55f, 75f, 400))
        // the popup's from then on
        assertFalse(held.move(400f, 75f, 450))
        val two = gesture()
        two.down(50f, 75f, 0, onLetter = true)
        two.otherDown()
        assertFalse(two.move(400f, 75f, 50))
    }

    // keys 100 by 150: a stroke starts 0.3 of a key off, either way
    private fun stroke() = GlideGesture(keyWidth = 100f, keyHeight = 150f, longPressMillis = 300, stroke = true)

    @Test
    fun aStrokeStartsFarSoonerThanAGlide() {
        // a 竖 from the bottom row: a third of a key's height down, where a glide needs 1.6
        val down = stroke()
        down.down(50f, 75f, 0, onLetter = true)
        assertFalse(down.move(50f, 110f, 40))
        assertTrue(down.move(50f, 125f, 60))
        // a short 点, a quarter of a key each way
        val dot = stroke()
        dot.down(50f, 75f, 0, onLetter = true)
        assertTrue(dot.move(75f, 112f, 50))
        // a 横 a third of a key's width across, under half a glide's
        val across = stroke()
        across.down(50f, 75f, 0, onLetter = true)
        assertFalse(across.move(75f, 75f, 40))
        assertTrue(across.move(83f, 75f, 50))
        // a tap's wobble (a tenth of a key) is no stroke: the tap is the key's
        val tap = stroke()
        tap.down(50f, 75f, 0, onLetter = true)
        assertFalse(tap.move(58f, 82f, 30))
        assertFalse(tap.move(60f, 87f, 60))
    }

    @Test
    fun aSlowStrokeIsOneOnceStartedBeforeTheLongPress() {
        val slow = stroke()
        slow.down(50f, 75f, 0, onLetter = true)
        assertFalse(slow.move(55f, 100f, 150))
        assertTrue(slow.move(55f, 125f, 290))
        // drawn on well past the long press: still the stroke's
        assertTrue(slow.move(55f, 300f, 1200))
        // not started by then: the popup's
        val held = stroke()
        held.down(50f, 75f, 0, onLetter = true)
        assertFalse(held.move(52f, 80f, 310))
        assertFalse(held.move(50f, 300f, 400))
    }

    @Test
    fun aStrokeGoesAsFarAsItIsToldCappedInPixels() {
        // the first stroke written on the keys: half a key (KeyInkGesture.START)
        fun written(cap: Float = Float.POSITIVE_INFINITY) =
            GlideGesture(keyWidth = 100f, keyHeight = 150f, longPressMillis = 300, stroke = true, strokeKeys = 0.5f, strokeCap = cap)
        val across = written()
        across.down(50f, 75f, 0, onLetter = true)
        assertFalse(across.move(95f, 75f, 40))
        assertTrue(across.move(100f, 75f, 50))
        // tall keys: 60 pixels down starts it, under the half key's 75
        val tall = written(cap = 60f)
        tall.down(50f, 75f, 0, onLetter = true)
        assertFalse(tall.move(50f, 130f, 40))
        assertTrue(tall.move(50f, 135f, 50))
        // the cap is up or down alone: across a wide key (landscape, the nine keys) still half of it
        val wide = GlideGesture(keyWidth = 200f, keyHeight = 150f, longPressMillis = 300, stroke = true, strokeKeys = 0.5f, strokeCap = 60f)
        wide.down(50f, 75f, 0, onLetter = true)
        assertFalse(wide.move(140f, 75f, 40))
        assertTrue(wide.move(150f, 75f, 50))
    }
}
