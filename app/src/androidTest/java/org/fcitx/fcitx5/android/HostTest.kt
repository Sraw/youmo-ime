/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.core.HostInstance
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.data.punctuation.PunctuationManager
import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.host.ConfigHome
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.PunctuationTable
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

class HostTest {

    // without it a broken engine interaction hangs the whole run instead of failing it
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private companion object {

        val harness = TestHost()
        val fcitx: HostInstance get() = harness.fcitx
        val latestInputPanel get() = harness.latestInputPanel
        val latestPaged get() = harness.latestPaged

        var predictionBefore: String? = null

        @BeforeClass
        @JvmStatic
        fun setup() = harness.start {
            // prediction on: a commit then followed by none says learning is off
            predictionBefore = imOptions(InputMethodCatalog.PINYIN)?.get(EngineSettings.PREDICTION).orEmpty()
            setImOptions(InputMethodCatalog.PINYIN, mapOf(EngineSettings.PREDICTION to "True"))
        }

        @AfterClass
        @JvmStatic
        fun cleanup() = harness.stop {
            predictionBefore?.let { setImOptions(InputMethodCatalog.PINYIN, mapOf(EngineSettings.PREDICTION to it)) }
        }

        const val WUBI = "engine-wubi"

        private suspend fun sendString(str: String) {
            str.forEach { c ->
                fcitx.sendKey(c)
                delay(50)
            }
        }

        /** Each digit its own code (Keyboard.pinyinNineKey). */
        private suspend fun sendNineKeys(digits: String) {
            digits.forEach { d ->
                fcitx.sendKey(0x01000000 + Keyboard.pinyinNineKey(d))
                delay(50)
            }
        }

        private suspend inline fun <reified T : HostEvent> receiveFirst(): T? = harness.receiveFirst<T>()

        private suspend fun receiveFirstCommitString() =
            receiveFirst<HostEvent.Commit>()

        private suspend fun receiveFirstPreedit() = receiveFirst<HostEvent.ClientPreedit>()

        /** The panel updates when fcitx flushes its UI, not necessarily before `sendKey` returns; returns the last preedit seen. */
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

        private fun fcitxData(): File = HostConfigDir.inUse(ConfigHome.DATA).dir
    }

    @Before
    fun resetEngineAndDrainEvents() = harness.beforeTest()

    @After
    fun restoreEnabledIME() = harness.afterTest()

    /** `wqvb` gives 你好 and 您好 in usage order, so picks go by content; with two candidates neither commits itself. */
    @Test
    fun testWbx(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(WUBI))
        sendString("wqvb")
        awaitQuiet()
        val words = fcitx.getCandidates(0, 16).map { it.text }
        Timber.i("candidates for wubi 'wqvb' are $words")
        Assert.assertTrue("您好 and 你好 among candidates: $words", words.containsAll(listOf("您好", "你好")))
        fcitx.select(words.indexOf("您好"))
        Assert.assertEquals("您好", receiveFirstCommitString()?.text)
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
        Assert.assertEquals(expected, receiveFirstCommitString()?.text)
        fcitx.reset()
    }

    // by content, not index: 你好世界 and 你好时节 are both fair readings, and the ranking is not under test
    @Test
    fun testPinyin(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
        val expected = "你好世界"
        sendString("nihaoshijie")
        awaitQuiet()

        val candidates = fcitx.getCandidates(0, 32).map { it.text }
        Timber.i("candidates are $candidates")
        val index = candidates.indexOf(expected)
        Assert.assertTrue("$expected not among candidates: $candidates", index >= 0)

        fcitx.select(index)
        val commitString = receiveFirstCommitString()?.text
        Timber.i("commitString is $commitString")
        Assert.assertEquals(expected, commitString)
        fcitx.reset()
    }

    /** The long press sends the letter past the BMP (Keyboard.letterKey), which fcitx must pass whole: m then 4 is mi, never ni. */
    @Test
    fun aLetterTypedAsItselfOnTheNineKeysReachesTheEngine(): Unit = runBlocking {
        // nine-key digits are codes of their own (Keyboard.pinyinNineKey)
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
        fcitx.sendKey(0x01000000 + Keyboard.letterKey('m', again = false))
        sendNineKeys("4")
        awaitQuiet()
        val candidates = fcitx.getCandidates(0, 32).map { it.text }
        Timber.i("candidates for m, 4 are $candidates")
        Assert.assertTrue("米 among $candidates", "米" in candidates)
        Assert.assertFalse("no 你 among $candidates", "你" in candidates)
        fcitx.reset()
        // 连按: 2 four times fast is a, typed as itself (啊), never 2222 (爸爸)
        sendNineKeys("2")
        "bca".forEach { fcitx.sendKey(0x01000000 + Keyboard.letterKey(it, again = true)) }
        awaitQuiet()
        val a = fcitx.getCandidates(0, 32).map { it.text }
        Timber.i("candidates for 2 pressed four times fast are $a")
        Assert.assertTrue("啊 among $a", "啊" in a)
        Assert.assertFalse("no 爸爸 among $a", "爸爸" in a)
        fcitx.reset()
    }

    @Test
    fun switchingInputMethodChangesTheCurrentOne(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, WUBI))
        fcitx.activateIme(InputMethodCatalog.PINYIN)
        Assert.assertEquals(InputMethodCatalog.PINYIN, fcitx.currentIme().uniqueName)
        fcitx.activateIme(WUBI)
        Assert.assertEquals(WUBI, fcitx.currentIme().uniqueName)
    }

    @Test
    fun enumerateImeCyclesThroughEnabledOnes(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, WUBI))
        fcitx.activateIme(InputMethodCatalog.PINYIN)
        fcitx.enumerateIme(forward = true)
        Assert.assertEquals(WUBI, fcitx.currentIme().uniqueName)
        fcitx.enumerateIme(forward = true)
        Assert.assertEquals("cycles back round", InputMethodCatalog.PINYIN, fcitx.currentIme().uniqueName)
    }

    /** Told as one HostEvent.InputMethodChanged, both focused (re-activation) and unfocused (InputMethodGroupChanged). */
    @Test
    fun aListSetAnewWithTheInputMethodKeptIsToldOnce(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
        awaitQuiet()
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, WUBI))
        Assert.assertEquals("focused", 1, eventsTillQuiet().count { it is HostEvent.InputMethodChanged })
        fcitx.focus(false)
        try {
            awaitQuiet()
            fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
            Assert.assertEquals("unfocused", 1, eventsTillQuiet().count { it is HostEvent.InputMethodChanged })
        } finally {
            fcitx.focus(true)
        }
    }

    @Test
    fun enabledImeIsWhatWasSet(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, WUBI))
        Assert.assertEquals(listOf(InputMethodCatalog.PINYIN, WUBI), fcitx.enabledIme().map { it.uniqueName })
    }

    @Test
    fun pinyinAndWubiAreBothAvailable(): Unit = runBlocking {
        val available = fcitx.availableIme().map { it.uniqueName }
        Assert.assertTrue("${InputMethodCatalog.PINYIN} missing from $available", InputMethodCatalog.PINYIN in available)
        Assert.assertTrue("$WUBI missing from $available", WUBI in available)
    }

    /**
     * Waits for the whole composition: `sendString` gives one preedit per keystroke. Reads the panel's preedit,
     * since [TestHost.start]'s flags leave out `Preedit` and fcitx keeps it in the panel.
     */
    @Test
    fun pinyinSegmentsSyllablesInThePreedit(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
        sendString("nihao")
        val preedit = awaitPanelPreedit("ni hao")
        Timber.i("input panel preedit is $preedit")
        Assert.assertEquals("ni hao", preedit)
        fcitx.reset()
    }

    @Test
    fun theInputPanelsPreeditHasItsCursorAtItsEnd(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
        sendString("nihao")
        Assert.assertEquals("ni hao", awaitPanelPreedit("ni hao"))
        Assert.assertEquals("ni hao".length, latestInputPanel.preedit.cursor)
        fcitx.reset()
    }

    @Test
    fun aSingleSyllableOffersItsCommonCharacters(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
        sendString("ni")
        val candidates = fcitx.getCandidates(0, 16).map { it.text }
        Timber.i("candidates for 'ni' are $candidates")
        Assert.assertTrue("你 missing from $candidates", "你" in candidates)
        fcitx.reset()
    }

    /** A reload once left the bar empty till fcitx restarted. */
    @Test
    fun theBarGetsCandidatesAfterFcitxReadsItsConfigAgain(): Unit = runBlocking {
        for ((nine, keys) in listOf(false to "ni", true to "64")) {
            fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
            fcitx.reloadConfig()
            awaitQuiet()
            if (nine) sendNineKeys(keys) else sendString(keys)
            val bar = eventsTillQuiet().filterIsInstance<HostEvent.Candidates>().lastOrNull()
                ?.candidates?.map { it.text }.orEmpty()
            Assert.assertTrue("你 in the bar for $keys: $bar", "你" in bar)
            fcitx.reset()
        }
    }

    @Test
    fun candidatesArePagedByOffsetAndLimit(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
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
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
        sendString("nihao")
        Assert.assertFalse("engine should hold a composition", fcitx.isEmpty())
        // so an empty preedit below is the reset's doing, not a leftover
        Assert.assertEquals("ni hao", awaitPanelPreedit("ni hao"))
        fcitx.reset()
        Assert.assertTrue("reset should clear it", fcitx.isEmpty())
        Assert.assertEquals(0, fcitx.getCandidates(0, 8).size)
        Assert.assertEquals("", awaitPanelPreedit(""))
    }

    /** The whole panel clears: the Sensitive flag turns learning off, so no predictions follow the commit. */
    @Test
    fun selectingACandidateCommitsItAndClearsThePreedit(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
        sendString("ni")
        Assert.assertEquals("ni", awaitPanelPreedit("ni"))
        awaitQuiet()
        val first = fcitx.getCandidates(0, 1).first().text
        fcitx.select(0)
        Assert.assertEquals(first, receiveFirstCommitString()?.text)
        Assert.assertEquals("preedit is consumed by the commit", "", awaitPanelPreedit(""))
        Assert.assertTrue("nothing predicted: learning is off", fcitx.isEmpty())
        fcitx.reset()
    }

    /** A refine can reorder the list between tap and pick; the pick names its text, so a stale index is dropped. */
    @Test
    fun aPickOfTextTheCandidateNoLongerShowsIsDropped(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
        sendString("ni")
        Assert.assertEquals("ni", awaitPanelPreedit("ni"))
        awaitQuiet()
        val shown = fcitx.getCandidates(0, 2).map { it.text }
        Assert.assertEquals("two candidates for 'ni': $shown", 2, shown.size)
        Assert.assertFalse("the second's text at the first's index", fcitx.select(0, shown[1]))
        Assert.assertEquals("nothing was picked", shown, fcitx.getCandidates(0, 2).map { it.text })
        Assert.assertTrue("its own text at its index", fcitx.select(1, shown[1]))
        Assert.assertEquals(shown[1], receiveFirstCommitString()?.text)
        fcitx.reset()
    }

    /** JNI's modified UTF-8 spells such a character as two surrogates, which no candidate's text equals. */
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
        Assert.assertEquals(shown[at], receiveFirstCommitString()?.text)
        fcitx.reset()
    }

    /** As a physical keyboard's floating window pages: arrows only where there is a page, and a pick from a page turned from is dropped. */
    @Test
    fun pagedCandidatesTurnAndDropAPickOfAnotherPage(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
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
            Assert.assertEquals(firstWords[1], receiveFirstCommitString()?.text)
        } finally {
            fcitx.setCandidatePagingMode(0)
            fcitx.reset()
        }
    }

    /** The bar keeps the whole list from the first; a pick on the page is sent as its index among all. */
    @Test
    fun aPageTurnedToKeepsTheBarWholeAndCommitsItsOwnWord(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
        try {
            sendString("ni")
            Assert.assertEquals("ni", awaitPanelPreedit("ni"))
            awaitQuiet()
            val all = fcitx.getCandidates(0, 16).map { it.text }
            fcitx.offsetCandidatePage(1)
            val bar = receiveFirst<HostEvent.Candidates>()?.candidates?.map { it.text }
            Assert.assertEquals("the bar from the first of all", all, bar)
            awaitQuiet()
            fcitx.setCandidatePagingMode(1)
            awaitQuiet()
            val page = latestPaged
            val words = page.candidates.map { it.text }
            Assert.assertTrue("a page past the first for 'ni': $words", page.hasPrev && words.size >= 2)
            Assert.assertTrue("its own text at its index", fcitx.select(1, words[1]))
            Assert.assertEquals(words[1], receiveFirstCommitString()?.text)
        } finally {
            fcitx.setCandidatePagingMode(0)
            fcitx.reset()
        }
    }

    /** As PageDown/PageUp, nothing reaching the app; with nothing typed - is itself. */
    @Test
    fun equalsAndMinusTurnThePageWhileSomethingIsTyped(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
        fcitx.setCandidatePagingMode(1)
        try {
            sendString("ni")
            Assert.assertEquals("ni", awaitPanelPreedit("ni"))
            awaitQuiet()
            val first = latestPaged.candidates.map { it.text }
            fcitx.sendKey('=')
            val turned = eventsTillQuiet()
            Assert.assertTrue("$turned", turned.none { it is HostEvent.Key || it is HostEvent.Commit })
            val second = latestPaged.candidates.map { it.text }
            Assert.assertTrue("a page to go back to", latestPaged.hasPrev)
            Assert.assertTrue("another page: $first, $second", second.none { it in first })
            fcitx.sendKey('-')
            awaitQuiet()
            Assert.assertEquals("the first page again", first, latestPaged.candidates.map { it.text })
            Assert.assertEquals("still typed", "ni", latestInputPanel.preedit.toString())
            fcitx.reset()
            awaitQuiet()
            fcitx.sendKey('-')
            val typed = eventsTillQuiet()
            Assert.assertTrue("- typed or offered: $typed",
                typed.any { it is HostEvent.Key || it is HostEvent.Commit } || !fcitx.isEmpty())
        } finally {
            fcitx.setCandidatePagingMode(0)
            fcitx.reset()
        }
    }

    /** A key missed is retyped in place; with marks offered no session takes the tap, and fcitx's own would drop the marks. */
    @Test
    fun aTapOnTheCodeMovesItsCursorAndOneNotTakenChangesNothing(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
        sendString("nhao")
        awaitQuiet()
        fcitx.moveCursor(1)
        sendString("i")
        Assert.assertEquals("ni hao", awaitPanelPreedit("ni hao"))
        awaitQuiet()
        val candidates = fcitx.getCandidates(0, 16).map { it.text }
        Assert.assertTrue("你好 among $candidates", "你好" in candidates)
        fcitx.reset()

        sendString("[")
        Assert.assertEquals("【", awaitPanelPreedit("【"))
        awaitQuiet()
        val marks = fcitx.getCandidates(0, 16).map { it.text }
        fcitx.moveCursor(0)
        val tapped = eventsTillQuiet()
        Assert.assertTrue("$tapped", tapped.none { it is HostEvent.Commit })
        Assert.assertEquals("the marks still on offer", marks, fcitx.getCandidates(0, 16).map { it.text })
        Assert.assertEquals("【", latestInputPanel.preedit.toString())
        fcitx.sendKey("BackSpace")
        Assert.assertEquals("", awaitPanelPreedit(""))
        fcitx.reset()
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

    // 五笔's aaog is 工业 alone
    @Test
    fun aFullCodeOfOneCandidateCommitsItself(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(WUBI))
        sendString("aaog")
        Assert.assertEquals("工业", receiveFirstCommitString()?.text)
        Assert.assertEquals("", awaitPanelPreedit(""))
        fcitx.reset()
    }

    // fcitx's zh_CN map: . has 。 alone, [ has 【 「 『 ❲ ［ [

    @Test
    fun aPunctuationKeyWithOneMarkTypesIt(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
        sendString(".")
        Assert.assertEquals("。", receiveFirstCommitString()?.text)
        awaitQuiet()
        Assert.assertEquals("nothing to choose from", 0, fcitx.getCandidates(0, 16).size)
        fcitx.reset()
    }

    /** The next key puts the first in and goes on, a tap puts in another, Backspace none. */
    @Test
    fun aPunctuationKeyWithSeveralMarksOffersThemItsFirstGoingIn(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
        sendString("[")
        Assert.assertEquals("【", awaitPanelPreedit("【"))
        awaitQuiet()
        Assert.assertEquals(listOf("【", "「", "『", "❲", "［", "["), fcitx.getCandidates(0, 16).map { it.text })
        sendString("1")
        Assert.assertEquals("【", receiveFirstCommitString()?.text)

        sendString("[")
        awaitQuiet()
        Assert.assertTrue(fcitx.select(1, "「"))
        Assert.assertEquals("「", receiveFirstCommitString()?.text)

        sendString("[")
        awaitQuiet()
        fcitx.sendKey("BackSpace")
        Assert.assertEquals("", awaitPanelPreedit(""))
        Assert.assertTrue("the key taken back", fcitx.isEmpty())

        // Space and Return pick the first and type nothing more
        for (key in listOf("space", "Return")) {
            sendString("[")
            awaitQuiet()
            fcitx.sendKey(key)
            Assert.assertEquals(key, "【", receiveFirstCommitString()?.text)
            val after = eventsTillQuiet()
            Assert.assertTrue("$key: $after", after.none { it is HostEvent.Key || it is HostEvent.Commit })
            Assert.assertTrue(key, fcitx.isEmpty())
        }
        fcitx.reset()
    }

    /** [entries] as the user's zh_CN table and reloaded; null removes it (built-in table). No app path saves one any more. */
    private suspend fun putTable(entries: List<PunctuationMapEntry>?) {
        val file = File(fcitxData(), "punctuation/punc.mb.zh_CN")
        if (entries == null) {
            file.delete()
        } else {
            file.parentFile!!.mkdirs()
            file.writeBytes(PunctuationTable.write(entries.map { PunctuationTable.Entry(it.key, it.mapping, it.altMapping) }))
        }
        fcitx.reloadConfig()
        PunctuationManager.forget()
    }

    /** The halves come in turn, fcitx's default (TypePairedPunctuationsTogether=False). */
    @Test
    fun aPairAsAKeysFirstCardIsOneChoice(): Unit = runBlocking {
        val shipped = PunctuationManager.load(fcitx, "zh_CN")
        val saved = File(fcitxData(), "punctuation/punc.mb.zh_CN")
        val hadSaved = saved.exists()
        try {
            putTable(shipped.filter { it.key != "\"" } + PunctuationMapEntry("\"", "“", "”") + PunctuationMapEntry("\"", "＂", ""))
            fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
            sendString("\"")
            Assert.assertEquals("“", awaitPanelPreedit("“"))
            awaitQuiet()
            Assert.assertEquals(listOf("“", "＂"), fcitx.getCandidates(0, 16).map { it.text })
            Assert.assertTrue(fcitx.select(0, "“"))
            Assert.assertEquals("“", receiveFirstCommitString()?.text)

            sendString("\"")
            Assert.assertEquals("the other half next", "”", awaitPanelPreedit("”"))
            awaitQuiet()
            Assert.assertEquals(listOf("”", "＂"), fcitx.getCandidates(0, 16).map { it.text })
            Assert.assertTrue(fcitx.select(1, "＂"))
            Assert.assertEquals("＂", receiveFirstCommitString()?.text)
        } finally {
            fcitx.reset()
            if (hadSaved) putTable(shipped) else putTable(null)
        }
    }

    /** After another card is picked or Backspace, the key opens the pair again. */
    @Test
    fun aPairsHalfNotPutInLeavesThePairAsItWas(): Unit = runBlocking {
        val shipped = PunctuationManager.load(fcitx, "zh_CN")
        val saved = File(fcitxData(), "punctuation/punc.mb.zh_CN")
        val hadSaved = saved.exists()
        try {
            putTable(shipped.filter { it.key != "\"" } + PunctuationMapEntry("\"", "“", "”") + PunctuationMapEntry("\"", "＂", ""))
            fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
            sendString("\"")
            Assert.assertEquals("“", awaitPanelPreedit("“"))
            awaitQuiet()
            Assert.assertTrue(fcitx.select(1, "＂"))
            Assert.assertEquals("＂", receiveFirstCommitString()?.text)
            // so the “ below is the next press's, not a leftover
            Assert.assertEquals("", awaitPanelPreedit(""))

            sendString("\"")
            Assert.assertEquals("another card picked", "“", awaitPanelPreedit("“"))
            awaitQuiet()
            fcitx.sendKey("BackSpace")
            Assert.assertEquals("", awaitPanelPreedit(""))

            sendString("\"")
            Assert.assertEquals("taken back", "“", awaitPanelPreedit("“"))
            awaitQuiet()
            sendString("\"")
            Assert.assertEquals("“", receiveFirstCommitString()?.text)
            Assert.assertEquals("”", awaitPanelPreedit("”"))
        } finally {
            fcitx.reset()
            if (hadSaved) putTable(shipped) else putTable(null)
        }
    }

    /** With “ in, the key offers ” after another card is picked or Backspace, till ” goes in. */
    @Test
    fun aClosingHalfNotPutInLeavesThePairOpen(): Unit = runBlocking {
        val shipped = PunctuationManager.load(fcitx, "zh_CN")
        val saved = File(fcitxData(), "punctuation/punc.mb.zh_CN")
        val hadSaved = saved.exists()
        try {
            putTable(shipped.filter { it.key != "\"" } + PunctuationMapEntry("\"", "“", "”") + PunctuationMapEntry("\"", "＂", ""))
            fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
            sendString("\"")
            Assert.assertEquals("“", awaitPanelPreedit("“"))
            awaitQuiet()
            Assert.assertTrue(fcitx.select(0, "“"))
            Assert.assertEquals("“", receiveFirstCommitString()?.text)

            sendString("\"")
            Assert.assertEquals("”", awaitPanelPreedit("”"))
            awaitQuiet()
            Assert.assertTrue(fcitx.select(1, "＂"))
            Assert.assertEquals("＂", receiveFirstCommitString()?.text)
            // so the ” below is the next press's, not a leftover
            Assert.assertEquals("", awaitPanelPreedit(""))

            sendString("\"")
            Assert.assertEquals("another card picked", "”", awaitPanelPreedit("”"))
            awaitQuiet()
            fcitx.sendKey("BackSpace")
            Assert.assertEquals("", awaitPanelPreedit(""))

            sendString("\"")
            Assert.assertEquals("taken back", "”", awaitPanelPreedit("”"))
            awaitQuiet()
            sendString("\"")
            Assert.assertEquals("”", receiveFirstCommitString()?.text)
            Assert.assertEquals("“", awaitPanelPreedit("“"))
        } finally {
            fcitx.reset()
            if (hadSaved) putTable(shipped) else putTable(null)
        }
    }

    @Test
    fun switchingInputMethodPutsInTheMarkOnOffer(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN))
        fcitx.activateIme(InputMethodCatalog.PINYIN)
        sendString("[")
        Assert.assertEquals("【", awaitPanelPreedit("【"))
        awaitQuiet()
        fcitx.activateIme(InputMethodCatalog.SHUANGPIN)
        Assert.assertEquals("【", receiveFirstCommitString()?.text)
        Assert.assertEquals("", awaitPanelPreedit(""))
        fcitx.reset()
    }

    @Test
    fun aShortcutPutsInTheMarkOnOfferBeforeTheAppGetsIt(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
        sendString("[")
        Assert.assertEquals("【", awaitPanelPreedit("【"))
        awaitQuiet()
        fcitx.sendKey("v", KeyState.Ctrl.state)
        Assert.assertEquals("【", receiveFirstCommitString()?.text)
        // then the key: what the app pastes goes in after the mark
        val key = receiveFirst<HostEvent.Key>()
        Assert.assertEquals('v'.code, key?.unicode)
        Assert.assertEquals(true, key?.keyStates?.ctrl)
        Assert.assertEquals("", awaitPanelPreedit(""))
        fcitx.reset()
    }

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

    @Test
    fun aCharacterKeyPastLatin1ReachesFcitxAsItself(): Unit = runBlocking {
        fcitx.setEnabledIme(arrayOf(InputMethodCatalog.PINYIN))
        // neither of its two bytes is 0: read as a C string, it had no end
        fcitx.sendKey('中')
        Assert.assertEquals('中'.code, receiveFirst<HostEvent.Key>()?.unicode)
        fcitx.reset()
    }

}