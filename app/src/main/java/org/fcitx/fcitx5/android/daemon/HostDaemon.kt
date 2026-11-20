/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.daemon

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.core.HostApi
import org.fcitx.fcitx5.android.core.HostInstance
import org.fcitx.fcitx5.android.core.HostLifecycle
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.core.lifeCycleScope
import org.fcitx.fcitx5.android.core.whenReady
import org.fcitx.fcitx5.android.daemon.HostDaemon.connect
import org.fcitx.fcitx5.android.daemon.HostDaemon.disconnect
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns the process's one [KotlinHost]. Clients [connect] and [disconnect], and must not keep the [HostApi] they
 * are handed. The first connect starts it, the last disconnect stops it. [stopFcitx] and [restartFcitx] wait out a
 * host still starting, so they may block for seconds: call them off the main thread. Thread-safe.
 */
object HostDaemon {

    private val realFcitx: HostInstance by lazy { KotlinHost(appContext) }

    private val fcitxImpl by lazy { PunctuationManager.forgettingOnReload(realFcitx) }

    private fun mkConnection(name: String) = object : HostConnection {

        private inline fun <T> ensureConnected(block: () -> T) =
            // read without lock: a restart holds it through a stop that may not time out (main-thread ANR)
            if (name in clients)
                block()
            else throw IllegalStateException("$name is disconnected")

        override fun <T> runImmediately(block: suspend HostApi.() -> T): T = ensureConnected {
            runBlocking(realFcitx.lifeCycleScope.coroutineContext) {
                block(fcitxImpl)
            }
        }

        override suspend fun <T> runOnReady(block: suspend HostApi.() -> T): T = ensureConnected {
            realFcitx.lifecycle.whenReady { block(fcitxImpl) }
        }

        override fun runIfReady(block: suspend HostApi.() -> Unit) {
            ensureConnected {
                if (realFcitx.isReady)
                    realFcitx.lifeCycleScope.launch {
                        block(fcitxImpl)
                    }
            }
        }

        override val lifecycleScope: CoroutineScope
            get() = realFcitx.lifecycle.lifecycleScope

    }

    private val lock = ReentrantLock()

    // concurrent for ensureConnected's lock-free read; compound updates stay under lock
    private val clients: MutableMap<String, HostConnection> = ConcurrentHashMap()

    private val clientSeq = AtomicLong()

    /** Per instance: a remade owner connects before the old one disconnects, and a shared name would disconnect both. */
    fun clientName(owner: Any) = "${owner.javaClass.name}#${clientSeq.incrementAndGet()}"

    // fcitx reads its punctuation tables as it starts: the cache goes
    private fun start() {
        PunctuationManager.starting(realFcitx::start)
    }

    /** Test seam: [connect] hands out this instead of starting the host, so Robolectric can build the whole service on a fake engine. */
    @VisibleForTesting
    var testConnection: ((String) -> HostConnection)? = null

    /** Test seam: what [restartFcitx] returns under [testConnection]; false stands for a host that stayed starting. */
    @VisibleForTesting
    var testRestarted = true

    fun connect(name: String): HostConnection = lock.withLock {
        testConnection?.let { return@withLock it(name) }
        if (name in clients)
            return@withLock clients.getValue(name)
        if (realFcitx.lifecycle.currentState == HostLifecycle.State.STOPPED) {
            Timber.d("HostDaemon start fcitx")
            start()
        }
        val new = mkConnection(name)
        clients[name] = new
        return@withLock new
    }

    fun disconnect(name: String): Unit = lock.withLock {
        if (testConnection != null || name !in clients)
            return
        clients -= name
        if (clients.isEmpty()) {
            Timber.d("HostDaemon stop fcitx")
            realFcitx.stop()
        }
    }

    /** Runs only if fcitx already runs, without a connection: for work that outlives its page. Not running, fcitx reads the files as it starts. */
    fun runIfRunning(block: suspend HostApi.() -> Unit): Unit = lock.withLock {
        testConnection?.let { return@withLock it("runIfRunning").runIfReady(block) }
        if (clients.isNotEmpty() && realFcitx.isReady)
            realFcitx.lifeCycleScope.launch { block(fcitxImpl) }
    }

    /**
     * Restarts fcitx if it runs, so its engines use new files (a pack imported). A host still starting may have read
     * the old files: waited for, then restarted. True once the new host is up (or none runs): the old files may then be
     * deleted. False if a host stayed STARTING past [RESTART_WAIT_MS]. Blocks: not on the main thread.
     */
    fun restartIfRunning(): Boolean {
        if (testConnection != null) return true
        return restartOnceStarted(realFcitx.lifecycle.stateFlow, RESTART_WAIT_MS) {
            lock.withLock {
                if (clients.isEmpty()) return@withLock false
                realFcitx.stop()
                start()
                true
            }
        }
    }

    /** Both waits are outside the lock, so a client's connect on main is not held up; false if either ran out. */
    @VisibleForTesting
    internal fun restartOnceStarted(state: StateFlow<HostLifecycle.State>, waitMs: Long, restart: () -> Boolean): Boolean {
        fun started() = pastStarting(state, waitMs)
        if (!started()) {
            Timber.w("fcitx still starting after %d ms: not restarted", waitMs)
            return false
        }
        if (restart() && !started()) {
            Timber.w("fcitx restarted, still starting after %d ms", waitMs)
            return false
        }
        return true
    }

    /**
     * KotlinHost.stop skips a host not READY, so one stopped while starting would keep using files an import is
     * replacing. [stop] checks again under its lock; false there means a restart slipped in, waited for again.
     */
    @VisibleForTesting
    internal fun stopOnceStarted(state: StateFlow<HostLifecycle.State>, waitMs: Long, stop: () -> Boolean): Boolean {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
        while (true) {
            val left = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())
            if (left <= 0 || !pastStarting(state, left)) {
                Timber.w("fcitx still starting after %d ms: not stopped", waitMs)
                return false
            }
            if (stop()) return true
        }
    }

    /** False if started anew since the wait: KotlinHost.stop would skip it and the import would run over a live host. */
    @VisibleForTesting
    internal fun stopUnlessStarting(now: HostLifecycle.State, stop: () -> Unit): Boolean {
        if (now == HostLifecycle.State.STARTING) return false
        stop()
        return true
    }

    private fun pastStarting(state: StateFlow<HostLifecycle.State>, waitMs: Long): Boolean = runBlocking {
        withTimeoutOrNull(waitMs) { state.first { it != HostLifecycle.State.STARTING } }
    } != null

    private const val RESTART_WAIT_MS = 10_000L

    /** Keeps clients connected; a host still starting is waited for first, else the stop and start were both skipped. False if it stayed starting. Blocks. */
    fun restartFcitx(): Boolean {
        if (testConnection != null) return testRestarted
        return restartOnceStarted(realFcitx.lifecycle.stateFlow, RESTART_WAIT_MS) {
            lock.withLock {
                realFcitx.stop()
                start()
                true
            }
        }
    }

    /** Stops regardless of clients, before importing user config; restart the app soon after. False if it stayed starting: the import must not go on. Blocks. */
    fun stopFcitx(): Boolean {
        if (testConnection != null) return true
        return stopOnceStartedUnderLock(realFcitx.lifecycle.stateFlow, RESTART_WAIT_MS) { realFcitx.stop() }
    }

    /** [stopFcitx]'s body with the state and stop passed in, so a test runs it whole. */
    @VisibleForTesting
    internal fun stopOnceStartedUnderLock(state: StateFlow<HostLifecycle.State>, waitMs: Long, stop: () -> Unit): Boolean =
        stopOnceStarted(state, waitMs) { lock.withLock { stopUnlessStarting(state.value, stop) } }

    /** Only when stopped and a user-data import failed. */
    fun startFcitx() {
        start()
    }

    /** Without starting it as [connect] would. */
    fun getFirstConnectionOrNull() = lock.withLock { clients.firstNotNullOfOrNull { it.value } }

    /** A test's fake engine counts as one ([testConnection]). */
    fun hasClient(): Boolean = lock.withLock { testConnection != null || clients.isNotEmpty() }
}
