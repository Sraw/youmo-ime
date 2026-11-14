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
 * Owns the one host of the process, a [KotlinHost] (ime-core's host run on its own thread), behind [HostInstance].
 *
 * To use the host, a client calls [connect] to obtain a [HostConnection] and [disconnect] when it is destroyed. A
 * client should not keep the [HostApi] it is handed, and reaches the host only through its [HostConnection].
 *
 * The [KotlinHost] always exists; whether it runs depends on the clients: the first [connect] starts it, and it is
 * stopped once none is connected. [stopFcitx] and [restartFcitx] (a backup restored, the language changed) wait for a
 * host still starting before they stop it, so they may block for seconds: call them off the main thread.
 *
 * Functions are thread-safe in this class.
 */
object HostDaemon {

    private val realFcitx: HostInstance by lazy { KotlinHost(appContext) }

    // don't leak fcitx instance
    private val fcitxImpl by lazy { PunctuationManager.forgettingOnReload(realFcitx) }

    private fun mkConnection(name: String) = object : HostConnection {

        private inline fun <T> ensureConnected(block: () -> T) =
            // read without lock: a restart holds it through a stop that does not time out (main-thread ANR)
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

    /**
     * A client name of [owner]'s own instance: one made anew connects before the old one goes and
     * disconnects, and a shared name would disconnect both
     */
    fun clientName(owner: Any) = "${owner.javaClass.name}#${clientSeq.incrementAndGet()}"

    // fcitx reads its punctuation tables as it starts: what was cached of them before goes
    private fun start() {
        PunctuationManager.starting(realFcitx::start)
    }

    /**
     * Test seam: when set, [connect] hands out what it makes instead of starting the Kotlin host
     * (KotlinHost), so a JVM test (Robolectric) can build the whole input method service on a fake engine.
     */
    @VisibleForTesting
    var testConnection: ((String) -> HostConnection)? = null

    /**
     * Test seam: what [restartFcitx] says under [testConnection] (a fake engine has no start to wait for): false
     * stands for a host that stayed starting, so the pages' "may not have restarted" is tested (LC-20).
     */
    @VisibleForTesting
    var testRestarted = true

    /**
     * Create a connection
     */
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

    /**
     * Dispose the connection
     */
    fun disconnect(name: String): Unit = lock.withLock {
        if (testConnection != null || name !in clients)
            return
        clients -= name
        if (clients.isEmpty()) {
            Timber.d("HostDaemon stop fcitx")
            realFcitx.stop()
        }
    }

    /**
     * [block] on fcitx if it runs for some client now, without a connection of the caller's: work that
     * outlives its page (its connection gone) still tells the keyboard. Not running, nothing to tell:
     * fcitx reads its files as it starts.
     */
    fun runIfRunning(block: suspend HostApi.() -> Unit): Unit = lock.withLock {
        // a test's fake engine stands for fcitx as connect gives it
        testConnection?.let { return@withLock it("runIfRunning").runIfReady(block) }
        if (clients.isNotEmpty() && realFcitx.isReady)
            realFcitx.lifeCycleScope.launch { block(fcitxImpl) }
    }

    /**
     * fcitx restarted if it runs for some client (a resource pack imported: its engines made anew on
     * the new files); not running, it reads them as it starts. One still starting may have read the
     * old files: waited for, then restarted (a stop skips a host not ready, a start one not stopped:
     * it was left on the old files). True once the new host is up (or none runs): what the old one
     * read may be deleted then. False if a host stayed STARTING past [RESTART_WAIT_MS] (before the
     * restart, or after it): it may still read the old files, left for the next start to delete.
     * Blocks: not on the main thread (an import's worker).
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

    /**
     * [restart] once [state] is past STARTING (waited for outside the lock: a client's connect on the
     * main thread is not held up), then waited for the new host to be past STARTING too; each wait
     * at most [waitMs]. [restart] says whether it restarted (false: no client, nothing running).
     * False if either wait ran out (a warning logged; not restarted if the first did): the host
     * still starting, on what files is not known.
     */
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
     * [stop] once [state] is past STARTING (waited for at most [waitMs] in all, outside the lock): true; false, still
     * starting then (a warning logged), not stopped. KotlinHost.stop skips a host not READY, so one stopped while
     * starting would go on reading and writing the files an import is replacing (audit-061 app-rest-2).
     * [stop] looks again under its lock and says whether it stopped: false, the host was started anew between the
     * wait and the lock (a pack's import or a language change restarting it), waited for again (R25r2 F7862).
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

    /**
     * [stop] unless the host is [now] starting: what [stopOnceStarted]'s stop does under the lock, the state read there.
     * False, not stopped: started anew since the wait (KotlinHost.stop would skip it and the import go on over a
     * running host), to be waited for again (R25r2 F7862).
     */
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

    /**
     * Restart fcitx instance while keep the clients connected: a host still starting waited for first
     * ([restartOnceStarted]; stopped while starting, the stop and the start were both skipped, the old
     * one kept: a language changed not taken). False if it stayed starting. Blocks: not on the main thread.
     */
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

    /**
     * Stop fcitx instance regardless of connected clients.
     * Should only be used before importing user configuration files,
     * then the App must be restarted as soon as possible.
     *
     * A host still starting is waited for first ([stopOnceStarted]); false if it stayed starting, not
     * stopped: the import must not go on. Blocks until fully stopped: not on the main thread.
     */
    fun stopFcitx(): Boolean {
        if (testConnection != null) return true
        return stopOnceStartedUnderLock(realFcitx.lifecycle.stateFlow, RESTART_WAIT_MS) { realFcitx.stop() }
    }

    /**
     * [stopFcitx]'s body, the host's [state] and its [stop] passed in so a test runs it whole, not a copy (R25r5
     * F8010): [stopOnceStarted], each stop looking again under the lock ([stopUnlessStarting], the state read there).
     */
    @VisibleForTesting
    internal fun stopOnceStartedUnderLock(state: StateFlow<HostLifecycle.State>, waitMs: Long, stop: () -> Unit): Boolean =
        stopOnceStarted(state, waitMs) { lock.withLock { stopUnlessStarting(state.value, stop) } }

    /**
     * Start fcitx instance.
     * Should only be used when it has been stopped **AND** user data importing failed.
     */
    fun startFcitx() {
        start()
    }

    /**
     * A client's connection, or null if none: for what reaches fcitx only while it runs (a broadcast,
     * a settings page), without starting it as [connect] would
     */
    fun getFirstConnectionOrNull() = lock.withLock { clients.firstNotNullOfOrNull { it.value } }

    /** Whether fcitx runs for some client: a test's fake engine stands for one ([testConnection]). */
    fun hasClient(): Boolean = lock.withLock { testConnection != null || clients.isNotEmpty() }
}
