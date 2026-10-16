/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InkPadTest {

    private fun InkPad.stroke(time: Long, vararg xy: Float): InkPad.Read {
        down(xy[0], xy[1])
        for (i in 2 until xy.size step 2) move(xy[i], xy[i + 1])
        return up(time)!!
    }

    @Test
    fun `each stroke ended asks for the piece to be read, later reads numbered higher`() {
        val pad = InkPad()
        val first = pad.stroke(0, 0f, 0f, 10f, 0f)
        assertEquals(1, first.strokes.size)
        assertArrayEquals(floatArrayOf(0f, 0f, 10f, 0f), first.strokes[0], 0f)
        assertEquals(pad.piece, first.piece)
        val second = pad.stroke(100, 5f, -5f, 5f, 5f)
        assertEquals(2, second.strokes.size)
        assertEquals(first.piece, second.piece)
        assertTrue(second.id > first.id)
    }

    @Test
    fun `a stroke being drawn shows with the rest, and is dropped if cancelled`() {
        val pad = InkPad()
        pad.stroke(0, 0f, 0f, 1f, 1f)
        pad.down(2f, 2f)
        // a long stroke: more points than the first room for them
        repeat(100) { pad.move(it.toFloat(), 0f) }
        assertTrue(pad.drawing)
        assertEquals(2, pad.strokes.size)
        assertEquals(2 + 2 * 100, pad.strokes[1].size)
        pad.cancel()
        assertFalse(pad.drawing)
        assertEquals(1, pad.strokes.size)
        assertNull(pad.up(10))
        // a move with no pen down draws nothing
        pad.move(1f, 1f)
        assertEquals(1, pad.strokes.size)
    }

    @Test
    fun `the pen up long enough ends the piece, the next stroke another's, not while one is drawn`() {
        val pad = InkPad()
        val a = pad.stroke(1000, 0f, 0f, 1f, 1f)
        assertFalse(pad.finish(1000 + InkPad.FINISH_MILLIS - 1))
        assertTrue(pad.finish(1000 + InkPad.FINISH_MILLIS))
        assertTrue(pad.strokes.isEmpty())
        // done already, and nothing to end
        assertFalse(pad.finish(99_999))
        val b = pad.stroke(5000, 3f, 3f, 4f, 4f)
        assertEquals(1, b.strokes.size)
        assertTrue(b.piece > a.piece)
        // pieces go on across pads
        assertTrue(InkPad().piece > b.piece)
        pad.down(2f, 2f)
        assertFalse(pad.finish(1_000_000))
    }

    @Test
    fun `the pause that ends a piece is the user's to set`() {
        val pad = InkPad()
        pad.finishMillis = 300
        pad.stroke(1000, 0f, 0f, 1f, 1f)
        assertFalse(pad.finish(1299))
        assertTrue(pad.finish(1300))
        assertThrows(IllegalArgumentException::class.java) { pad.finishMillis = 0 }
    }

    @Test
    fun `backspace takes the last stroke back, the rest read again, none read as nothing`() {
        val pad = InkPad()
        pad.stroke(0, 0f, 0f, 1f, 1f)
        val two = pad.stroke(10, 2f, 2f, 3f, 3f)
        val one = pad.undo(20)!!
        assertEquals(1, one.strokes.size)
        assertEquals(two.piece, one.piece)
        assertTrue(one.id > two.id)
        assertTrue(pad.undo(30)!!.strokes.isEmpty())
        assertNull(pad.undo(40))
        // not while a stroke is drawn
        pad.down(0f, 0f)
        assertNull(pad.undo(50))
    }

    @Test
    fun `the pause after backspace counts from it, the stroke taken back written again in the same piece`() {
        val pad = InkPad()
        pad.stroke(1000, 0f, 0f, 1f, 1f)
        pad.stroke(1500, 2f, 2f, 3f, 3f)
        pad.undo(2000)
        // the pen up long enough since the stroke left went up, not yet since the backspace
        assertFalse(pad.finish(1500 + InkPad.FINISH_MILLIS))
        assertFalse(pad.finish(2000 + InkPad.FINISH_MILLIS - 1))
        assertTrue(pad.finish(2000 + InkPad.FINISH_MILLIS))
    }

    @Test
    fun `drawing reads the strokes where they are, the finished ones counted as they change`() {
        val pad = InkPad()
        var seen = pad.edits
        fun changed() = (pad.edits != seen).also { seen = pad.edits }
        pad.stroke(0, 0f, 0f, 1f, 1f)
        assertTrue(changed())
        pad.down(2f, 2f)
        repeat(100) { pad.move(it.toFloat(), 0f) }
        // the stroke under the pen is none of the finished ones
        assertFalse(changed())
        assertEquals(1, pad.finished.size)
        assertArrayEquals(pad.strokes[0], pad.finished[0], 0f)
        var drawn = FloatArray(0)
        pad.underPen { xy, n -> drawn = xy.copyOf(n) }
        assertArrayEquals(pad.strokes[1], drawn, 0f)
        pad.cancel()
        assertFalse(changed())
        var called = false
        pad.underPen { _, _ -> called = true }
        assertFalse(called)
        pad.stroke(10, 3f, 3f, 4f, 4f)
        assertTrue(changed())
        pad.undo(20)
        assertTrue(changed())
        pad.next()
        assertTrue(changed())
        assertTrue(pad.finished.isEmpty())
    }

    @Test
    fun `the piece being written is wiped once the engine ends it, not for an earlier one`() {
        val pad = InkPad()
        val read = pad.stroke(0, 0f, 0f, 1f, 1f)
        assertFalse(pad.ended(read.piece - 1))
        assertEquals(1, pad.strokes.size)
        pad.down(5f, 5f)
        assertTrue(pad.ended(read.piece))
        assertTrue(pad.strokes.isEmpty())
        assertFalse(pad.drawing)
        assertTrue(pad.piece > read.piece)
        // ended as it is: hidden, or written on another way
        pad.stroke(10, 0f, 0f, 1f, 1f)
        val before = pad.piece
        pad.next()
        assertTrue(pad.strokes.isEmpty())
        assertTrue(pad.piece > before)
    }
}
