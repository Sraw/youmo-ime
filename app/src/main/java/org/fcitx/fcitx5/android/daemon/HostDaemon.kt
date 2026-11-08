/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.daemon

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manage the singleton instance of [Fcitx]
 *
 * To use fcitx, client should call [connect] to obtain a [HostConnection],
 * and call [disconnect] on client destroyed. Client should not leak the instance of [HostApi],
 * and must use [HostConnection] to access fcitx functionalities.
 *
 * The instance of [Fcitx] always exists,but whether the dispatcher runs and callback works depend on clients, i.e.
 * if no clients are connected, [Fcitx.stop] will be called.
 *
 * Functions are thread-safe in this class.
 */
object HostDaemon {

    private val realFcitx: HostInstance by lazy { KotlinHost(appContext) }

    // don't leak fcitx instance
    private val fcitxImpl by lazy { PunctuationManager.forgettingOnReload(realFcitx) }

    private fun mkConnection(name: String) = object : HostConnection {

        private inline fun <T> ensureConnected(block: () -> T) =
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

    private val clients = mutableMapOf<String, HostConnection>()

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
     * the new files); not running, it reads them as it starts.
     */
    fun restartIfRunning(): Unit = lock.withLock {
        if (testConnection == null && clients.isNotEmpty()) {
            realFcitx.stop()
            start()
        }
    }

    /**
     * Restart fcitx instance while keep the clients connected
     */
    fun restartFcitx() = lock.withLock {
        realFcitx.stop()
        start()
    }

    /**
     * Stop fcitx instance regardless of connected clients.
     * Should only be used before importing user configuration files,
     * then the App must be restarted as soon as possible.
     *
     * This method blocks until fully stopped.
     */
    fun stopFcitx() {
        realFcitx.stop()
    }

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
}
