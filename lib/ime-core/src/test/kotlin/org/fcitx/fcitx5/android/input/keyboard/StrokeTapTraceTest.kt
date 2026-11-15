/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrokeTapTraceTest {

    // 2 px a dp; a cell 80x128 px (40x64 dp) at 100,200; touch slop 16 px
    private fun trace() = StrokeTapTrace(density = 2f, touchSlop = 16f)

    private fun StrokeTapTrace.downAt(x: Float, y: Float, time: Long, strokeMode: Boolean = true) =
        down(x, y, time, 100f, 200f, 180f, 328f, "TextKeyboard", strokeMode, 300)

    @Test
    fun aTapIsItsDriftAndWhereItLandedInDp() {
        val t = trace()
        t.downAt(150f, 270f, 1000)
        t.move(154f, 267f)
        t.move(156f, 262f)
        assertEquals(
            "kb=TextKeyboard mode=1 lp=300 cell=40.0x64.0 at=5.0,3.0 dur=90 gap=-1 far=5.0 dir=53 lift=3.0,-4.0 start=- out=0 multi=0 res=L",
            t.up(1090, cancelled = false),
        )
    }

    @Test
    fun theGapIsFromTheLastLift() {
        val t = trace()
        t.downAt(140f, 264f, 1000)
        t.up(1080, cancelled = false)
        t.downAt(140f, 264f, 1300)
        assertEquals("gap=220", t.up(1380, cancelled = false)!!.split(' ').first { it.startsWith("gap=") })
        assertEquals("bksp since=120", t.backspace(1500))
    }

    @Test
    fun aStrokeStartedIsWhatItWasReadAs() {
        val t = trace()
        t.downAt(140f, 264f, 0)
        t.move(160f, 264f)
        t.started(40)
        t.move(200f, 266f)
        t.otherFinger()
        t.read('h')
        val line = t.up(150, cancelled = false)!!
        assert(line.endsWith("far=30.0 dir=-2 lift=30.0,1.0 start=40@10.0 out=1 multi=1 res=Sh")) { line }
    }

    @Test
    fun anUnreadStrokeAGlideALongPressOffTheKeyAndACancel() {
        val t = trace()
        t.downAt(140f, 264f, 0)
        t.move(140f, 200f)
        t.started(30)
        t.read(null)
        assert(t.up(100, false)!!.endsWith("res=U"))

        t.downAt(140f, 264f, 0)
        t.started(30)
        t.tapped()
        assert(t.up(100, false)!!.endsWith("res=T"))

        t.downAt(140f, 264f, 0, strokeMode = false)
        t.started(30)
        assert(t.up(100, false)!!.endsWith("res=G"))

        t.downAt(140f, 264f, 0)
        assert(t.up(400, false)!!.endsWith("res=P"))

        // past the cell and its slop: 100 - 16 = 84 is the edge
        t.downAt(104f, 264f, 0)
        t.move(83f, 264f)
        assert(t.up(100, false)!!.endsWith("out=1 multi=0 res=O"))

        t.downAt(140f, 264f, 0)
        assert(t.up(50, true)!!.endsWith("res=C"))
    }

    @Test
    fun noLineWithoutATouchDown() {
        val t = trace()
        t.move(1f, 1f)
        t.started(1)
        t.otherFinger()
        assertNull(t.up(10, cancelled = false))
    }
}
