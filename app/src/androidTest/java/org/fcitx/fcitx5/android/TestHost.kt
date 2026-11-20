/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.HostInstance
import org.fcitx.fcitx5.android.core.KotlinHost
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.host.HostEvent

/** A test class's own fcitx, its events forwarded to [events]; shared by HostTest and HostEnglishTest. */
class TestHost {

    lateinit var fcitx: HostInstance
        private set

    // buffered, not CONFLATED: a conflated channel loses the Commit to the Candidates that follows it
    val events = Channel<HostEvent>(capacity = Channel.UNLIMITED)

    // written on the collector: KotlinHost.inputPanelCached is written on the fcitx thread unsynchronised
    @Volatile
    var latestInputPanel = HostEvent.InputPanel.EMPTY
        private set

    @Volatile
    var latestPaged = HostEvent.Paged.EMPTY
        private set

    // off the main thread: eventFlow drops its oldest past 15 buffered, so a stalled collector could lose a Commit
    private val scope = CoroutineScope(Dispatchers.Default)

    private var enabledImeBefore: Array<String>? = null

    private var enabledIme: List<String> = listOf()

    fun start(setUp: suspend HostInstance.() -> Unit = {}) {
        checkThisInputMethodIsNotBound()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fcitx = KotlinHost(context).apply { showInputMethodInformation = false }

        // UNDISPATCHED subscribes before start(): eventFlow has no replay, so Ready would be lost
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            fcitx.eventFlow.collect {
                if (it is HostEvent.InputPanel) latestInputPanel = it
                if (it is HostEvent.Paged) latestPaged = it
                events.send(it)
            }
        }
        fcitx.start()

        // the Timeout rule does not cover @BeforeClass
        runBlocking {
            withTimeout(60_000) {
                setUpEngine()
                fcitx.setUp()
            }
        }
    }

    private suspend fun setUpEngine() {
        receiveFirst<HostEvent.Ready>()
        // without an input context sendKey is accepted but yields nothing; the service does this in onBindInput
        fcitx.activate(TEST_UID, TEST_PKG_NAME)
        fcitx.focus(true)
        enabledImeBefore = fcitx.enabledIme().map { it.uniqueName }.toTypedArray()
        // keep what the tests commit out of the app's learned data
        fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive))
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
    }

    fun stop(restore: suspend HostInstance.() -> Unit = {}) {
        if (!::fcitx.isInitialized) return
        try {
            runBlocking {
                fcitx.restore()
                enabledImeBefore?.let { fcitx.setEnabledIme(it) }
                fcitx.focus(false)
                fcitx.deactivate(TEST_UID)
            }
        } finally {
            fcitx.stop()
            scope.cancel()
        }
    }

    /**
     * Leftover events would be matched by the next test's `receiveFirst*` (oldest first), and `reset()` returns
     * before its own events arrive: drain until quiet, then forget the last panel.
     */
    fun beforeTest() = runBlocking {
        fcitx.reset()
        awaitQuiet()
        latestInputPanel = HostEvent.InputPanel.EMPTY
        latestPaged = HostEvent.Paged.EMPTY
        enabledIme = fcitx.enabledIme().map { it.uniqueName }
    }

    fun afterTest() = runBlocking {
        fcitx.setEnabledIme(enabledIme.toTypedArray())
    }

    suspend inline fun <reified T : HostEvent> receiveFirst(): T? =
        events.receiveAsFlow().mapNotNull { it as? T }.firstOrNull()

    /** Refine slices may still reorder a list after it first shows. */
    suspend fun awaitQuiet() {
        while (withTimeoutOrNull(QUIET_MS) { events.receive() } != null) {
            // discard
        }
    }

    suspend fun eventsTillQuiet(): List<HostEvent> {
        val came = ArrayList<HostEvent>()
        while (true) came += withTimeoutOrNull(QUIET_MS) { events.receive() } ?: return came
    }

    private companion object {
        // headroom over a GC or class-loading stall of the collector on a loaded emulator
        const val QUIET_MS = 500L

        /** fcitx only keys its input context table with it. */
        const val TEST_UID = 0
        const val TEST_PKG_NAME = "org.fcitx.fcitx5.android.test"
    }
}
