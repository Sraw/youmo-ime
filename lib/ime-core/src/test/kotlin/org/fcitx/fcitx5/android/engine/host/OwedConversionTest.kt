/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.learn.ConvertedMarker
import org.fcitx.fcitx5.android.engine.learn.FakeClock
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

/** [OwedConversion]: a heavy first open's conversion, made off the typing thread and put in place at a pause. */
class OwedConversionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = FakeClock()
    private val errors = ArrayList<IOException>()

    // the work handed to the background, run when the test says
    private val held = ArrayList<Runnable>()
    private val heldBack = Executor { held += it }

    private fun runHeld() {
        while (held.isNotEmpty()) held.removeAt(0).run()
    }

    // an older build's files, heavy so the open defers them; [fails]: into() throws
    private class FakeLegacy(var fails: Boolean = false) : LearnLog.Legacy {
        var intos = 0

        override fun into(state: LearnState, clock: LearnClock) {
            intos++
            if (fails) throw IOException("unreadable")
        }

        override fun marker(state: LearnState) = ConvertedMarker(emptyMap(), emptySet())

        override fun since(was: ConvertedMarker?, state: LearnState, stamp: LearnClock.Stamp): Pair<List<LearnRecord>, ConvertedMarker>? = null

        override val heavy get() = true
    }

    private fun deferred(legacy: LearnLog.Legacy, dir: File = folder.newFolder()) =
        LearnLog(File(dir, LearnLog.FILE), clock, errors::add).also { it.open(legacy, deferred = true) }

    // review R11 F2747: a conversion that failed off the thread is owed to the next start, not made again at a finish
    @Test
    fun aConversionThatFailedInTheBackgroundIsNotTriedAgain() {
        val legacy = FakeLegacy(fails = true)
        val log = deferred(legacy)
        assertTrue(log.conversionPending)
        val owed = OwedConversion(heldBack, errors::add)
        owed.begin(log, legacy, 1)
        runHeld()
        assertEquals(1, legacy.intos)
        assertFalse(owed.ready)
        owed.finish(log)
        assertEquals(1, legacy.intos)
        assertTrue(log.conversionPending)
        log.close()
    }

    // review R11 F3067: a conversion made for a log closed since is dropped, not put in another
    @Test
    fun aConversionMadeForAnotherLogIsDropped() {
        val legacy = FakeLegacy()
        val first = deferred(legacy)
        val second = deferred(legacy)
        val owed = OwedConversion(heldBack, errors::add)
        owed.begin(first, legacy, 1)
        runHeld()
        assertTrue(owed.ready)
        owed.take(second)
        assertFalse(owed.ready)
        assertTrue(second.conversionPending)
        first.close()
        second.close()
    }

    // review R11 F2707: made off the thread, put in place at a pause, then nothing owed
    @Test
    fun aConversionMadeIsPutInPlaceAtAPause() {
        val legacy = FakeLegacy()
        val log = deferred(legacy)
        val owed = OwedConversion(heldBack, errors::add)
        owed.begin(log, legacy, 1)
        assertFalse(owed.ready)
        runHeld()
        assertTrue(owed.ready)
        // made, still waiting: not put in place while something is composed
        assertTrue(owed.paused(log, composing = true, idle = false))
        assertTrue(log.conversionPending)
        owed.paused(log, composing = false, idle = false)
        assertFalse(owed.ready)
        assertFalse(log.conversionPending)
        log.close()
    }

    // the log as it is now, written out first
    private fun LearnLog.now(): ByteArray {
        var now = ByteArray(0)
        assertTrue(rewrite { bytes, out -> now = bytes; out.write(bytes); true })
        return now
    }

    // the log rewritten to [was], shorter (as a fold or a clear may leave it): a merge made since is stale
    private fun LearnLog.rewrittenTo(was: ByteArray) = assertTrue(rewrite { _, out -> out.write(was); true })

    // review R11 F2707: a merge the log was rewritten under is made again off the thread, then put in place
    @Test
    fun aMergeMadeStaleByARewriteIsMadeAgainInTheBackground() {
        val legacy = FakeLegacy()
        val dir = folder.newFolder()
        val log = deferred(legacy, dir)
        log.record(listOf(row("ni", "你")))
        val was = log.now()
        log.record(listOf(row("hao", "好")))
        val owed = OwedConversion(heldBack, errors::add)
        owed.begin(log, legacy, 1)
        runHeld()
        log.rewrittenTo(was)
        owed.take(log)
        assertFalse(owed.ready)
        assertTrue(log.conversionPending)
        assertEquals(1, held.size)
        runHeld()
        assertTrue(owed.ready)
        owed.take(log)
        assertFalse(log.conversionPending)
        log.close()
    }

    // review R11 F2707: stale again and again, merged off the thread three times, then made on this one
    @Test
    fun aMergeStaleFourTimesIsMadeOnTheCallingThread() {
        val legacy = FakeLegacy()
        val dir = folder.newFolder()
        val log = deferred(legacy, dir)
        val owed = OwedConversion(heldBack, errors::add)
        log.record(listOf(row("ni", "你")))
        owed.begin(log, legacy, 1)
        repeat(4) { i ->
            val was = log.now()
            log.record(listOf(row("hao", "好")))
            runHeld()
            assertTrue("merge $i", owed.ready)
            log.rewrittenTo(was)
            owed.take(log)
        }
        assertEquals(0, held.size)
        assertFalse(log.conversionPending)
        log.close()
    }

    // review R11 F2707: a merge that could not be put in place is owed to the next start, not made at a finish
    @Test
    fun aMergeThatCouldNotBePutInPlaceIsNotMadeAgain() {
        val legacy = FakeLegacy()
        val dir = folder.newFolder()
        val log = deferred(legacy, dir)
        val owed = OwedConversion(heldBack, errors::add)
        owed.begin(log, legacy, 1)
        runHeld()
        assertTrue(owed.ready)
        // the record's directory read-only: the rewrite cannot be written
        assertTrue(dir.setWritable(false))
        try {
            assumeFalse("a user who may write any file", dir.canWrite())
            owed.take(log)
        } finally {
            dir.setWritable(true)
        }
        assertFalse(owed.ready)
        assertTrue(log.conversionPending)
        owed.finish(log)
        assertTrue(log.conversionPending)
        log.close()
    }
}
