/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.Runnable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext

class HostDispatcherTest {

    // the debug build's idle signal
    @Test
    fun callsNotRunOutArePending() {
        val d = HostDispatcher().apply { start() }
        try {
            // the counter is process-wide: another test's leftover jobs must not count here
            val before = HostDispatcher.pendingJobs
            val release = CountDownLatch(1)
            val ran = CountDownLatch(2)
            d.dispatch(EmptyCoroutineContext, Runnable { release.await(5, TimeUnit.SECONDS); ran.countDown() })
            d.dispatch(EmptyCoroutineContext, Runnable { ran.countDown() })
            assertEquals(before + 2, HostDispatcher.pendingJobs)
            release.countDown()
            assertTrue(ran.await(5, TimeUnit.SECONDS))
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (HostDispatcher.pendingJobs != before && System.nanoTime() < end) Thread.sleep(1)
            assertEquals(before, HostDispatcher.pendingJobs)
        } finally {
            d.stop {}
        }
    }

    // nothing stays counted, even a call shutdownNow interrupted mid-run
    @Test
    fun stopReturnsTheCallsDroppedNotTheTimersAndCountsNone() {
        val d = HostDispatcher().apply { start() }
        d.schedule(60_000) {}
        val never = CountDownLatch(1)
        // the first holds the thread till shutdownNow interrupts it, so the second is still queued: one or both come back, never the timer
        val rest = d.stop {
            d.dispatch(EmptyCoroutineContext, Runnable { never.await() })
            d.dispatch(EmptyCoroutineContext, Runnable {})
        }
        assertTrue("${rest.size}", rest.size in 1..2)
        // checked directly: with the first call started, call two + the timer is 2 too
        assertTrue(rest.toString(), rest.none { it is ScheduledFuture<*> && it.getDelay(TimeUnit.MILLISECONDS) > 0 })
        // a -1 here needs the first call to have started before shutdownNow; the other order gives 0 either way
        assertEquals(0, HostDispatcher.pendingJobs)
    }

    // the host can start again (restartFcitx)
    @Test
    fun aLastStepThatThrowsStillStops() {
        val d = HostDispatcher().apply { start() }
        var thread: Thread? = null
        assertThrows(ExecutionException::class.java) { d.stop { thread = Thread.currentThread(); error("flush failed") } }
        thread!!.join(5000)
        assertFalse(thread!!.isAlive)
        d.start()
        d.stop {}
    }

    // the call must be running when the stop comes, not queued (dropped unstarted it would pass anyway): the stopper is
    // interrupted while waiting behind it, so the shutdown happens with the call still on the thread
    @Test
    fun aCallPastTheWaitStillEndsAtZero() {
        val d = HostDispatcher(stopWaitSeconds = 0).apply { start() }
        val before = HostDispatcher.pendingJobs
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        d.dispatch(EmptyCoroutineContext, Runnable {
            started.countDown()
            while (release.count > 0) {
                try { release.await() } catch (_: InterruptedException) {}
            }
        })
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val stopper = Thread { runCatching { d.stop {} } }.apply { start() }
        // the stopper is parked in the last step's get, behind the call
        val parked = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (stopper.state != Thread.State.WAITING && System.nanoTime() < parked) Thread.sleep(1)
        stopper.interrupt()
        stopper.join(10_000)
        assertFalse(stopper.isAlive)
        // still running past the wait: counted, not zeroed
        assertEquals(before + 1, HostDispatcher.pendingJobs)
        release.countDown()
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (HostDispatcher.pendingJobs != before && System.nanoTime() < end) Thread.sleep(1)
        assertEquals(before, HostDispatcher.pendingJobs)
    }
}
