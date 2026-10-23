/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class InBackgroundTest {

    private val errors = ArrayList<Throwable>()

    // runs what it was handed only when told, in any order
    private class Later : Executor {
        val queued = ArrayList<Runnable>()
        override fun execute(command: Runnable) {
            queued += command
        }
        fun run(i: Int) = queued.removeAt(i).run()
    }

    @Test
    fun nothingTillMadeThenTheValueAndMadeOncePerKey() {
        val later = Later()
        val b = InBackground<String>(later) { errors += it }
        var snapshots = 0
        val make = { v: String -> { snapshots++; { _: () -> Boolean -> v } } }
        assertNull(b.get(1, make("a")))
        assertNull(b.get(1, make("a")))
        assertEquals(1, later.queued.size)
        later.run(0)
        assertEquals("a", b.get(1, make("a")))
        assertEquals(1, snapshots)
    }

    @Test
    fun oneMadeForAnOlderKeyIsDroppedHoweverLateItComes() {
        val later = Later()
        val b = InBackground<String>(later) { errors += it }
        b.get(1) { { "old" } }
        b.get(2) { { "new" } }
        // the newer made first, then the older: the newer stays
        later.run(1)
        later.run(0)
        assertEquals("new", b.get(2) { { "again" } })
        // asked for under the old key again: made anew, not the dropped one
        assertNull(b.get(1) { { "old again" } })
        later.run(0)
        assertEquals("old again", b.get(1) { { "x" } })
    }

    @Test
    fun aMakeThatFailedIsReportedNotThrownAndNotMadeAgainForItsKey() {
        // F2441: it runs on a bare thread, where a throw would kill the input method
        val later = Later()
        val b = InBackground<String>(later) { errors += it }
        b.get(1) { { throw IllegalStateException("no") } }
        later.run(0)
        assertEquals(listOf("no"), errors.map { it.message })
        assertNull(b.get(1) { { "a" } })
        assertTrue(later.queued.isEmpty())
        // another key is made as ever
        assertNull(b.get(2) { { "b" } })
        later.run(0)
        assertEquals("b", b.get(2) { { "c" } })
    }

    @Test
    fun anErrorInAMakeIsReportedToo() {
        // 0.3.1: an OutOfMemoryError coding a merged dictionary for a table, uncaught, crash-looped the input method
        val later = Later()
        val b = InBackground<String>(later) { errors += it }
        b.get(1) { { throw OutOfMemoryError("heap") } }
        later.run(0)
        assertEquals(listOf("heap"), errors.map { it.message })
        assertNull(b.get(1) { { "a" } })
    }

    @Test
    fun aMakeOfAKeyNoLongerAskedForStopsAndIsDroppedEvenIfTheKeyIsAskedAgain() {
        // F2471: a superseded coding stops early; its partial value never fills a later ask
        val later = Later()
        val b = InBackground<String>(later) { errors += it }
        var seen = true
        b.get(1) { { stillAsked -> seen = stillAsked(); "partial" } }
        b.get(2) { { "two" } }
        b.get(1) { { "whole" } }
        later.run(0) // never started: superseded before it ran
        assertTrue(seen)
        val stopped = Later()
        val d = InBackground<String>(stopped) { errors += it }
        d.get(1) { { stillAsked -> d.get(2) { { "" } }; d.get(1) { { "" } }; seen = stillAsked(); "partial" } }
        stopped.run(0)
        assertFalse(seen)
        assertNull(d.get(1) { { "z" } })
        assertTrue(errors.isEmpty())
    }

    @Test
    fun madeInPlaceItIsThereAtOnce() {
        assertEquals("a", InBackground<String>({ it.run() }) { errors += it }.get(1) { { "a" } })
    }
}
