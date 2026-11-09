/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.scene.CanvasFrame.Face
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasFrameTest {

    private class Row(val why: String, val pressed: Boolean, val normal: Boolean, val pressedFace: Boolean, val faces: List<Face>)

    @Test
    fun theFacesDrawnAndRecorded() {
        fun n(alpha: Float, record: Boolean) = Face(pressed = false, alpha = alpha, record = record)
        fun p(alpha: Float, record: Boolean) = Face(pressed = true, alpha = alpha, record = record)
        val rows = listOf(
            Row("never pressed, nothing recorded: the normal face alone, recorded", false, false, false, listOf(n(1f, true))),
            Row("never pressed, recorded: the normal face alone as it is", false, true, false, listOf(n(1f, false))),
            Row("the first press: the pressed face recorded, the normal one kept in the tree at 0", true, true, false, listOf(n(0f, false), p(1f, true))),
            Row("let go: both still drawn, the pressed one at 0, nothing recorded", false, true, true, listOf(n(1f, false), p(0f, false))),
            Row("pressed again: the alphas swapped back", true, true, true, listOf(n(0f, false), p(1f, false))),
            Row("the renderer let go of the normal face's list (memory trimmed): recorded again, shown", false, false, true, listOf(n(1f, true), p(0f, false))),
        )
        assertRows(rows, { it.why }) {
            assertEquals(it.faces, CanvasFrame.faces(it.pressed, normalRecorded = it.normal, pressedRecorded = it.pressedFace))
        }
    }

    // every face of any key, pressed or not, ever recorded or not: exactly one shown, at alpha 1
    @Test
    fun oneFaceShownWhateverWasRecorded() {
        for (pressed in listOf(false, true)) for (n in listOf(false, true)) for (p in listOf(false, true)) {
            val faces = CanvasFrame.faces(pressed, n, p)
            assertEquals("$pressed $n $p", 1, faces.count { it.alpha == 1f })
            assertEquals(pressed, faces.single { it.alpha == 1f }.pressed)
            assertTrue(faces.first().let { !it.pressed })
        }
    }

    @Test
    fun aNodesExtentItsBoundsItsShadowsBlurAndPadding() {
        val bounds = Box(100.0, 50.0, 200.0, 150.0)
        val face = Shape.Rect(Box(105.0, 55.0, 195.0, 145.0), listOf(0.0, 0.0, 0.0, 0.0))
        val black = Argb(0xff000000.toInt())
        assertEquals(Box(99.0, 49.0, 201.0, 151.0), CanvasFrame.extent(bounds, listOf(Op.Fill(face, black)), 1.0))
        // a shadow blurred 12 px, moved 5 down: 7 past the bounds at the sides, 12 below
        val shadow = Op.Shadow(Shape.Rect(Box(105.0, 60.0, 195.0, 150.0), listOf(0.0, 0.0, 0.0, 0.0)), 12.0, black)
        assertEquals(Box(93.0, 48.0, 207.0, 162.0), CanvasFrame.extent(bounds, listOf(shadow), 0.0))
        // an inner shadow: clipped to its face, its ring however far out (T4b)
        val inner = Op.InnerShadow(face, face.moved(0.0, 8.0), Box(0.0, 0.0, 400.0, 400.0), 12.0, black)
        assertEquals(bounds, CanvasFrame.extent(bounds, listOf(inner), 0.0))
        // a text: a guess wider than its ink, never less than the bounds
        val text = Op.Text("q", 140.0, 110.0, "system", 400, 20.0, black)
        val t = CanvasFrame.extent(bounds, listOf(text), 0.0)
        assertTrue(t.left <= 120.0 && t.top <= 80.0 && t.right >= 180.0 && t.bottom >= 120.0)
        // turned: the square round its point reaching the farthest corner of what it was unturned
        val icon = Op.Icon("x", Box(10.0, 10.0, 40.0, 50.0), black, rotation = 45.0)
        assertEquals(Box(0.0, 5.0, 50.0, 55.0), CanvasFrame.extent(Box(10.0, 10.0, 40.0, 50.0), listOf(icon), 0.0))
        val turned = CanvasFrame.extent(bounds, listOf(text.copy(rotation = 90.0)), 0.0)
        // unturned it reached 20 × 1.5 above the baseline and well right of x: turned, as far below and left
        assertTrue("$turned", turned.bottom >= 110.0 + 30.0 && turned.left <= 140.0 - 40.0)
        assertTrue(CanvasFrame.showsPicture(listOf(Op.Fill(face, black), Op.Picture(face, null, null))))
        assertFalse(CanvasFrame.showsPicture(listOf(Op.Fill(face, black))))
    }
}
