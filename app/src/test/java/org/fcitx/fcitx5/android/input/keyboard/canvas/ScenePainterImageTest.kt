/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

// a key's image not decoded yet: its kind's colour shown meanwhile, and the painter told to draw again once in
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScenePainterImageTest {
    private val look = ThemePreset.PixelLight.look
    private val face = Op.NineSlice(Shape.Rect(Box(0.0, 0.0, 20.0, 20.0)), "k.png", listOf(1, 1, 1, 1), 1, Argb(0xFF336699.toInt()))

    private fun drawn(images: BoardImages): Pair<ScenePainter, Bitmap> {
        val painter = ScenePainter(View(RuntimeEnvironment.getApplication()), look, images, SceneFonts(look))
        val b = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        painter.draw(Canvas(b), listOf(face))
        return painter to b
    }

    @Test
    fun anImageNotInYetShowsItsColourAndIsDrawnAgain() {
        val (painter, b) = drawn(BoardImages())
        assertEquals(0xFF336699.toInt(), b.getPixel(10, 10))
        assertTrue(painter.missed)
    }

    // one that will never come (no images on this keyboard): its colour, not drawn again for nothing
    @Test
    fun anImageThatIsNotThereShowsItsColourOnly() {
        val (painter, b) = drawn(BoardImages.NONE)
        assertEquals(0xFF336699.toInt(), b.getPixel(10, 10))
        assertFalse(painter.missed)
    }
}
