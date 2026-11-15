/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

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

    // keys 100 by 150, 2 pixels a dp: a stroke drawn starts 12dp (24 pixels) off, any way
    private fun stroke() = GlideGesture(keyWidth = 100f, keyHeight = 150f, longPressMillis = 300, stroke = true, strokeDistance = 24f)

    @Test
    fun aStrokeStartsTwelveDpOffAnyWayAlikeWhateverTheKeysShape() {
        for (deg in 0 until 360 step 30) {
            val a = Math.toRadians(deg.toDouble())
            fun at(d: Float) = floatArrayOf(50f + d * cos(a).toFloat(), 75f + d * sin(a).toFloat())
            val g = stroke()
            g.down(50f, 75f, 0, onLetter = true)
            val short = at(23.8f)
            assertFalse("$deg", g.move(short[0], short[1], 40))
            val far = at(24.01f)
            assertTrue("$deg", g.move(far[0], far[1], 50))
        }
        // a tap's wobble (5dp) is no stroke: the tap is the key's
        val tap = stroke()
        tap.down(50f, 75f, 0, onLetter = true)
        assertFalse(tap.move(58f, 82f, 30))
        // a glide needs far more: 0.8 of a key across
        val glide = GlideGesture(keyWidth = 100f, keyHeight = 150f, longPressMillis = 300)
        glide.down(50f, 75f, 0, onLetter = true)
        assertFalse(glide.move(75f, 75f, 40))
    }

    @Test
    fun aSlowStrokeIsOneOnceStartedBeforeTheLongPress() {
        val slow = stroke()
        slow.down(50f, 75f, 0, onLetter = true)
        assertFalse(slow.move(52f, 90f, 150))
        assertTrue(slow.move(55f, 125f, 290))
        // drawn on well past the long press: still the stroke's
        assertTrue(slow.move(55f, 300f, 1200))
        // not started by then: the popup's
        val held = stroke()
        held.down(50f, 75f, 0, onLetter = true)
        assertFalse(held.move(52f, 80f, 310))
        assertFalse(held.move(50f, 300f, 400))
    }
}
