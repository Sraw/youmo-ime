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
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.engine.host.Engines

/**
 * A test class's own fcitx, its events forwarded to [events] for the tests to take one at a time:
 * [start] in the class's @BeforeClass, [stop] in its @AfterClass, [beforeTest] and [afterTest]
 * around each test. FcitxTest and FcitxEnglishTest share it, so a fix to how the events are read
 * is made once.
 */
class TestFcitx {

    lateinit var fcitx: Fcitx
        private set

    /**
     * Buffered, not CONFLATED. A conflated channel keeps only the newest event, so a
     * `CommitStringEvent` is overwritten by the `CandidateListEvent` fcitx emits right
     * after a commit -- the test then waits forever for an event that was dropped.
     */
    val events = Channel<FcitxEvent<*>>(capacity = Channel.UNLIMITED)

    /**
     * The input panel as of the newest event delivered here. `Fcitx.inputPanelCached` is
     * written on the fcitx thread without synchronisation, so reading it from the test
     * thread is a data race; this is written on the collector and read through a volatile.
     */
    @Volatile
    var latestInputPanel = FcitxEvent.InputPanelEvent.Data()
        private set

    /** The page of candidates as of the newest event, kept as [latestInputPanel] is. */
    @Volatile
    var latestPaged = FcitxEvent.PagedCandidateEvent.Data.Empty
        private set

    /**
     * Off the main thread: eventFlow drops its oldest event past 15 buffered, so a collector
     * held up by a main-thread stall could lose a `CommitStringEvent` before it reaches the channel.
     */
    private val scope = CoroutineScope(Dispatchers.Default)

    /** What [setUpEngine] saves over in the app's data, put back by [stop]. */
    private var enabledImeBefore: Array<String>? = null
    private var globalConfigBefore: RawConfig? = null

    /** The input methods enabled as a test started, put back by [afterTest]. */
    private var enabledIme: List<String> = listOf()

    /** Starts fcitx and sets it up for the tests, then runs a test class's own [setUp]. */
    fun start(setUp: suspend Fcitx.() -> Unit = {}) {
        checkThisInputMethodIsNotBound()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fcitx = Fcitx(context)

        // Forward to our channel for point to point consuming. UNDISPATCHED subscribes right
        // here, before `start()`: eventFlow keeps no replay, so a ReadyEvent emitted before a
        // dispatched collector got round to subscribing would be lost and setup would hang.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            fcitx.eventFlow.collect {
                if (it is FcitxEvent.InputPanelEvent) latestInputPanel = it.data
                if (it is FcitxEvent.PagedCandidateEvent) latestPaged = it.data
                events.send(it)
            }
        }
        fcitx.start()

        // The Timeout rule covers tests, not @BeforeClass; bound this separately so a
        // broken engine fails the run instead of hanging it.
        runBlocking {
            withTimeout(60_000) {
                setUpEngine()
                fcitx.setUp()
            }
        }
    }

    private suspend fun setUpEngine() {
        receiveFirst<FcitxEvent.ReadyEvent>()
        // Without an input context fcitx has nowhere to route keys: `sendKey` is
        // accepted but produces no preedit and no candidates, so every test that waits
        // for an event hangs. FcitxInputMethodService does this from `onBindInput` /
        // `onStartInputView`; a bare engine has to do it itself.
        fcitx.activate(TEST_UID, TEST_PKG_NAME)
        fcitx.focus(true)
        // both are saved to the app's data at once
        enabledImeBefore = fcitx.enabledIme().map { it.uniqueName }.toTypedArray()
        globalConfigBefore = fcitx.getGlobalConfig()["cfg"]
        // what the tests commit is not learned into the app's data: as in a field that asks
        // for no personalized learning (CapabilityFlags.fromEditorInfo)
        fcitx.setCapFlags(CapabilityFlags(CapabilityFlag.Sensitive))
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN))
        fcitx.setGlobalConfig(
            RawConfig(
                arrayOf(
                    RawConfig(
                        "Behavior", arrayOf(
                            RawConfig("ShowInputMethodInformation", false)
                        )
                    )
                )
            )
        )
    }

    /** Runs a test class's own [restore], puts back what [start] saved over, and stops fcitx. */
    fun stop(restore: suspend Fcitx.() -> Unit = {}) {
        // setup failed before it made one
        if (!::fcitx.isInitialized) return
        try {
            runBlocking {
                fcitx.restore()
                enabledImeBefore?.let { fcitx.setEnabledIme(it) }
                globalConfigBefore?.let { fcitx.setGlobalConfig(it) }
                fcitx.focus(false)
                fcitx.deactivate(TEST_UID)
            }
        } finally {
            fcitx.stop()
            scope.cancel()
        }
    }

    /**
     * Each test must start from a clean engine AND a clean event queue.
     *
     * The channel is buffered (see [events]), so events a previous test did not
     * consume are still queued, and `receiveFirst*` returns the OLDEST match -- which shows up
     * as a test asserting on another test's input. Draining here makes the tests independent
     * of each other and of their declaration order.
     *
     * `reset()` returns before the events it causes have come through the collector, so a
     * single sweep of the channel can finish before they land. Drain until it has been quiet
     * for a while instead, then forget the last panel too.
     */
    fun beforeTest() = runBlocking {
        fcitx.reset()
        awaitQuiet()
        latestInputPanel = FcitxEvent.InputPanelEvent.Data()
        latestPaged = FcitxEvent.PagedCandidateEvent.Data.Empty
        enabledIme = fcitx.enabledIme().map { it.uniqueName }
    }

    fun afterTest() = runBlocking {
        fcitx.setEnabledIme(enabledIme.toTypedArray())
    }

    suspend inline fun <reified T : FcitxEvent<*>> receiveFirst(): T? =
        events.receiveAsFlow().mapNotNull { it as? T }.firstOrNull()

    /**
     * Waits until no event has come for [QUIET_MS], dropping those that did: the engine's
     * refine slices may still reorder a list after it is first shown.
     */
    suspend fun awaitQuiet() {
        while (withTimeoutOrNull(QUIET_MS) { events.receive() } != null) {
            // discard
        }
    }

    /** As [awaitQuiet], but the events that came, for a test to see what a key did not do. */
    suspend fun eventsTillQuiet(): List<FcitxEvent<*>> {
        val came = ArrayList<FcitxEvent<*>>()
        while (true) came += withTimeoutOrNull(QUIET_MS) { events.receive() } ?: return came
    }

    private companion object {
        /**
         * How long the event channel must stay empty to count as drained. Events reach it through
         * the collector, which a GC or class loading on a loaded emulator can stall for a few
         * hundred ms; this leaves headroom over that.
         */
        const val QUIET_MS = 500L

        /** Any uid works; fcitx only uses it to key its input context table. */
        const val TEST_UID = 0
        const val TEST_PKG_NAME = "org.fcitx.fcitx5.android.test"
    }
}
