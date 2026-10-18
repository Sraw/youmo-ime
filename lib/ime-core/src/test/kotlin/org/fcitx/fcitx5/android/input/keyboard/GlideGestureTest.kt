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
        val g = GlideGesture(keyWidth = 100f, keyHeight = 150f, longPressMillis = 300, down = GlideGesture.NINE_KEYS_DOWN)
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
}
