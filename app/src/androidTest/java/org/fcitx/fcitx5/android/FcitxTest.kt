/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.Keyboard
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
     * which is exactly what happened before `activate`/`focus` were added to [TestFcitx.start].
     */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private companion object {

        val harness = TestFcitx()
        val fcitx: Fcitx get() = harness.fcitx
        val latestInputPanel get() = harness.latestInputPanel
        val latestPaged get() = harness.latestPaged

        /** Pinyin's prediction as the app's data had it, put back by [cleanup]. */
        var predictionBefore: String? = null

        @BeforeClass
        @JvmStatic
        fun setup() = harness.start {
            // prediction on, whatever the app's data says: a commit then followed by none says learning is off
            predictionBefore = getImConfig(Engines.PINYIN)["cfg"][EngineSettings.PREDICTION].value
            setImConfig(Engines.PINYIN, RawConfig(arrayOf(RawConfig(EngineSettings.PREDICTION, true))))
        }

        @AfterClass
        @JvmStatic
        fun cleanup() = harness.stop {
            predictionBefore?.let { setImConfig(Engines.PINYIN, RawConfig(arrayOf(RawConfig(EngineSettings.PREDICTION, it)))) }
        }

        /** 五笔, the engine's own table (a key of [Engines.TABLES]). */
        const val WUBI = "engine-wubi"

        private suspend fun sendString(str: String) {
            str.forEach { c ->
                fcitx.sendKey(c)
                delay(50)
            }
        }

        private suspend inline fun <reified T : FcitxEvent<*>> receiveFirst(): T? = harness.receiveFirst<T>()

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

        private suspend fun awaitQuiet() = harness.awaitQuiet()

        private suspend fun eventsTillQuiet() = harness.eventsTillQuiet()

        /** Where Fcitx starts fcitx: its data/ and config/. */
        private fun fcitxHome(): File =
            FcitxApplication.getInstance().directBootAwareContext.let { it.getExternalFilesDir(null) ?: it.filesDir }
    }

    /** A clean engine and event queue for each test: see [TestFcitx.beforeTest]. */
    @Before
    fun resetEngineAndDrainEvents() = harness.beforeTest()

    @After
    fun restoreEnabledIME() = harness.afterTest()

    /**
     * `wqvb` is the table's 你好 and 您好, in the order of their use (TableText.addWords): the
     * model's, or on a device whose user picked one, theirs. So each is picked by content, and with
     * two candidates neither commits itself on the last key ([aFullCodeOfOneCandidateCommitsItself]
     * has a code of one). 你好 is typed by its characters too, 你 picked from `wq`'s candidates: what
     * this test is for is the code -> candidates -> select -> commit path.
     */
    @Test
    fun testWbx(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(WUBI))
        sendString("wqvb")
        awaitQuiet()
        val words = fcitx.getCandidates(0, 16).map { it.text }
        Timber.i("candidates for wubi 'wqvb' are $words")
        Assert.assertTrue("您好 and 你好 among candidates: $words", words.containsAll(listOf("您好", "你好")))
        fcitx.select(words.indexOf("您好"))
        Assert.assertEquals("您好", receiveFirstCommitString()?.data?.text)
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
     * Asserting `select(0)` would pin the engine's ranking: "nihaoshijie" reads as 你好世界 or
     * 你好时节, and either is a legitimate reading. What this test is for is
     * the sendKey -> candidates -> select -> commit path, not the engine's scoring.
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

    /**
     * A letter chosen from a nine-key's long press: the keyboard sends it as a character past the
     * BMP (Keyboard.letterKey), which fcitx must hand the engine whole. m then 4 is mi alone,
     * never ni.
     */
    @Test
    fun aLetterTypedAsItselfOnTheNineKeysReachesTheEngine(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(Engines.T9))
        fcitx.sendKey(0x01000000 + Keyboard.letterKey('m', again = false))
        sendString("4")
        awaitQuiet()
        val candidates = fcitx.getCandidates(0, 32).map { it.text }
        Timber.i("candidates for m, 4 are $candidates")
        Assert.assertTrue("米 among $candidates", "米" in candidates)
        Assert.assertFalse("no 你 among $candidates", "你" in candidates)
        fcitx.reset()
        // 连按: 2 four times fast, each press again a character past the BMP, read as keys: 爸爸
        sendString("2")
        "bca".forEach { fcitx.sendKey(0x01000000 + Keyboard.letterKey(it, again = true)) }
        awaitQuiet()
        val baba = fcitx.getCandidates(0, 32).map { it.text }
        Timber.i("candidates for 2 pressed four times fast are $baba")
        Assert.assertTrue("爸爸 among $baba", "爸爸" in baba)
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
     * bare engine whose capability flags (see [TestFcitx.start]) leave out `Preedit`, so fcitx has
     * no reason to believe the client can render a preedit and keeps it in the input panel. The
     * real service sets the capability flags in `onStartInput`, which is what moves it client-side.
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

    /** The preedit in the input panel has its cursor at its end, as one in the app has. */
    @Test
    fun theInputPanelsPreeditHasItsCursorAtItsEnd(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN))
        sendString("nihao")
        Assert.assertEquals("ni hao", awaitPanelPreedit("ni hao"))
        Assert.assertEquals("ni hao".length, latestInputPanel.preedit.cursor)
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

    /**
     * A file written through the documents provider, or the punctuation edited, has fcitx read its
     * config again, and nothing more: the bar has its candidates after it, in pinyin and on the nine
     * keys, for which a reload once left them unshown till fcitx restarted (15fb15963b; not seen
     * since on redroid, 2026-10-13).
     */
    @Test
    fun theBarGetsCandidatesAfterFcitxReadsItsConfigAgain(): Unit = runBlocking {
        for ((im, keys) in listOf(Engines.PINYIN to "ni", Engines.T9 to "64")) {
            fcitx.setEnabledIme(arrayOf(im))
            fcitx.reloadConfig()
            awaitQuiet()
            sendString(keys)
            val bar = eventsTillQuiet().filterIsInstance<FcitxEvent.CandidateListEvent>().lastOrNull()
                ?.data?.candidates?.map { it.text }.orEmpty()
            Assert.assertTrue("你 in the bar for $keys in $im: $bar", "你" in bar)
            fcitx.reset()
        }
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
     * The preedit is cleared, and the whole panel with it: where it learns the engine follows a
     * commit with predictions for the word just typed (`isEmpty()` false), prediction being on as
     * [setup] sets it, and the Sensitive flag [TestFcitx.start] sets, which keeps the tests' words out of the
     * app's data, turns learning off.
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
        Assert.assertTrue("nothing predicted: learning is off", fcitx.isEmpty())
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
        // 五笔's fae is 霰 and U+2298F
        fcitx.setEnabledIme(arrayOf(WUBI))
        sendString("fae")
        awaitQuiet()
        val shown = fcitx.getCandidates(0, 16).map { it.text }
        val at = shown.indexOf("𢦏")
        Assert.assertTrue("U+2298F among fae's: $shown", at >= 0)
        Assert.assertTrue("another of fae's beside it: $shown", shown.size >= 2)
        Assert.assertFalse("another's text at its index", fcitx.select(at, shown[if (at == 0) 1 else 0]))
        Assert.assertTrue("its own text at its index", fcitx.select(at, shown[at]))
        Assert.assertEquals(shown[at], receiveFirstCommitString()?.data?.text)
        fcitx.reset()
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
            Assert.assertEquals("the list from the first of all", firstWords, fcitx.getCandidates(0, firstWords.size).map { it.text })
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

    /**
     * A page turned to: the bar still shows the list from the first of all, those before the page
     * fetched as it asks for them, and a pick on the page commits that page's word, sent as its
     * index among all.
     */
    @Test
    fun aPageTurnedToKeepsTheBarWholeAndCommitsItsOwnWord(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN))
        try {
            sendString("ni")
            Assert.assertEquals("ni", awaitPanelPreedit("ni"))
            awaitQuiet()
            val all = fcitx.getCandidates(0, 16).map { it.text }
            fcitx.offsetCandidatePage(1)
            val bar = receiveFirst<FcitxEvent.CandidateListEvent>()?.data?.candidates?.map { it.text }
            Assert.assertEquals("the bar from the first of all", all, bar)
            awaitQuiet()
            fcitx.setCandidatePagingMode(1)
            awaitQuiet()
            val page = latestPaged
            val words = page.candidates.map { it.text }
            Assert.assertTrue("a page past the first for 'ni': $words", page.hasPrev && words.size >= 2)
            Assert.assertTrue("its own text at its index", fcitx.select(1, words[1]))
            Assert.assertEquals(words[1], receiveFirstCommitString()?.data?.text)
        } finally {
            fcitx.setCandidatePagingMode(0)
            fcitx.reset()
        }
    }

    // endregion

    // region code table input

    @Test
    fun wubiOffersCandidatesForAPartialCode(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(WUBI))
        sendString("wq")
        val candidates = fcitx.getCandidates(0, 16).map { it.text }
        Timber.i("candidates for wubi 'wq' are $candidates")
        Assert.assertTrue("expected some candidates for a partial code", candidates.isNotEmpty())
        fcitx.reset()
    }

    /**
     * A full code with one candidate commits it on its last key (唯一自动上屏): the code -> commit
     * path. 五笔's aaog is 工业 alone.
     */
    @Test
    fun aFullCodeOfOneCandidateCommitsItself(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(WUBI))
        sendString("aaog")
        Assert.assertEquals("工业", receiveFirstCommitString()?.data?.text)
        Assert.assertEquals("", awaitPanelPreedit(""))
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

        // Space and Return pick the first and type nothing more: no space after it, no message sent
        for (key in listOf("space", "Return")) {
            sendString("[")
            awaitQuiet()
            fcitx.sendKey(key)
            Assert.assertEquals(key, "【", receiveFirstCommitString()?.data?.text)
            // the key itself not passed on to the app (a space typed, a message sent), nor anything more committed
            val after = eventsTillQuiet()
            Assert.assertTrue("$key: $after", after.none { it is FcitxEvent.KeyEvent || it is FcitxEvent.CommitStringEvent })
            Assert.assertTrue(key, fcitx.isEmpty())
        }
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

    /** A shortcut puts in the first of the marks on offer before it goes to the app, as any other key does. */
    @Test
    fun aShortcutPutsInTheMarkOnOfferBeforeTheAppGetsIt(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN))
        sendString("[")
        Assert.assertEquals("【", awaitPanelPreedit("【"))
        awaitQuiet()
        fcitx.sendKey("v", KeyState.Ctrl.state)
        Assert.assertEquals("【", receiveFirstCommitString()?.data?.text)
        // then the key: what the app pastes goes in after the mark
        val key = receiveFirst<FcitxEvent.KeyEvent>()?.data
        Assert.assertEquals('v'.code, key?.unicode)
        Assert.assertEquals(true, key?.states?.ctrl)
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

    /** A character key reaches fcitx as that character: one the engine does not take comes back to the app as it. */
    @Test
    fun aCharacterKeyPastLatin1ReachesFcitxAsItself(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(Engines.PINYIN))
        // neither of its two bytes is 0: read as a C string, it had no end
        fcitx.sendKey('中')
        Assert.assertEquals('中'.code, receiveFirst<FcitxEvent.KeyEvent>()?.data?.unicode)
        fcitx.reset()
    }

}