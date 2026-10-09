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
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.table.TableConf
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import timber.log.Timber
import java.io.File

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

        /** The page of candidates as of the newest event, kept as [latestInputPanel] is. */
        @Volatile
        var latestPaged = FcitxEvent.PagedCandidateEvent.Data.Empty
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
                    if (it is FcitxEvent.PagedCandidateEvent) latestPaged = it.data
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

        /** 五笔, the engine's own table (a key of [Engines.TABLES]). */
        const val WUBI = "engine-wubi"

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

        /**
         * Waits until no event has come for [QUIET_MS], dropping those that did: the engine's
         * refine slices may still reorder a list after it is first shown.
         */
        private suspend fun awaitQuiet() {
            while (withTimeoutOrNull(QUIET_MS) { fcitxEventChannel.receive() } != null) {
                // discard
            }
        }

        /** Where Fcitx starts fcitx: its data/ and config/. */
        private fun fcitxHome(): File =
            FcitxApplication.getInstance().directBootAwareContext.let { it.getExternalFilesDir(null) ?: it.filesDir }

        /** What the page of the imported table [name] saved, as fcitx's table addon kept it. */
        private fun userTableConf(name: String) = File(fcitxHome(), "config/table/$name.conf")

        /**
         * Runs [block] with [name] imported as TableManager leaves a table, the text [table] and
         * [options] in its `[Table]`; then gone again, with what its page and the engine made of it.
         */
        private suspend fun withImportedTable(name: String, options: String, table: String, block: suspend () -> Unit) {
            val home = fcitxHome()
            val conf = File(home, "data/inputmethod/$name.conf")
            val text = File(home, "data/table/$name.txt")
            val userDir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "engine")
            try {
                conf.parentFile!!.mkdirs()
                text.parentFile!!.mkdirs()
                conf.writeText(
                    "[InputMethod]\nName=$name\nLangCode=zh_CN\nAddon=androidengine\nConfigurable=True\n\n" +
                        "[Table]\nFile=table/$name.txt\n$options"
                )
                text.writeText(table)
                // fcitx lists what is in inputmethod/ as it refreshes
                fcitx.reloadConfig()
                block()
            } finally {
                // closes what the engine opened of it
                fcitx.reloadEngine()
                (listOf(conf, text, userTableConf(name)) + Engines.addedTableFiles(name).map { File(userDir, it) })
                    .forEach { it.delete() }
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
        latestPaged = FcitxEvent.PagedCandidateEvent.Data.Empty
        enabledIme = fcitx.enabledIme().map { it.uniqueName }
    }

    @After
    fun restoreEnabledIME() = runBlocking {
        fcitx.setEnabledIme(enabledIme.toTypedArray())
    }

    /**
     * `wqvb` is 您好 alone: the table's data leaves out 你好, under a tenth as common as 您好 by the
     * model's unigram (TableText.addWords, lexicon/README.md), and a full code's one candidate
     * commits itself on its last key (唯一自动上屏). 你好 is typed by its characters, 你 picked
     * from `wq`'s candidates by content: what this test is for is the code -> commit and the
     * code -> candidates -> select -> commit paths.
     */
    @Test
    fun testWbx(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(WUBI))
        sendString("wqvb")
        val commitString = receiveFirstCommitString()?.data?.text
        Timber.i("commitString is $commitString")
        Assert.assertEquals("您好", commitString)
        awaitQuiet()
        Assert.assertEquals("nothing left to pick", emptyList<String>(), fcitx.getCandidates(0, 16).map { it.text })

        val expected = "你"
        sendString("wq")
        awaitQuiet()
        val candidates = fcitx.getCandidates(0, 16).map { it.text }
        Timber.i("candidates for wubi 'wq' are $candidates")
        val index = candidates.indexOf(expected)
        Assert.assertTrue("$expected not among candidates: $candidates", index >= 0)

        fcitx.select(index)
        Assert.assertEquals(expected, receiveFirstCommitString()?.data?.text)
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
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN))
        val expected = "你好世界"
        sendString("nihaoshijie")
        awaitQuiet()

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
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN, WUBI))
        fcitx.activateIme(Engines.PINYIN)
        Assert.assertEquals(Engines.PINYIN, fcitx.currentIme().uniqueName)
        fcitx.activateIme(WUBI)
        Assert.assertEquals(WUBI, fcitx.currentIme().uniqueName)
    }

    @Test
    fun enumerateImeCyclesThroughEnabledOnes(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN, WUBI))
        fcitx.activateIme(Engines.PINYIN)
        fcitx.enumerateIme(forward = true)
        Assert.assertEquals(WUBI, fcitx.currentIme().uniqueName)
        fcitx.enumerateIme(forward = true)
        Assert.assertEquals("cycles back round", Engines.PINYIN, fcitx.currentIme().uniqueName)
    }

    @Test
    fun enabledImeIsWhatWasSet(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN, WUBI))
        Assert.assertEquals(listOf(Engines.PINYIN, WUBI), fcitx.enabledIme().map { it.uniqueName })
    }

    @Test
    fun pinyinAndWubiAreBothAvailable(): Unit = runBlocking {
        val available = fcitx.availableIme().map { it.uniqueName }
        Assert.assertTrue("${Engines.PINYIN} missing from $available", Engines.PINYIN in available)
        Assert.assertTrue("$WUBI missing from $available", WUBI in available)
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
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN))
        sendString("nihao")
        val preedit = awaitPanelPreedit("ni hao")
        Timber.i("input panel preedit is $preedit")
        Assert.assertEquals("ni hao", preedit)
        fcitx.reset()
    }

    @Test
    fun aSingleSyllableOffersItsCommonCharacters(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN))
        sendString("ni")
        val candidates = fcitx.getCandidates(0, 16).map { it.text }
        Timber.i("candidates for 'ni' are $candidates")
        Assert.assertTrue("你 missing from $candidates", "你" in candidates)
        fcitx.reset()
    }

    @Test
    fun candidatesArePagedByOffsetAndLimit(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN))
        sendString("ni")
        awaitQuiet()
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
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN))
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
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN))
        sendString("ni")
        Assert.assertEquals("ni", awaitPanelPreedit("ni"))
        awaitQuiet()
        val first = fcitx.getCandidates(0, 1).first().text
        fcitx.select(0)
        Assert.assertEquals(first, receiveFirstCommitString()?.data?.text)
        Assert.assertEquals("preedit is consumed by the commit", "", awaitPanelPreedit(""))
        fcitx.reset()
    }

    /**
     * A tap carries the text it showed. A refine slice can reorder the list between the tap and
     * its pick, and the index then names another word: that pick is dropped, not committed.
     */
    @Test
    fun aPickOfTextTheCandidateNoLongerShowsIsDropped(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN))
        sendString("ni")
        Assert.assertEquals("ni", awaitPanelPreedit("ni"))
        awaitQuiet()
        val shown = fcitx.getCandidates(0, 2).map { it.text }
        Assert.assertEquals("two candidates for 'ni': $shown", 2, shown.size)
        Assert.assertFalse("the second's text at the first's index", fcitx.select(0, shown[1]))
        Assert.assertEquals("nothing was picked", shown, fcitx.getCandidates(0, 2).map { it.text })
        Assert.assertTrue("its own text at its index", fcitx.select(1, shown[1]))
        Assert.assertEquals(shown[1], receiveFirstCommitString()?.data?.text)
        fcitx.reset()
    }

    /**
     * The same past U+FFFF (CJK Ext-B, in the tables): JNI's modified UTF-8 spells such a
     * character as two surrogates, which no candidate's text equals.
     */
    @Test
    fun aPickOfACharacterPastTheBmpIsCheckedByItsText(): Unit = runBlocking {
        val name = "fcitxtest-extb"
        // U+20000 and U+20001
        withImportedTable(name, "", "KeyCode=z\nLength=2\n[Data]\nzz 𠀀\nzz 𠀁\n") {
            fcitx.setEnabledIme(arrayOf(name))
            sendString("zz")
            awaitQuiet()
            val shown = fcitx.getCandidates(0, 2).map { it.text }
            Assert.assertEquals("both of 'zz': $shown", setOf("𠀀", "𠀁"), shown.toSet())
            Assert.assertFalse("the second's text at the first's index", fcitx.select(0, shown[1]))
            Assert.assertTrue("its own text at its index", fcitx.select(0, shown[0]))
            Assert.assertEquals(shown[0], receiveFirstCommitString()?.data?.text)
            fcitx.reset()
        }
    }

    /**
     * A page at a time, as the floating window of a physical keyboard has them: arrows only where
     * there is a page, what space commits highlighted, and a pick of a page turned from dropped.
     */
    @Test
    fun pagedCandidatesTurnAndDropAPickOfAnotherPage(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN))
        fcitx.setCandidatePagingMode(1)
        try {
            sendString("ni")
            Assert.assertEquals("ni", awaitPanelPreedit("ni"))
            awaitQuiet()
            val first = latestPaged
            val firstWords = first.candidates.map { it.text }
            Assert.assertTrue("a page for 'ni': $firstWords", firstWords.size >= 2)
            Assert.assertEquals("what space commits", 0, first.cursorIndex)
            Assert.assertFalse("nothing before the first page", first.hasPrev)
            Assert.assertTrue("more than a page for 'ni'", first.hasNext)
            fcitx.offsetCandidatePage(1)
            awaitQuiet()
            val second = latestPaged
            val secondWords = second.candidates.map { it.text }
            Assert.assertTrue("a page to go back to", second.hasPrev)
            Assert.assertTrue("another page: $firstWords, $secondWords", firstWords.none { it in secondWords })
            Assert.assertEquals(0, second.cursorIndex)
            Assert.assertFalse("a word of the page turned from", fcitx.select(0, firstWords[0]))
            fcitx.offsetCandidatePage(-1)
            awaitQuiet()
            Assert.assertEquals("the first page again", firstWords, latestPaged.candidates.map { it.text })
            Assert.assertTrue("its own text at its index", fcitx.select(1, firstWords[1]))
            Assert.assertEquals(firstWords[1], receiveFirstCommitString()?.data?.text)
        } finally {
            fcitx.setCandidatePagingMode(0)
            fcitx.reset()
        }
    }

    // endregion

    // region code table input

    /**
     * An imported table's page shows and saves that table's options, in its own file and not over
     * pinyin's. A change to the value fcitx has as an option's default is written out all the
     * same (fcitx would comment it out, which the engine does not read); an option left alone is
     * not written, so one past the page's range stays the table's.
     */
    @Test
    fun anImportedTablesPageSavesItsOwnOptionsAndEachChangeSticks(): Unit = runBlocking {
        val name = "fcitxtest-options"
        val options = "AutoSelect=False\nHint=False\nOrderPolicy=Freq\nAutoPhraseLength=10\n"
        withImportedTable(name, options, "KeyCode=z\nLength=2\n[Data]\nzz 你\n") {
            val pinyin = fcitx.getImConfig(Engines.PINYIN)
            val page = fcitx.getImConfig(name)["cfg"]
            Assert.assertEquals("the table's own", "False", page["AutoSelect"].value)
            Assert.assertEquals("Freq", page["OrderPolicy"].value)
            // each the default of fcitx's option (EngineTableConfig)
            page["AutoSelect"].value = "True"
            page["Hint"].value = "True"
            page["OrderPolicy"].value = "No"
            fcitx.setImConfig(name, page)
            val saved = fcitx.getImConfig(name)["cfg"]
            for ((key, value) in listOf("AutoSelect" to "True", "Hint" to "True", "OrderPolicy" to "No")) {
                Assert.assertEquals(key, value, saved[key].value)
            }
            val conf = File(fcitxHome(), "data/inputmethod/$name.conf").readText()
            val read = TableConf.parse(conf, userTableConf(name).readText()).options
            Assert.assertTrue("as the engine reads it", read.autoSelect)
            Assert.assertTrue(read.hint)
            Assert.assertFalse(read.orderByUse)
            Assert.assertEquals("left alone", 10, read.autoPhraseLength)
            Assert.assertEquals("pinyin's page", pinyin, fcitx.getImConfig(Engines.PINYIN))
        }
    }

    @Test
    fun wubiOffersCandidatesForAPartialCode(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(WUBI))
        sendString("wq")
        val candidates = fcitx.getCandidates(0, 16).map { it.text }
        Timber.i("candidates for wubi 'wq' are $candidates")
        Assert.assertTrue("expected some candidates for a partial code", candidates.isNotEmpty())
        fcitx.reset()
    }

    // endregion

    // region punctuation

    // fcitx's zh_CN map: . has 。 alone, [ has 【 「 『 ❲ ［ [

    @Test
    fun aPunctuationKeyWithOneMarkTypesIt(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN))
        sendString(".")
        Assert.assertEquals("。", receiveFirstCommitString()?.data?.text)
        awaitQuiet()
        Assert.assertEquals("nothing to choose from", 0, fcitx.getCandidates(0, 16).size)
        fcitx.reset()
    }

    /**
     * A key with several marks offers them all, its first as typed: the next key puts that in
     * and goes on, a tap puts in another, Backspace none.
     */
    @Test
    fun aPunctuationKeyWithSeveralMarksOffersThemItsFirstGoingIn(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN))
        sendString("[")
        Assert.assertEquals("【", awaitPanelPreedit("【"))
        awaitQuiet()
        Assert.assertEquals(listOf("【", "「", "『", "❲", "［", "["), fcitx.getCandidates(0, 16).map { it.text })
        sendString("1")
        Assert.assertEquals("【", receiveFirstCommitString()?.data?.text)

        sendString("[")
        awaitQuiet()
        Assert.assertTrue(fcitx.select(1, "「"))
        Assert.assertEquals("「", receiveFirstCommitString()?.data?.text)

        sendString("[")
        awaitQuiet()
        fcitx.sendKey("BackSpace")
        Assert.assertEquals("", awaitPanelPreedit(""))
        Assert.assertTrue("the key taken back", fcitx.isEmpty())
        fcitx.reset()
    }

    /**
     * A pair as a key's first card is one choice, the half the key types: its other half is not
     * offered beside it. The halves come in turn, fcitx's default (TypePairedPunctuationsTogether=False).
     */
    @Test
    fun aPairAsAKeysFirstCardIsOneChoice(): Unit = runBlocking {
        val shipped = PunctuationManager.load(fcitx, "zh_CN")
        val saved = File(fcitxHome(), "data/punctuation/punc.mb.zh_CN")
        val hadSaved = saved.exists()
        try {
            // " with a card after its pair, as the editor page lets one add
            PunctuationManager.save(
                fcitx, "zh_CN",
                shipped.filter { it.key != "\"" } + PunctuationMapEntry("\"", "“", "”") + PunctuationMapEntry("\"", "＂", "")
            )
            fcitx.setEnabledIme(arrayOf(Engines.PINYIN))
            sendString("\"")
            Assert.assertEquals("“", awaitPanelPreedit("“"))
            awaitQuiet()
            Assert.assertEquals(listOf("“", "＂"), fcitx.getCandidates(0, 16).map { it.text })
            Assert.assertTrue(fcitx.select(0, "“"))
            Assert.assertEquals("“", receiveFirstCommitString()?.data?.text)

            sendString("\"")
            Assert.assertEquals("the other half next", "”", awaitPanelPreedit("”"))
            awaitQuiet()
            Assert.assertEquals(listOf("”", "＂"), fcitx.getCandidates(0, 16).map { it.text })
            Assert.assertTrue(fcitx.select(1, "＂"))
            Assert.assertEquals("＂", receiveFirstCommitString()?.data?.text)
        } finally {
            fcitx.reset()
            PunctuationManager.save(fcitx, "zh_CN", shipped)
            // the save above wrote a map of the user's where there was none
            if (!hadSaved) saved.delete()
        }
    }

    /**
     * A pair's half on offer opens or closes the pair only as it goes in: after another card is
     * picked, or the key is taken back with Backspace, the key opens the pair again.
     */
    @Test
    fun aPairsHalfNotPutInLeavesThePairAsItWas(): Unit = runBlocking {
        val shipped = PunctuationManager.load(fcitx, "zh_CN")
        val saved = File(fcitxHome(), "data/punctuation/punc.mb.zh_CN")
        val hadSaved = saved.exists()
        try {
            PunctuationManager.save(
                fcitx, "zh_CN",
                shipped.filter { it.key != "\"" } + PunctuationMapEntry("\"", "“", "”") + PunctuationMapEntry("\"", "＂", "")
            )
            fcitx.setEnabledIme(arrayOf(Engines.PINYIN))
            sendString("\"")
            Assert.assertEquals("“", awaitPanelPreedit("“"))
            awaitQuiet()
            Assert.assertTrue(fcitx.select(1, "＂"))
            Assert.assertEquals("＂", receiveFirstCommitString()?.data?.text)
            // gone first, so that the “ below is the next press's and not a leftover
            Assert.assertEquals("", awaitPanelPreedit(""))

            sendString("\"")
            Assert.assertEquals("another card picked", "“", awaitPanelPreedit("“"))
            awaitQuiet()
            fcitx.sendKey("BackSpace")
            Assert.assertEquals("", awaitPanelPreedit(""))

            sendString("\"")
            Assert.assertEquals("taken back", "“", awaitPanelPreedit("“"))
            awaitQuiet()
            // the next key puts in the half on offer, and this press then closes the pair
            sendString("\"")
            Assert.assertEquals("“", receiveFirstCommitString()?.data?.text)
            Assert.assertEquals("”", awaitPanelPreedit("”"))
        } finally {
            fcitx.reset()
            PunctuationManager.save(fcitx, "zh_CN", shipped)
            if (!hadSaved) saved.delete()
        }
    }

    /**
     * The same for the half that closes it: with “ in, the key offers ” after another card is
     * picked or Backspace, till ” goes in.
     */
    @Test
    fun aClosingHalfNotPutInLeavesThePairOpen(): Unit = runBlocking {
        val shipped = PunctuationManager.load(fcitx, "zh_CN")
        val saved = File(fcitxHome(), "data/punctuation/punc.mb.zh_CN")
        val hadSaved = saved.exists()
        try {
            PunctuationManager.save(
                fcitx, "zh_CN",
                shipped.filter { it.key != "\"" } + PunctuationMapEntry("\"", "“", "”") + PunctuationMapEntry("\"", "＂", "")
            )
            fcitx.setEnabledIme(arrayOf(Engines.PINYIN))
            sendString("\"")
            Assert.assertEquals("“", awaitPanelPreedit("“"))
            awaitQuiet()
            Assert.assertTrue(fcitx.select(0, "“"))
            Assert.assertEquals("“", receiveFirstCommitString()?.data?.text)

            sendString("\"")
            Assert.assertEquals("”", awaitPanelPreedit("”"))
            awaitQuiet()
            Assert.assertTrue(fcitx.select(1, "＂"))
            Assert.assertEquals("＂", receiveFirstCommitString()?.data?.text)
            // gone first, so that the ” below is the next press's and not a leftover
            Assert.assertEquals("", awaitPanelPreedit(""))

            sendString("\"")
            Assert.assertEquals("another card picked", "”", awaitPanelPreedit("”"))
            awaitQuiet()
            fcitx.sendKey("BackSpace")
            Assert.assertEquals("", awaitPanelPreedit(""))

            sendString("\"")
            Assert.assertEquals("taken back", "”", awaitPanelPreedit("”"))
            awaitQuiet()
            // the next key puts in ”, which closes the pair, and this press then opens it again
            sendString("\"")
            Assert.assertEquals("”", receiveFirstCommitString()?.data?.text)
            Assert.assertEquals("“", awaitPanelPreedit("“"))
        } finally {
            fcitx.reset()
            PunctuationManager.save(fcitx, "zh_CN", shipped)
            if (!hadSaved) saved.delete()
        }
    }

    /** Switching input method puts in the first of the marks on offer, as it keeps what was typed. */
    @Test
    fun switchingInputMethodPutsInTheMarkOnOffer(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN, Engines.SHUANGPIN))
        fcitx.activateIme(Engines.PINYIN)
        sendString("[")
        Assert.assertEquals("【", awaitPanelPreedit("【"))
        awaitQuiet()
        fcitx.activateIme(Engines.SHUANGPIN)
        Assert.assertEquals("【", receiveFirstCommitString()?.data?.text)
        Assert.assertEquals("", awaitPanelPreedit(""))
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