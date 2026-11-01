/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.g14
import org.fcitx.fcitx5.android.engine.pinyin.g14Code
import org.fcitx.fcitx5.android.host.KeySyms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * uat/06-pinyin.md's 14-key cases whose 预期 is the engine's (design k14-tap-position §4, review
 * S3): the keys tapped as GroupedKeyboard sends them, each a code with where across its key it fell
 * ([Keyboard.tapKey]). The device's cases test the keyboard's side, the place a touch gives.
 */
class Keys14UatTest {

    private val nihao = NineKeyLetters.G14.typings("nihao").single() // BN UI GH AS OP
    private val centre = List(5) { 0.5f }
    private val onNihao = listOf(0.75f, 0.75f, 0.75f, 0.25f, 0.25f)

    // each key tapped at its place, its keysym the character's, as the service sends the screen's keys
    private fun HostReplay.tap(keys: String, at: List<Float>) {
        keys.indices.forEach { key(EngineReplay.symOf(Keyboard.tapKey(keys[it], at[it]))) }
        pause()
    }

    // the 14 keys' codes the learning record holds, and whether it has their kind
    private fun learnedKeys(): Set<String> {
        val record = String(UatEngines.learningRecord(), Charsets.UTF_8)
        assertTrue("no ${Kinds.keys(NineKeyLetters.G14.tag())} in the record", Kinds.keys(NineKeyLetters.G14.tag()) in record)
        // a tap's code (its place) in the record would be learned as other keys than the bare ones
        assertTrue("a tap's code in the record", record.codePoints().noneMatch { it in Keyboard.TAPS })
        return Regex("[${Char(NineKeyLetters.G14_BLOCK.first)}-${Char(NineKeyLetters.G14_BLOCK.last)}]+").findAll(record).map { it.value }.toSet()
    }

    @Test
    @Uat("PY-85")
    fun atTheKeysCentresTheFirstIsTheModelsAndYourIsOnThePage() = with(HostReplay(engines = UatEngines.fresh())) {
        tap(nihao, centre)
        assertEquals("不好", candidates.first())
        assertTrue("$candidates", "你好" in candidates.take(5))
        pick("你好")
        assertEquals("你好", text)
    }

    @Test
    @Uat("PY-95")
    fun theBuiltInDatesUnderRqAndRiqi() = onOneDay { day -> with(EngineReplay()) {
        type(g14("er qw"))
        val rq = candidates.take(8)
        for (d in dates(day)) assertTrue("rq: $d not in $rq", d in rq)
        while (preedit.isNotEmpty()) backspace()
        type(g14("er ui qw ui"))
        val at = candidates.indexOf("日期")
        assertTrue("riqi: ${candidates.take(6)}", at >= 0 && dates(day).first() in candidates.drop(maxOf(at + 1, 2)).take(6))
        pick("日期")
        expectText("日期")
    } }

    @Test
    @Uat("PY-102")
    fun whereTheKeysWereTappedIsNotLearnedAndQuickInputGetsTheLetter() {
        val atCentre = with(HostReplay(engines = UatEngines.fresh())) {
            tap(nihao, centre)
            assertEquals("不好", candidates.first())
            pick("你好")
            assertEquals("你好", text)
            // the same keys again, on the letters: the word learned found again
            focus("")
            tap(nihao, onNihao)
            assertEquals("你好", candidates.first())
            backspace(nihao.length)
            learnedKeys()
        }
        val onLetters = with(HostReplay(engines = UatEngines.fresh())) {
            tap(nihao, onNihao)
            pick("你好")
            learnedKeys()
        }
        assertTrue("$onLetters", nihao in onLetters)
        assertEquals(atCentre, onLetters)
        with(HostReplay(engines = UatEngines.fresh())) {
            quick()
            tap(g14("bn"), listOf(0.75f))
            enter()
            assertEquals("n", text)
            assertTrue(text, EngineReplay.noPrivateUse(text))
        }
    }

    @Test
    @Uat("PY-90")
    fun theFourteenKeysLearnApartFromTheFullKeyboardAndYourWordsAreBoths() = with(EngineReplay()) {
        val full = typed("shi")
        val keys = typed(g14("as gh ui"))
        // before learning both keyboards put the same first
        assertEquals(full.first(), keys.first())
        val second = keys[1]
        repeat(2) {
            typed(g14("as gh ui"))
            pick(second)
            commit("。")
        }
        assertEquals(second, typed(g14("as gh ui")).first())
        repeat(2) {
            focus("")
            commit("。")
            type(g14(WXM))
            pick("汪")
            pick("小明")
            expectText("。汪小明")
        }
        // the full keyboard: its first glide after the 14 keys' commits is full pinyin's
        focus("")
        glide("nihao")
        assertTrue("$candidates", "你好" in candidates.take(5))
        assertNotEquals(second, typed("shi").first())
        typed("wxm")
        assertTrue("$page", "汪小明" in page)
        assertTrue("汪小明" in engines.userWords(UserWord.Kind.LEARNED).map { it.text })
    }

    @Test
    @Uat("PY-92")
    fun keysWithNoReadingCommitAsTheirLetters() = with(HostReplay(engines = UatEngines.fresh())) {
        keys(g14("ui"))
        enter()
        expectText("u")
        focus("")
        keys(g14("bn ui gh"))
        val shown = preedit
        ime(InputMethodCatalog.ENGLISH)
        // what the code row showed, in letters, never a key's code
        assertEquals(shown.filter { it != ' ' && it != '\'' }, text.filter { it != '\'' })
        assertTrue(text, text.isNotEmpty() && text.all { it in 'a'..'z' || it == '\'' })
        assertTrue(text, EngineReplay.noPrivateUse(text))
    }

    @Test
    @Uat("PY-97")
    fun syllablesTakenGoBackLastFirstAndATapInTheCodeOnlyMovesTheCursor() {
        val engines = UatEngines.fresh()
        fun ev(event: Int, arg: Int = 0) = engines.onEvent(InputMethodCatalog.PINYIN, event, arg)
        val typed = nihao.map { ev(EngineEvent.CHAR, it.code) }.last()
        var s = typed
        fun take(syllable: String) {
            assertTrue("$syllable not in ${s.syllables}", syllable in s.syllables)
            s = ev(EngineEvent.CHAR, Keyboard.syllableKey(s.syllables.indexOf(syllable), s.syllablesId).code)
        }
        take("ni")
        assertTrue(s.preedit, s.preedit.startsWith("ni"))
        take("hao")
        assertTrue(s.preedit, s.preedit.replace(" ", "'").startsWith("ni'hao"))
        s = ev(EngineEvent.BACKSPACE)
        assertTrue("${s.syllables}", "hao" in s.syllables && "ni" !in s.syllables)
        s = ev(EngineEvent.BACKSPACE)
        assertTrue("${s.syllables}", "ni" in s.syllables)
        assertEquals(typed.preedit, s.preedit)
        // a tap in the code: the cursor moved, the syllables as they were
        val before = s.syllables
        val bare = s.preedit.filter { it.isLetter() }.length
        s = ev(EngineEvent.CURSOR, s.preedit.length - 2)
        assertEquals(before, s.syllables)
        assertTrue("cursor ${s.cursor} of ${s.preedit}", s.cursor in 0 until s.preedit.length)
        // Backspace there deletes the key before the cursor
        s = ev(EngineEvent.BACKSPACE)
        assertEquals(bare - 1, s.preedit.filter { it.isLetter() }.length)
        assertTrue("cursor ${s.cursor} of ${s.preedit}", s.cursor in 0 until s.preedit.length)
        // and with a syllable taken, a Backspace inside the code after it leaves the syllable taken:
        // the key goes, not the syllable (the two above had undone theirs already)
        s = ev(EngineEvent.CURSOR, s.preedit.length)
        take("ni")
        val taken = s.preedit
        assertTrue(taken, taken.startsWith("ni"))
        s = ev(EngineEvent.CURSOR, taken.length - 1)
        s = ev(EngineEvent.BACKSPACE)
        assertTrue("「${s.preedit}」 after 「$taken」", s.preedit.startsWith("ni") && s.preedit.length < taken.length)
        // the preedit alone cannot tell: a syllable undone may still read ni first; the syllable list can
        assertTrue("${s.syllables}", "ni" !in s.syllables)
    }

    @Test
    @Uat("QU-09")
    fun unicodeOnTheFourteenKeysTakesTheCodeFromCornersAndHalves() = with(HostReplay(engines = UatEngines.fresh())) {
        unicode()
        // 4E2D: UI swiped down (its corner 4, a keypad's), E the left half of ER, 2 ER swiped, D the left half of DF
        key(KeySyms.KP_0 + 4)
        key(EngineReplay.symOf(Keyboard.tapKey(g14Code('e'), 0.25f)))
        key(KeySyms.KP_0 + 2)
        key(EngineReplay.symOf(Keyboard.tapKey(g14Code('d'), 0.25f)))
        pause()
        assertTrue("「$row」", EngineReplay.noPrivateUse(row))
        assertTrue("$page", "中" in page)
        pickShown(page.indexOf("中"))
        expectText("中")
    }

    // the separator sends ' (GroupedKeyboard): with nothing typed fcitx's Chinese punctuation takes it,
    // its second half the service's pairing (GroupedKeyboardUatTest.theSeparatorWithNothingTypedIsPairedQuotes)
    @Test
    @Uat("PY-87")
    fun theSeparatorWithNothingTypedIsAQuoteAndWhileTypingSplitsXian() = with(HostReplay(engines = UatEngines.fresh())) {
        keys("'")
        expectText("‘")
        focus("")
        keys(g14("zx ui ' as bn"))
        assertTrue(preedit, preedit.replace(" ", "'") == "xi'an")
        expectFirst("西安")
        space()
        expectText("西安")
    }

    private companion object {
        // wangxiaoming on the 14 keys
        const val WXM = "qw as bn gh zx ui as op m ui bn gh"
    }
}
