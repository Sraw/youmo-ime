/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.scene

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

    @Test
    fun aKeyNeverPressedDrawsItsNormalFaceAlone() {
        assertEquals(listOf(Face(pressed = false, alpha = 1f, record = true)), CanvasFrame.faces(false, normalRecorded = false, pressedRecorded = false))
        assertEquals(listOf(Face(pressed = false, alpha = 1f, record = false)), CanvasFrame.faces(false, normalRecorded = true, pressedRecorded = false))
    }

    @Test
    fun aPressRecordsThePressedFaceOnceAndSwapsTheAlpha() {
        // the first press: the pressed face recorded, the normal one kept in the tree at 0
        assertEquals(
            listOf(Face(pressed = false, alpha = 0f, record = false), Face(pressed = true, alpha = 1f, record = true)),
            CanvasFrame.faces(true, normalRecorded = true, pressedRecorded = false),
        )
        // let go: both still drawn, the pressed one at 0, nothing recorded
        assertEquals(
            listOf(Face(pressed = false, alpha = 1f, record = false), Face(pressed = true, alpha = 0f, record = false)),
            CanvasFrame.faces(false, normalRecorded = true, pressedRecorded = true),
        )
        // pressed again: the alphas swapped back
        assertEquals(
            listOf(Face(pressed = false, alpha = 0f, record = false), Face(pressed = true, alpha = 1f, record = false)),
            CanvasFrame.faces(true, normalRecorded = true, pressedRecorded = true),
        )
    }

    @Test
    fun aRecordingLostIsRecordedAgain() {
        // the renderer let go of the normal face's list (memory trimmed): recorded again, shown
        assertEquals(
            listOf(Face(pressed = false, alpha = 1f, record = true), Face(pressed = true, alpha = 0f, record = false)),
            CanvasFrame.faces(false, normalRecorded = false, pressedRecorded = true),
        )
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
        // a shadow blurred 12 px reaches 7 past the bounds
        val shadow = Op.Shadow(Shape.Rect(Box(105.0, 60.0, 195.0, 150.0), listOf(0.0, 0.0, 0.0, 0.0)), 12.0, black)
        assertEquals(Box(93.0, 48.0, 207.0, 162.0), CanvasFrame.extent(bounds, listOf(shadow), 0.0))
        // a text: a guess wider than its ink, never less than the bounds
        val text = Op.Text("q", 140.0, 110.0, "system", 400, 20.0, black)
        val t = CanvasFrame.extent(bounds, listOf(text), 0.0)
        assertTrue(t.left <= 120.0 && t.top <= 80.0 && t.right >= 180.0 && t.bottom >= 120.0)
        assertTrue(CanvasFrame.showsPicture(listOf(Op.Fill(face, black), Op.Picture(face, null, null))))
        assertFalse(CanvasFrame.showsPicture(listOf(Op.Fill(face, black))))
    }
}
