/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.RawConfig
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import timber.log.Timber

class FcitxTest {

    /**
     * Without this a broken engine interaction hangs the whole run instead of failing it --
     * which is exactly what happened before `activate`/`focus` were added to [setup].
     */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private companion object {

        lateinit var fcitx: Fcitx
        /**
         * Buffered, not CONFLATED. A conflated channel keeps only the newest event, so a
         * `CommitStringEvent` is overwritten by the `CandidateListEvent` fcitx emits right
         * after a commit -- the test then waits forever for an event that was dropped.
         */
        val fcitxEventChannel = Channel<FcitxEvent<*>>(capacity = Channel.UNLIMITED)

        /**
         * The input panel as of the newest event delivered here. `Fcitx.inputPanelCached` is
         * written on the fcitx thread without synchronisation, so reading it from the test
         * thread is a data race; this is written on the collector and read through a volatile.
         */
        @Volatile
        var latestInputPanel = FcitxEvent.InputPanelEvent.Data()
        val scope = MainScope()

        @BeforeClass
        @JvmStatic
        fun setup() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            fcitx = Fcitx(context)

            // Forward to our channel for point to point consuming. UNDISPATCHED subscribes right
            // here, before `start()`: eventFlow keeps no replay, so a ReadyEvent emitted before a
            // dispatched collector got round to subscribing would be lost and setup would hang.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                fcitx.eventFlow.collect {
                    if (it is FcitxEvent.InputPanelEvent) latestInputPanel = it.data
                    fcitxEventChannel.send(it)
                }
            }
            fcitx.start()

            // The Timeout rule covers tests, not @BeforeClass; bound this separately so a
            // broken engine fails the run instead of hanging it.
            runBlocking {
                withTimeout(60_000) { setUpEngine() }
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
            fcitx.setEnabledIme(arrayOf("pinyin"))
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

        @AfterClass
        @JvmStatic
        fun cleanup() {
            runBlocking {
                fcitx.focus(false)
                fcitx.deactivate(TEST_UID)
            }
            fcitx.stop()
            scope.cancel()
        }

        /**
         * How long the event channel must stay empty to count as drained. The collector runs on
         * the main thread, which a GC or class loading on a loaded emulator can stall for a few
         * hundred ms; this leaves headroom over that.
         */
        const val QUIET_MS = 500L

        /** Any uid works; fcitx only uses it to key its input context table. */
        const val TEST_UID = 0
        const val TEST_PKG_NAME = "org.fcitx.fcitx5.android.test"

        private suspend fun sendString(str: String) {
            str.forEach { c ->
                fcitx.sendKey(c)
                delay(50)
            }
        }

        private suspend inline fun <reified T : FcitxEvent<*>> receiveFirst(): T? =
            fcitxEventChannel.receiveAsFlow().mapNotNull { it as? T }.firstOrNull()

        private suspend fun receiveFirstCommitString() =
            receiveFirst<FcitxEvent.CommitStringEvent>()

        private suspend fun receiveFirstPreedit() = receiveFirst<FcitxEvent.ClientPreeditEvent>()

        /**
         * Waits for the input panel to show [expected] as its preedit. The panel is updated
         * when fcitx flushes its UI, which is not necessarily done by the time `sendKey`
         * returns, so asserting straight after typing races it.
         *
         * @return the preedit last seen: [expected], unless it did not arrive in time
         */
        private suspend fun awaitPanelPreedit(expected: String, timeoutMs: Long = 5_000): String {
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (true) {
                val preedit = latestInputPanel.preedit.toString()
                if (preedit == expected || SystemClock.uptimeMillis() >= deadline) return preedit
                delay(20)
            }
        }


    }

    private var enabledIme: List<String> = listOf()

    /**
     * Each test must start from a clean engine AND a clean event queue.
     *
     * The channel is buffered (see [fcitxEventChannel]), so events a previous test did not
     * consume are still queued, and `receiveFirst*` returns the OLDEST match -- which shows up
     * as a test asserting on another test's input. Draining here makes the tests independent
     * of each other and of their declaration order.
     *
     * `reset()` returns before the events it causes have come through the collector, so a
     * single sweep of the channel can finish before they land. Drain until it has been quiet
     * for a while instead, then forget the last panel too.
     */
    @Before
    fun resetEngineAndDrainEvents() = runBlocking {
        fcitx.reset()
        while (withTimeoutOrNull(QUIET_MS) { fcitxEventChannel.receive() } != null) {
            // discard
        }
        latestInputPanel = FcitxEvent.InputPanelEvent.Data()
        enabledIme = fcitx.enabledIme().map { it.uniqueName }
    }

    @After
    fun restoreEnabledIME() = runBlocking {
        fcitx.setEnabledIme(enabledIme.toTypedArray())
    }

    /** A code table is deterministic, so the first candidate can be asserted directly. */
    @Test
    fun testWbx(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf("wbx"))
        sendString("wqvb")
        fcitx.select(0)
        val commitString = receiveFirstCommitString()?.data?.text
        Timber.i("commitString is $commitString")
        Assert.assertEquals("你好", commitString)
        fcitx.reset()
    }

    /**
     * Picks the candidate by content rather than by index.
     *
     * Asserting `select(0)` would pin the dictionary's ranking: "nihaoshijie" currently offers
     * 你好时节 ahead of 你好世界, and either is a legitimate reading. What this test is for is
     * the sendKey -> candidates -> select -> commit path, not libime's scoring.
     */
    @Test
    fun testPinyin(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf("pinyin"))
        val expected = "你好世界"
        sendString("nihaoshijie")

        val candidates = fcitx.getCandidates(0, 32).map { it.text }
        Timber.i("candidates are $candidates")
        val index = candidates.indexOf(expected)
        Assert.assertTrue("$expected not among candidates: $candidates", index >= 0)

        fcitx.select(index)
        val commitString = receiveFirstCommitString()?.data?.text
        Timber.i("commitString is $commitString")
        Assert.assertEquals(expected, commitString)
        fcitx.reset()
    }

    // region input method management

    @Test
    fun switchingInputMethodChangesTheCurrentOne(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf("pinyin", "wbx"))
        fcitx.activateIme("pinyin")
        Assert.assertEquals("pinyin", fcitx.currentIme().uniqueName)
        fcitx.activateIme("wbx")
        Assert.assertEquals("wbx", fcitx.currentIme().uniqueName)
    }

    @Test
    fun enumerateImeCyclesThroughEnabledOnes(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf("pinyin", "wbx"))
        fcitx.activateIme("pinyin")
        fcitx.enumerateIme(forward = true)
        Assert.assertEquals("wbx", fcitx.currentIme().uniqueName)
        fcitx.enumerateIme(forward = true)
        Assert.assertEquals("cycles back round", "pinyin", fcitx.currentIme().uniqueName)
    }

    @Test
    fun enabledImeIsWhatWasSet(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf("pinyin", "wbx"))
        Assert.assertEquals(listOf("pinyin", "wbx"), fcitx.enabledIme().map { it.uniqueName })
    }

    @Test
    fun pinyinAndWubiAreBothAvailable(): Unit = runBlocking {
        val available = fcitx.availableIme().map { it.uniqueName }
        Assert.assertTrue("pinyin missing from $available", "pinyin" in available)
        Assert.assertTrue("wbx missing from $available", "wbx" in available)
    }

    // endregion

    // region pinyin decoding

    /**
     * Waits for the finished composition rather than taking the first preedit event:
     * `sendString` produces one per keystroke, so the first would be "n".
     *
     * Uses the input panel's preedit, not [FcitxAPI.clientPreeditCached]. This test drives a
     * bare engine and never calls `setCapFlags`, so fcitx has no reason to believe the client
     * can render a preedit and keeps it in the input panel. The real service sets the
     * capability flags in `onStartInput`, which is what moves it client-side.
     */
    @Test
    fun pinyinSegmentsSyllablesInThePreedit(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf("pinyin"))
        sendString("nihao")
        val preedit = awaitPanelPreedit("ni hao")
        Timber.i("input panel preedit is $preedit")
        Assert.assertEquals("ni hao", preedit)
        fcitx.reset()
    }

    @Test
    fun aSingleSyllableOffersItsCommonCharacters(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf("pinyin"))
        sendString("ni")
        val candidates = fcitx.getCandidates(0, 16).map { it.text }
        Timber.i("candidates for 'ni' are $candidates")
        Assert.assertTrue("你 missing from $candidates", "你" in candidates)
        fcitx.reset()
    }

    @Test
    fun candidatesArePagedByOffsetAndLimit(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf("pinyin"))
        sendString("ni")
        val firstFour = fcitx.getCandidates(0, 4).map { it.text }
        val nextFour = fcitx.getCandidates(4, 4).map { it.text }
        Assert.assertEquals(4, firstFour.size)
        Assert.assertEquals(4, nextFour.size)
        Assert.assertTrue("pages overlap: $firstFour vs $nextFour",
            firstFour.intersect(nextFour.toSet()).isEmpty())
        fcitx.reset()
    }

    @Test
    fun resetClearsPreeditAndCandidates(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf("pinyin"))
        sendString("nihao")
        Assert.assertFalse("engine should hold a composition", fcitx.isEmpty())
        // seen first, so that an empty preedit below is the reset's doing and not a leftover
        Assert.assertEquals("ni hao", awaitPanelPreedit("ni hao"))
        fcitx.reset()
        Assert.assertTrue("reset should clear it", fcitx.isEmpty())
        Assert.assertEquals(0, fcitx.getCandidates(0, 8).size)
        Assert.assertEquals("", awaitPanelPreedit(""))
    }

    /**
     * Note what is asserted: the *preedit* is cleared, not the whole engine. After a commit
     * fcitx offers prediction candidates for the word just typed, so `isEmpty()` is still
     * false -- that is correct behaviour, not leftover state.
     */
    @Test
    fun selectingACandidateCommitsItAndClearsThePreedit(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf("pinyin"))
        sendString("ni")
        Assert.assertEquals("ni", awaitPanelPreedit("ni"))
        val first = fcitx.getCandidates(0, 1).first().text
        fcitx.select(0)
        Assert.assertEquals(first, receiveFirstCommitString()?.data?.text)
        Assert.assertEquals("preedit is consumed by the commit", "", awaitPanelPreedit(""))
        fcitx.reset()
    }

    // endregion

    // region code table input

    @Test
    fun wubiOffersCandidatesForAPartialCode(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf("wbx"))
        sendString("wq")
        val candidates = fcitx.getCandidates(0, 16).map { it.text }
        Timber.i("candidates for wubi 'wq' are $candidates")
        Assert.assertTrue("expected some candidates for a partial code", candidates.isNotEmpty())
        fcitx.reset()
    }

    // endregion

    @Test
    fun testInputPanelStatus(): Unit = runBlocking {
        fcitx.reset()
        Timber.i("after first reset: ${fcitx.isEmpty()}")
        Assert.assertEquals(true, fcitx.isEmpty())
        fcitx.sendKey('a')
        // isEmpty() asks the engine directly, so there is no event to wait for
        Timber.i("after sending 'a': ${fcitx.isEmpty()}")
        Assert.assertEquals(false, fcitx.isEmpty())
        fcitx.reset()
        Timber.i("after second reset: ${fcitx.isEmpty()}")
        Assert.assertEquals(true, fcitx.isEmpty())
    }

}