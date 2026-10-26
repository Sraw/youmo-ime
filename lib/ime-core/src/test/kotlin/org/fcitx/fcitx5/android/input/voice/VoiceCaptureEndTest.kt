/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptureEndTest {

    private val window = 512L

    // windows read until it stops, from [read]; the samples read then
    private fun readUntilOver(end: VoiceCaptureEnd, from: Long, now: Long = 0): Long {
        var read = from
        while (!end.over(read, now)) read += window
        return read
    }

    @Test
    fun notLiftedNeverStops() {
        val end = VoiceCaptureEnd(16000)
        assertNull(end.stopAt)
        assertFalse(end.over(1_000_000, 1_000_000))
    }

    @Test
    fun aTailOfThreeTenthsAtEverySampleRate() = assertRows(
        listOf(16000 to 4800, 48000 to 14400, 8000 to 2400),
        { (rate, _) -> "$rate Hz" },
    ) { (rate, tail) -> assertEquals(tail, VoiceCaptureEnd(rate).tail) }

    // lifted at frame [lift], seen with [read] read: stops at [stopAt], read up to [until]
    private data class Lift(val why: String, val rate: Int, val lift: Long, val read: Long, val stopAt: Long, val until: Long)

    @Test
    @Uat("VO-23")
    fun fromTheLiftsFrameToTheNextWholeWindow() = assertRows(
        listOf(
            Lift("16 kHz: 14_336 + 512, the first boundary at or past 14_800", 16000, 10_000, 10_240, 14_800, 14_848),
            Lift("48 kHz: the same times in its own frames", 48000, 30_000, 30_720, 44_400, 44_544),
        ),
        Lift::why,
    ) { c ->
        val end = VoiceCaptureEnd(c.rate)
        end.lift(c.lift, c.read, 0)
        assertEquals(c.stopAt, end.stopAt)
        assertEquals(c.until, readUntilOver(end, c.read))
    }

    @Test
    fun withoutATimestampFromWhatWasReadWhenSeen() {
        val end = VoiceCaptureEnd(16000)
        end.lift(null, 10_240, 0)
        assertEquals(15_040L, end.stopAt)
        assertEquals(15_360L, readUntilOver(end, 10_240))
    }

    @Test
    fun aTimestampIsBelievedOnlySoFar() = assertRows(
        listOf(
            Lift("far behind: never before what is read", 16000, 0, 20_000, 20_000, 20_000),
            Lift("ahead of what is read: at most the tail and 200 ms of latency more", 16000, 50_000, 20_000, 20_000L + 4800 + 3200, 28_192),
            Lift("ahead at 48 kHz: the same 200 ms in its frames", 48000, 200_000, 30_720, 30_720L + 14_400 + 9_600, 54_784),
            Lift("a little behind (the capture path's latency): from the lift itself", 16000, 19_000, 20_000, 23_800, 24_096),
        ),
        Lift::why,
    ) { c ->
        val end = VoiceCaptureEnd(c.rate)
        end.lift(c.lift, c.read, 0)
        assertEquals(c.stopAt, end.stopAt)
        assertEquals(c.until, readUntilOver(end, c.read))
    }

    @Test
    fun theFirstLiftCounts() {
        val end = VoiceCaptureEnd(16000)
        end.lift(null, 1024, 0)
        end.lift(null, 9000, 0)
        assertEquals(5824L, end.stopAt)
    }

    @Test
    fun aWindowSeesTheLiftBeforeItIsCounted() {
        val end = VoiceCaptureEnd(16000)
        assertFalse(end.window(512, 10, null) { error("no lift yet") })
        // lifted while the second window was read: counted from 512, not 1024
        var asked: Long? = null
        assertFalse(end.window(512, 20, 15) { asked = it; null })
        assertEquals(15L, asked)
        assertEquals(512L + 4800, end.stopAt)
        assertEquals(1024L, end.read)
        // the timestamp asked for once only
        end.window(512, 30, 15) { error("asked again") }
    }

    @Test
    fun aWindowWithATimestampLogsWhereTheLiftFell() {
        val end = VoiceCaptureEnd(16000)
        end.window(512, 10, 5) { VoiceCaptureEnd.Stamp(400, 512) }
        assertEquals(400L, end.liftFrame)
        assertEquals(5200L, end.stopAt)
    }

    @Test
    fun aReaderSixHundredMsBehindGetsAllUpToTheLiftsTail() {
        // 9600 frames captured that are not yet read; the lift right at the timestamp
        val end = VoiceCaptureEnd(16000)
        end.window(512, 0, null) { null }
        end.window(512, 0, 0) { VoiceCaptureEnd.Stamp(liftFrame = 512 + 9600, captured = 512 + 9600) }
        assertEquals(512L + 9600 + 4800, end.stopAt)
        // read past it however long that takes, not cut at what was read + the tail
        var now = 0L
        while (!end.window(512, now, 0) { null }) now += 32
        assertTrue(end.read >= 512 + 9600 + 4800)
    }

    // a 2 s buffer, 512 read: the timestamp believed while what it says was captured fits the buffer unread
    @Test
    fun aFramePositionPastTheBufferIsNotBelieved() = assertRows(
        "a buffer's worth unread: believed, held to the tail and latency past it" to { assertEquals(512L + 32_000 + 4800, stopAt(512L + 32_000)) },
        "a frame more: the tail counted from what is read" to { assertEquals(512L + 4800, stopAt(512L + 32_001)) },
        // the HAL claiming 10 s captured
        "far past it: the same" to { assertEquals(512L + 4800, stopAt(160_000)) },
    )

    private fun stopAt(captured: Long): Long? {
        val end = VoiceCaptureEnd(16000, bufferFrames = 32000)
        end.lift(captured, 512, 0, captured)
        return end.stopAt
    }

    @Test
    fun noSamplesForASecondAfterTheLiftStops() {
        val end = VoiceCaptureEnd(16000)
        end.window(512, 100, null) { null }
        assertFalse(end.window(0, 500, 400) { null })
        assertFalse(end.window(0, 1399, 400) { null })
        assertTrue(end.window(0, 1400, 400) { null })
        // a sample after the lift: the second counted again from it, not from the lift
        val again = VoiceCaptureEnd(16000)
        assertFalse(again.window(512, 1300, 400) { null })
        assertFalse(again.window(0, 1400, 400) { null })
        assertFalse(again.window(0, 2299, 400) { null })
        assertTrue(again.window(0, 2300, 400) { null })
    }

    @Test
    fun aReaderBehindStillGetsItsTail() {
        // samples coming, however long after the lift: stopped by the count, not the clock
        val end = VoiceCaptureEnd(16000)
        end.window(512, 0, null) { null }
        var now = 0L
        while (!end.window(512, now, 0) { null }) now += 500
        assertTrue(now > VoiceCaptureEnd.GIVE_UP_MS)
        assertEquals(5632L, end.read)
    }

    @Test
    fun aTimestampToAFrame() {
        // a timestamp of no time (some HALs, before the first real one): none
        assertNull(VoiceCaptureEnd.frameAt(100_000, 0, 1_250_000_000, 16000))
        // 0.25 s after the timestamp at 16 kHz: 4000 frames on
        assertEquals(104_000L, VoiceCaptureEnd.frameAt(100_000, 1_000_000_000, 1_250_000_000, 16000))
        // before it: back
        assertEquals(96_000L, VoiceCaptureEnd.frameAt(100_000, 1_000_000_000, 750_000_000, 16000))
        assertEquals(112_000L, VoiceCaptureEnd.frameAt(100_000, 1_000_000_000, 1_250_000_000, 48000))
    }
}
