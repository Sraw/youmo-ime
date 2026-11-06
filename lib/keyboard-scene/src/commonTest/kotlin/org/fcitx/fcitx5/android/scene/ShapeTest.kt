/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Shape.Path.Op.Close
import org.fcitx.fcitx5.android.scene.Shape.Path.Op.LineTo
import org.fcitx.fcitx5.android.scene.Shape.Path.Op.MoveTo
import org.fcitx.fcitx5.android.scene.theme.Argb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 060-render-v2 §3.1: a key's shape and touch area are shapes, Rect now, Path for what comes (arc)
class ShapeTest {

    @Test
    fun boxes() {
        val b = Box(0.0, 0.0, 10.0, 20.0)
        assertTrue(b.contains(0.0, 0.0))
        assertFalse(b.contains(10.0, 5.0), "half-open: the next key's")
        assertEquals(Box(1.0, 2.0, 7.0, 16.0), b.inset(1.0, 2.0, 3.0, 4.0))
        assertEquals(Box(5.0, 5.0, 10.0, 20.0), b.intersect(Box(5.0, 5.0, 30.0, 30.0)))
        assertEquals(Box(0.0, 0.0, 30.0, 30.0), b.union(Box(5.0, 5.0, 30.0, 30.0)))
        assertFalse(Box(0.0, 0.0, 0.0, 5.0).hasSize)
    }

    @Test
    fun aRoundedRectLeavesItsCornersOut() {
        val r = Shape.Rect(Box(0.0, 0.0, 100.0, 100.0), listOf(20.0, 0.0, 20.0, 0.0))
        assertFalse(r.contains(1.0, 1.0), "outside the top-left curve")
        assertTrue(r.contains(99.0, 1.0), "a square top-right")
        assertTrue(r.contains(50.0, 50.0))
        assertFalse(r.contains(99.0, 99.0), "outside the bottom-right curve")
        assertTrue(r.contains(1.0, 99.0))
        assertEquals(r.box, r.bounds)
    }

    @Test
    fun aPathByEvenOdd() {
        // a triangle with a triangle cut out
        val p = Shape.Path(
            listOf(
                MoveTo(0.0, 0.0), LineTo(100.0, 0.0), LineTo(0.0, 100.0), Close,
                MoveTo(10.0, 10.0), LineTo(30.0, 10.0), LineTo(10.0, 30.0), Close,
            ),
        )
        assertEquals(Box(0.0, 0.0, 100.0, 100.0), p.bounds)
        assertTrue(p.contains(5.0, 50.0))
        assertFalse(p.contains(15.0, 15.0), "the hole")
        assertFalse(p.contains(80.0, 80.0))
        assertFalse(Shape.Path(emptyList()).contains(0.0, 0.0))
    }

    @Test
    fun sceneTextNumbersAreTheSameEverywhere() {
        assertEquals("12", SceneText.n(12.0))
        assertEquals("12.5", SceneText.n(12.5))
        assertEquals("12.05", SceneText.n(12.049))
        assertEquals("-0.25", SceneText.n(-0.25))
        assertEquals("0", SceneText.n(-0.001))
        assertEquals("1078.88", SceneText.n(411 * 2.625))
    }

    @Test
    fun sceneTextOps() {
        val r = Shape.Rect(Box(0.0, 0.0, 1.0, 2.0))
        val c = Argb(0x11223344)
        assertEquals("gradient rect(0,0,1,2) 90 #11223344@0 #11223344@1", SceneText.op(Op.Gradient(r, 90.0, listOf(c to 0.0, c to 1.0))))
        assertEquals("nine-slice rect(0,0,1,2) \"k.png\" 1,2,3,4 3", SceneText.op(Op.NineSlice(r, "k.png", listOf(1, 2, 3, 4), 3)))
        assertEquals("picture rect(0,0,1,2) blur=- brightness=50", SceneText.op(Op.Picture(r, null, 50.0)))
        assertEquals("ripple rect(0,0,1,2) #11223344", SceneText.op(Op.Ripple(r, c)))
        assertEquals("text \"a\\\"\" 1,2 system 400 3 #11223344 scale-x=0.5 rotate=90", SceneText.op(Op.Text("a\"", 1.0, 2.0, "system", 400, 3.0, c, 0.5, 90.0)))
        assertEquals("icon x 0,0,1,2 #11223344 rotate=45", SceneText.op(Op.Icon("x", r.box, c, 45.0)))
        assertEquals("path(M0,0 L1,0 Z)", SceneText.shape(Shape.Path(listOf(MoveTo(0.0, 0.0), LineTo(1.0, 0.0), Close))))
        val scene = Scene(1.0, 1.0, emptyList(), emptyList(), listOf(SlotNode("handwriting-pad", null, Box(0.0, 0.0, 1.0, 1.0))))
        assertEquals("scene 1x1\nslot handwriting-pad 0,0,1,1\n", SceneText.of(scene))
    }
}
