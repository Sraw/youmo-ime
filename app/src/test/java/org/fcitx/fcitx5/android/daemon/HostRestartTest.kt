/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.daemon

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/** A host still starting may have read the old files: waited for and restarted, the old pack deleted only once the new one is up. */
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
        // no client: nothing restarted or waited for
        val stopped = MutableStateFlow(State.STOPPED)
        assertTrue(HostDaemon.restartOnceStarted(stopped, 10_000) { false })
    }

    // false: what it reads is not known, so the old pack is not deleted
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


    // KotlinHost.stop skips a starting host, which would go on using the files an import replaces
    @Test(timeout = 20_000)
    fun aHostStillStartingIsStoppedOnceReadyForAnImport() {
        val state = MutableStateFlow(State.STARTING)
        val stops = AtomicInteger()
        val done = CountDownLatch(1)
        val stopped = AtomicBoolean()
        thread {
            stopped.set(HostDaemon.stopOnceStarted(state, 10_000) { stops.incrementAndGet() > 0 })
            done.countDown()
        }
        Thread.sleep(200)
        assertEquals(0, stops.get())
        state.value = State.READY
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, stops.get())
        assertTrue(stopped.get())
    }

    // the import then fails instead of overwriting files in use
    @Test(timeout = 20_000)
    fun aHostStuckStartingIsNotStoppedAndSaidSo() {
        var stops = 0
        assertFalse(HostDaemon.stopOnceStarted(MutableStateFlow(State.STARTING), 100) { stops++ >= 0 })
        assertEquals(0, stops)
    }

    // started anew between the wait and the stop's lock: waited for again and stopped once up. Runs stopFcitx's own body;
    // without the look under the lock ([HostDaemon.stopUnlessStarting]) it would stop a host that is starting
    @Test(timeout = 20_000)
    fun aHostStartedAnewBetweenTheWaitAndTheLockIsWaitedForAgainThenStopped() {
        val state = RestartedOnFirstLook(MutableStateFlow(State.READY))
        // stopped once, after the restart, on the new host up; not how often the state was read
        val stoppedIn = ArrayList<Pair<Boolean, State>>()
        assertTrue(HostDaemon.stopOnceStartedUnderLock(state, 10_000) { stoppedIn += state.restarted.get() to state.inner.value })
        assertEquals(listOf(true to State.READY), stoppedIn)
    }

    // the first read (the look under the stop's lock; the wait collects instead) finds it restarted: starting, ready 100 ms later
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class RestartedOnFirstLook(val inner: MutableStateFlow<State>) : StateFlow<State> by inner {
        val restarted = AtomicBoolean()
        override val value: State
            get() {
                if (restarted.compareAndSet(false, true)) {
                    inner.value = State.STARTING
                    thread {
                        Thread.sleep(100)
                        inner.value = State.READY
                    }
                }
                return inner.value
            }
    }

    @Test(timeout = 20_000)
    fun aHostReadyIsStoppedAtOnceForAnImport() {
        var stops = 0
        assertTrue(HostDaemon.stopOnceStarted(MutableStateFlow(State.READY), 100) { stops++ >= 0 })
        assertEquals(1, stops)
    }

    // the stop, looking again under the lock, does not take a restarted host for stopped but waits for it
    @Test(timeout = 20_000)
    fun aHostStartedAnewBetweenTheWaitAndTheStopIsWaitedForAgain() {
        val state = MutableStateFlow(State.READY)
        val asked = AtomicInteger()
        val done = CountDownLatch(1)
        val stopped = AtomicBoolean()
        thread {
            stopped.set(
                HostDaemon.stopOnceStarted(state, 10_000) {
                    if (asked.incrementAndGet() == 1) {
                        state.value = State.STARTING
                        false
                    } else {
                        true
                    }
                },
            )
            done.countDown()
        }
        waitFor("asked once") { asked.get() == 1 }
        assertFalse(done.await(200, TimeUnit.MILLISECONDS))
        assertEquals(1, asked.get())
        state.value = State.READY
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(2, asked.get())
        assertTrue(stopped.get())
        // and one that never comes up: false within the wait
        val stuck = MutableStateFlow(State.READY)
        assertFalse(
            HostDaemon.stopOnceStarted(stuck, 100) {
                stuck.value = State.STARTING
                false
            },
        )
    }
}
