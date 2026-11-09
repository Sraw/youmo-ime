/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.fcitx.fcitx5.android.data.theme.BoardImages
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

// a gradient drawn in Skia's pixels: a paint's alpha scales its shader's, so the paint under it must be opaque
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScenePainterGradientTest {
    private val look = TestThemes.PixelLight.look

    private fun drawn(op: Op): Bitmap {
        val painter = ScenePainter(View(RuntimeEnvironment.getApplication()), look, BoardImages.NONE, SceneFonts(look))
        val b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        painter.draw(Canvas(b), listOf(op))
        return b
    }

    @Test
    fun aGradientOfOneColourIsThatColour() {
        val c = Argb(0xFF336699.toInt())
        val b = drawn(Op.Gradient(Shape.Rect(Box(0.0, 0.0, SIZE.toDouble(), SIZE.toDouble())), 0.0, listOf(c to 0.0, c to 1.0)))
        assertEquals(0xFF336699.toInt(), b.getPixel(SIZE / 2, SIZE / 2))
    }

    // 180deg: top to bottom, red at the top, blue at the bottom; a translucent stop keeps its own alpha
    @Test
    fun aGradientGoesFromItsFirstStopToItsLast() {
        val red = Argb(0xFFFF0000.toInt())
        val blue = Argb(0x800000FF.toInt())
        val b = drawn(Op.Gradient(Shape.Rect(Box(0.0, 0.0, SIZE.toDouble(), SIZE.toDouble())), 180.0, listOf(red to 0.0, blue to 1.0)))
        // the first and last rows' pixel centres are half a pixel in: the ends' colours, near enough
        near(0xFFFF0000.toInt(), b.getPixel(SIZE / 2, 0))
        near(0x800000FF.toInt(), b.getPixel(SIZE / 2, SIZE - 1))
    }

    private fun near(want: Int, got: Int) {
        for (shift in listOf(ALPHA_SHIFT, RED_SHIFT, GREEN_SHIFT, 0)) {
            assertEquals("#%08x against #%08x".format(want, got), (want ushr shift and BYTE).toDouble(), (got ushr shift and BYTE).toDouble(), NEAR)
        }
    }

    private companion object {
        const val SIZE = 40
        const val ALPHA_SHIFT = 24
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
        const val BYTE = 0xFF
        const val NEAR = 8.0
    }
}
