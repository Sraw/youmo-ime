/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.device

import org.fcitx.fcitx5.android.core.CoreLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BigCorePinTest {

    /** One thread's mask; [refuses] is what a cpuset without the big cores does, [refused] the masks it takes no core of. */
    private class FakeAffinity(var mask: Long = 0xff, var refuses: Boolean = false, val refused: Set<Long> = emptySet()) : Affinity {
        val calls = ArrayList<String>()
        override fun set(mask: Long): Long {
            calls += "set ${mask.toULong().toString(16)}"
            if (refuses || mask in refused) return -1
            this.mask = mask
            return 0
        }
    }

    private var now = 0L

    private fun pin(affinity: Affinity, bigMask: Long = 0xf0) = BigCorePin(affinity, bigMask, possible = 0xff) { now }

    @Test
    fun nestedStretchesPinOnceAndPutTheThreadBackOnEveryCore() {
        val affinity = FakeAffinity()
        val pin = pin(affinity)
        val seen = pin.run {
            val inner = pin.run { affinity.mask }
            listOf(affinity.mask, inner)
        }
        assertEquals(listOf(0xf0L, 0xf0L), seen)
        assertEquals(listOf("set f0", "set ff"), affinity.calls)
        assertEquals(0xffL, affinity.mask)
        assertEquals(42, pin.run { 42 })
        assertEquals(4, affinity.calls.size)
    }

    // a mask read back lacks the cores core_ctl isolated then (the 6T's 4 and 6): restored, it shrank the thread for good
    @Test
    fun aThreadLeftWithoutSomeCoresGetsThemAllBack() {
        val affinity = FakeAffinity(mask = 0xaf)
        pin(affinity).run { }
        assertEquals(0xffL, affinity.mask)
    }

    @Test
    fun theCoresRefusedAfterAStretchEveryBitIsAskedForAndTold() {
        val logs = ArrayList<String>()
        CoreLog.sink = { logs += it }
        try {
            val affinity = FakeAffinity(refused = setOf(0xffL))
            val pin = pin(affinity)
            pin.run { }
            assertEquals(listOf("set f0", "set ff", "set ffffffffffffffff"), affinity.calls)
            assertEquals(-1L, affinity.mask)
            pin.run { }
            assertEquals(1, logs.size)
        } finally {
            CoreLog.sink = null
        }
    }

    // a stretch whose entry threw (a log sink did) still unwinds: the thread pins again later
    @Test
    fun anEntryThatThrowsLeavesNoStretchOpen() {
        CoreLog.sink = { throw IllegalStateException("sink") }
        val affinity = FakeAffinity(refuses = true)
        val pin = pin(affinity)
        try {
            pin.run { }
            fail("thrown")
        } catch (e: IllegalStateException) {
            assertEquals("sink", e.message)
        } finally {
            CoreLog.sink = null
        }
        affinity.refuses = false
        now = BigCorePin.BACKOFF_NANOS
        pin.run { assertEquals(0xf0L, affinity.mask) }
        assertEquals(0xffL, affinity.mask)
    }

    @Test
    fun aStretchThatThrowsGivesTheMaskBackToo() {
        val affinity = FakeAffinity()
        try {
            pin(affinity).run { throw IllegalStateException("x") }
            fail("thrown")
        } catch (e: IllegalStateException) {
            assertEquals("x", e.message)
        }
        assertEquals(0xffL, affinity.mask)
    }

    @Test
    fun noBigCoresNoPinning() {
        val affinity = FakeAffinity()
        assertEquals(1, pin(affinity, bigMask = 0).run { 1 })
        assertTrue(affinity.calls.isEmpty())
    }

    @Test
    fun switchedOffForAMeasurementNoPinning() {
        val affinity = FakeAffinity()
        BigCorePin.enabled = false
        try {
            pin(affinity).run { }
        } finally {
            BigCorePin.enabled = true
        }
        assertTrue(affinity.calls.isEmpty())
    }

    // refused (EINVAL): untried for ten seconds, then tried again; told once
    @Test
    fun aRefusalWaitsTenSecondsBeforeTheNextTry() {
        val logs = ArrayList<String>()
        CoreLog.sink = { logs += it }
        try {
            val affinity = FakeAffinity(refuses = true)
            val pin = pin(affinity)
            assertEquals(1, pin.run { 1 })
            assertEquals(listOf("set f0"), affinity.calls)
            now = BigCorePin.BACKOFF_NANOS - 1
            pin.run { }
            assertEquals(1, affinity.calls.size)
            now = BigCorePin.BACKOFF_NANOS
            pin.run { }
            assertEquals(2, affinity.calls.size)
            assertEquals(1, logs.size)
            affinity.refuses = false
            now = 2 * BigCorePin.BACKOFF_NANOS
            pin.run { assertEquals(0xf0L, affinity.mask) }
            assertEquals(0xffL, affinity.mask)
            pin.run { }
            assertEquals(listOf("set f0", "set ff"), affinity.calls.takeLast(2))
        } finally {
            CoreLog.sink = null
        }
    }
}
