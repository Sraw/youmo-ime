/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.Uat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun theSmallPictureCoversTheCardAndMapsBack() {
        assertEquals(1, VoiceGlass.small(1))
        assertEquals(1, VoiceGlass.small(8))
        assertEquals(2, VoiceGlass.small(9))
        assertEquals(135, VoiceGlass.small(1080))
        // drawn back SCALE times larger, the small picture covers all of the card
        assertEquals(true, VoiceGlass.small(1081) * VoiceGlass.SCALE >= 1081)
        // the board's point (x, y) in the small picture: the card's place taken away, then scaled
        val card = Rect(40, 160, 1040, 960)
        assertArrayEquals(floatArrayOf(0f, 0f), VoiceGlass.toSmall(card, 40f, 160f), 0f)
        assertArrayEquals(floatArrayOf(10f, 5f), VoiceGlass.toSmall(card, 120f, 200f), 0f)
    }

    @Test
    @Uat("VO-05")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun whatIsUnderTheCardIsTakenNotTheCard() {
        val ctx = RuntimeEnvironment.getApplication()
        val board = FrameLayout(ctx)
        // a red top half, a blue bottom half; the card over the bottom half, green itself
        val top = View(ctx).apply { setBackgroundColor(Color.RED) }
        val bottom = View(ctx).apply { setBackgroundColor(Color.BLUE) }
        val card = View(ctx).apply { setBackgroundColor(Color.GREEN) }
        board.addView(top)
        board.addView(bottom)
        board.addView(card)
        board.measure(View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY))
        board.layout(0, 0, 160, 320)
        top.layout(0, 0, 160, 160)
        bottom.layout(0, 160, 160, 320)
        card.layout(0, 160, 160, 320)
        val shot = VoiceGlass.capture(board, card)
        assertNotNull(shot)
        shot!!
        assertEquals(VoiceGlass.small(160), shot.width)
        assertEquals(VoiceGlass.small(160), shot.height)
        // all blue: the card's own green left out, the red above it not in its place
        assertEquals(Color.BLUE, shot.getPixel(10, 10))
        assertEquals(Color.BLUE, shot.getPixel(0, 0))
        shot.recycle()
    }
}
