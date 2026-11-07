/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.Uat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/** The voice mask's frosted glass (uat/12 VO-05): the blur, the small picture's coordinates, what it takes. */
@RunWith(RobolectricTestRunner::class)
class VoiceGlassTest {

    @Test
    @Uat("VO-05")
    fun aBoxBlurAveragesItsNeighboursAndHoldsTheEdges() {
        // one white pixel in black, radius 1: a 3 × 3 square of a ninth each
        val w = 5
        val px = IntArray(w * w) { 0xFF000000.toInt() }
        px[2 * w + 2] = 0xFFFFFFFF.toInt()
        VoiceGlass.boxBlur(px, w, w, 1)
        val ninth = 0xFF000000.toInt() or (255 / 3 / 3 * 0x010101)
        for (y in 0 until w) for (x in 0 until w) {
            val inside = x in 1..3 && y in 1..3
            assertEquals("($x, $y)", if (inside) ninth else 0xFF000000.toInt(), px[y * w + x])
        }
        // one colour all over stays it, the edges held rather than faded to nothing
        val flat = IntArray(4 * 3) { 0x80336699.toInt() }
        VoiceGlass.boxBlur(flat, 4, 3, 2)
        flat.forEach { assertEquals(0x80336699.toInt(), it) }
        // a step: the middle between the two sides, alpha on its own
        val step = intArrayOf(0x00000000, 0x00000000, 0xFF0000FF.toInt(), 0xFF0000FF.toInt())
        VoiceGlass.boxBlur(step, 4, 1, 1)
        assertEquals(0x55000055, step[1])
        assertEquals(0xAA0000AA.toInt(), step[2])
    }

    @Test
    @Uat("VO-05")
    fun theSmallPictureCoversTheCard() {
        assertEquals(1, VoiceGlass.small(1))
        assertEquals(1, VoiceGlass.small(8))
        assertEquals(2, VoiceGlass.small(9))
        assertEquals(135, VoiceGlass.small(1080))
        // drawn back SCALE times larger, the small picture covers all of the card
        assertTrue(VoiceGlass.small(1081) * VoiceGlass.SCALE >= 1081)
    }

    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun view(color: Int) = View(ctx).apply { setBackgroundColor(color) }

    // [board] laid out [w] × [h], each child at its rect
    private fun lay(board: FrameLayout, w: Int, h: Int, vararg at: Pair<View, Rect>) {
        at.forEach { (v, _) -> board.addView(v) }
        board.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        board.layout(0, 0, w, h)
        at.forEach { (v, r) -> v.layout(r.left, r.top, r.right, r.bottom) }
    }

    @Test
    @Uat("VO-05")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun whatIsUnderTheCardIsTakenWhereItIs() {
        val board = FrameLayout(ctx)
        // left red, right blue; the card over the right half, from x 320, green itself but not shown yet
        val card = view(Color.GREEN).apply { alpha = 0f }
        lay(board, 640, 320, view(Color.RED) to Rect(0, 0, 320, 320), view(Color.BLUE) to Rect(320, 0, 640, 320), card to Rect(320, 0, 640, 320))
        val shot = VoiceGlass.capture(board, card)!!
        assertEquals(VoiceGlass.small(320), shot.width)
        // all blue: the card's place across, not the board's left; the card itself left out
        assertEquals(Color.BLUE, shot.getPixel(0, 0))
        assertEquals(Color.BLUE, shot.getPixel(shot.width - 1, shot.height - 1))
        shot.recycle()
    }

    @Test
    @Uat("VO-05")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun childrenAreTakenAsTheyAreSeen() {
        val board = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        // a red one stretched twice from its left and moved; a white one half seen; a green one not seen (an old mask faded out)
        val moved = view(Color.RED).apply {
            pivotX = 0f
            scaleX = 2f
            translationX = 80f
        }
        val half = view(Color.WHITE).apply { alpha = 0.5f }
        val gone = view(Color.GREEN).apply { alpha = 0f }
        val card = view(Color.YELLOW).apply { alpha = 0f }
        lay(
            board, 640, 640,
            moved to Rect(0, 0, 160, 320),
            half to Rect(0, 320, 640, 640),
            gone to Rect(480, 0, 640, 320),
            card to Rect(0, 0, 640, 640),
        )
        val shot = VoiceGlass.capture(board, card)!!
        // the red one 160 wide at 0: seen from 80 to 400, so black left of it, red in it
        assertEquals(Color.BLACK, shot.getPixel(0, 5))
        assertEquals(Color.RED, shot.getPixel(30, 5))
        // not the green one: the black under it
        assertEquals(Color.BLACK, shot.getPixel(shot.width - 1, 0))
        // the white one at half: grey
        val grey = shot.getPixel(40, shot.height - 1)
        assertTrue("grey ${Integer.toHexString(grey)}", Color.red(grey) in 0x70..0x90)
        shot.recycle()
    }

    @Test
    @Uat("VO-05")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun drawnBackItsCornersAreTheBoardsUnderThem() {
        val board = FrameLayout(ctx)
        val colors = intArrayOf(Color.RED, Color.BLUE, Color.GREEN, Color.WHITE)
        val card = view(Color.YELLOW).apply { alpha = 0f }
        // four quarters, the card over where they meet (240..400 each way): taken from anywhere else, x or y,
        // a corner comes out another quarter's colour
        lay(
            board, 640, 640,
            view(colors[0]) to Rect(0, 0, 320, 320),
            view(colors[1]) to Rect(320, 0, 640, 320),
            view(colors[2]) to Rect(0, 320, 320, 640),
            view(colors[3]) to Rect(320, 320, 640, 640),
            card to Rect(240, 240, 400, 400),
        )
        val glass = VoiceGlass(Color.TRANSPARENT)
        glass.take(board, card)
        glass.setBounds(0, 0, 160, 160)
        val out = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        glass.draw(Canvas(out))
        assertEquals(colors[0], out.getPixel(0, 0))
        assertEquals(colors[1], out.getPixel(159, 0))
        assertEquals(colors[2], out.getPixel(0, 159))
        assertEquals(colors[3], out.getPixel(159, 159))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nothingTakenTheMaskIsOpaque() {
        val board = FrameLayout(ctx)
        val card = view(Color.YELLOW)
        // never laid out: nothing to take
        board.addView(card)
        val glass = VoiceGlass(0x80336699.toInt())
        glass.take(board, card)
        glass.setBounds(0, 0, 8, 8)
        val out = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        glass.draw(Canvas(out))
        assertEquals(0xFF336699.toInt(), out.getPixel(4, 4))
    }
}
