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


    // audit-061 app-rest-2: a backup's import stops the host before replacing its files; one still starting
    // (KotlinHost.stop skips it) is waited for and then stopped, not left reading and writing them
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

    // one stuck starting: not stopped, false (the import then fails instead of overwriting files in use)
    @Test(timeout = 20_000)
    fun aHostStuckStartingIsNotStoppedAndSaidSo() {
        var stops = 0
        assertFalse(HostDaemon.stopOnceStarted(MutableStateFlow(State.STARTING), 100) { stops++ >= 0 })
        assertEquals(0, stops)
    }

    // R25r3 F7926 (R25r2 F7862): past starting as the wait ended, started anew before the stop has the lock (a pack's
    // import or a language change restarting it): not stopped then (the stop would skip it, the import going on
    // over a running host), waited for again and stopped once it is up. Runs stopFcitx's own body
    // ([HostDaemon.stopOnceStartedUnderLock], R25r5 F8010), the restart made on its first look under the lock:
    // without that look ([HostDaemon.stopUnlessStarting]) it stops at once, on a host starting.
    @Test(timeout = 20_000)
    fun aHostStartedAnewBetweenTheWaitAndTheLockIsWaitedForAgainThenStopped() {
        val state = RestartedOnFirstLook(MutableStateFlow(State.READY))
        // as stopped: whether it had been started anew by then, and its state. Stopped once, after the restart and
        // on the new host up; not how often the state was read (a look more, as a fast path, changes nothing: F8033)
        val stoppedIn = ArrayList<Pair<Boolean, State>>()
        assertTrue(HostDaemon.stopOnceStartedUnderLock(state, 10_000) { stoppedIn += state.restarted.get() to state.inner.value })
        assertEquals(listOf(true to State.READY), stoppedIn)
    }

    // a host whose state, read for the first time (the look under the stop's lock; the wait collects the flow and
    // does not read it), has been started anew: starting, ready again 100 ms later
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

    // one past starting already: stopped at once, nothing waited for
    @Test(timeout = 20_000)
    fun aHostReadyIsStoppedAtOnceForAnImport() {
        var stops = 0
        assertTrue(HostDaemon.stopOnceStarted(MutableStateFlow(State.READY), 100) { stops++ >= 0 })
        assertEquals(1, stops)
    }

    // R25r2 F7862: the host restarted (a pack imported, the language changed) between the wait and the stop's
    // lock is starting again: the stop, looking again under the lock, does not take it for stopped (it was, and
    // the import went on over a running host) but waits for the new one
    @Test(timeout = 20_000)
    fun aHostStartedAnewBetweenTheWaitAndTheStopIsWaitedForAgain() {
        val state = MutableStateFlow(State.READY)
        val asked = AtomicInteger()
        val done = CountDownLatch(1)
        val stopped = AtomicBoolean()
        thread {
            stopped.set(
                HostDaemon.stopOnceStarted(state, 10_000) {
                    // the first look finds it restarted meanwhile: not stopped
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
        // the new one starting: neither stopped nor given up
        assertFalse(done.await(200, TimeUnit.MILLISECONDS))
        assertEquals(1, asked.get())
        state.value = State.READY
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(2, asked.get())
        assertTrue(stopped.get())
        // and one that never comes up after that: false within the wait, as one stuck from the start
        val stuck = MutableStateFlow(State.READY)
        assertFalse(
            HostDaemon.stopOnceStarted(stuck, 100) {
                stuck.value = State.STARTING
                false
            },
        )
    }
}
