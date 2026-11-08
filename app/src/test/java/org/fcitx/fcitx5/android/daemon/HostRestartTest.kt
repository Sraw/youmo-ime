/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.daemon

import kotlinx.coroutines.flow.MutableStateFlow
import org.fcitx.fcitx5.android.core.HostLifecycle.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The host restarted for a pack imported (review L3): one still starting (it may have read the old
 * files) waited for, then restarted, not skipped; and the restart waited for till the new host is
 * up, the old pack deleted only then.
 */
class HostRestartTest {

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cond()) {
            check(System.nanoTime() < until) { "not $what in 5 s" }
            Thread.sleep(5)
        }
    }

    @Test(timeout = 20_000)
    fun aHostStillStartingIsRestartedOnceReadyAndTheNewOneWaitedFor() {
        val state = MutableStateFlow(State.STARTING)
        val restarts = AtomicInteger()
        val done = CountDownLatch(1)
        val up = AtomicBoolean()
        thread {
            up.set(
                HostDaemon.restartOnceStarted(state, 10_000) {
                    restarts.incrementAndGet()
                    state.value = State.STARTING
                    true
                },
            )
            done.countDown()
        }
        // not while it starts: a stop would skip it, a start too
        Thread.sleep(200)
        assertEquals(0, restarts.get())
        state.value = State.READY
        waitFor("restarted") { restarts.get() == 1 }
        // the new one starting: not returned
        assertFalse(done.await(200, TimeUnit.MILLISECONDS))
        state.value = State.READY
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, restarts.get())
        assertTrue(up.get())
    }

    @Test(timeout = 20_000)
    fun aHostReadyIsRestartedAtOnceAndNoneRunningIsNotWaitedFor() {
        val state = MutableStateFlow(State.READY)
        var restarts = 0
        assertTrue(
            HostDaemon.restartOnceStarted(state, 10_000) {
                restarts++
                true
            },
        )
        assertEquals(1, restarts)
        // no client: nothing restarted, nothing waited for; nothing reads the old files
        val stopped = MutableStateFlow(State.STOPPED)
        assertTrue(HostDaemon.restartOnceStarted(stopped, 10_000) { false })
    }

    // a host that never gets past starting: not waited for past the wait, not restarted (a stop
    // would skip it), and false: what it reads is not known, the old pack not to be deleted (review L-2)
    @Test(timeout = 20_000)
    fun aHostStuckStartingIsWaitedForNoLongerThanTheWaitAndSaidNotUp() {
        val state = MutableStateFlow(State.STARTING)
        var restarts = 0
        val started = System.nanoTime()
        assertFalse(
            HostDaemon.restartOnceStarted(state, 100) {
                restarts++
                true
            },
        )
        assertEquals(0, restarts)
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
        // restarted, the new one stuck starting: false too
        val ready = MutableStateFlow(State.READY)
        assertFalse(
            HostDaemon.restartOnceStarted(ready, 100) {
                restarts++
                ready.value = State.STARTING
                true
            },
        )
        assertEquals(1, restarts)
    }
}
