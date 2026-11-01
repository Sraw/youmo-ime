/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.learn.control.LearningMenu
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.g14
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.host.KeySyms
import org.fcitx.fcitx5.android.input.keyboard.PendingInputPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's half of uat/ cases whose keyboard half is the app's (Robolectric, the keys and
 * menus on a fake engine): what the 14 keys' swipes, long presses and taps give with the app's
 * data, and the long-press menu's rows and acts for a candidate.
 */
class KeyboardHalvesUatTest {

    private fun EngineReplay.menuRows(text: String): List<LearningMenu.Row> {
        val menu = learn(LearningOp.Menu(candidates.indexOf(text), text))
        assertTrue("menu for $text: $menu", menu is LearningResult.Menu)
        return LearningMenu.rows(menu as LearningResult.Menu)
    }

    private fun EngineReplay.act(row: LearningMenu.Row, text: String): LearningResult =
        learn(LearningMenu.op(row, candidates.indexOf(text), text, false)!!)

    // ------------------------------------------------------------------ 06 14 keys

    // a swipe sends the corner: a digit as a keypad's (GroupedKeyboard), a mark as its key
    @Test
    @Uat("PY-88")
    fun aCornersDigitTypesItselfOrPicksThatCandidateWhileTyping() = with(EngineReplay()) {
        key(KeySyms.KP_0 + 1)
        key(EngineReplay.symOf('@'.code))
        expectText("1@")
        focus("")
        type(g14("bn ui"))
        val shown = page
        assertTrue("$shown", shown.size > 1)
        key(KeySyms.KP_0 + 2)
        expectText(shown[1])
    }

    // a letter picked from the long press while typing: what is typed resolved first (commitOverEngine), then the letter
    @Test
    @Uat("PY-89")
    fun aLetterOfTheLongPressWhileTypingFollowsTheFirstCandidate() = with(EngineReplay()) {
        type(g14("bn ui"))
        val first = candidates.first()
        assertEquals(PendingInputPolicy.Resolution.SelectFirstCandidate, commitOverEngine("q"))
        expectText(first + "q")
    }

    // .com and @ are commits over the engine (commitOverEngine): in an address field what is typed goes in as typed first
    @Test
    @Uat("PY-93")
    fun inAnAddressFieldTheKeysGoInAsLettersBeforeDotComAndAt() {
        for ((field, tail) in listOf(EngineReplay.Field.URL to ".com", EngineReplay.Field.EMAIL to "@")) with(EngineReplay()) {
            focus("", field)
            type(g14("bn ui"))
            // the service's own Return, not the screen's
            assertEquals(PendingInputPolicy.Resolution.CommitAsTyped, commitOverEngine(tail))
            assertTrue(text, text.endsWith(tail) && text.dropLast(tail.length).let { it.isNotEmpty() && it.all { c -> c in 'a'..'z' } })
            assertTrue(text, EngineReplay.noPrivateUse(text))
        }
    }

    private val nihao = NineKeyLetters.G14.typings("nihao").single()

    private fun EngineReplay.tap(keys: String, at: List<Float>) {
        keys.indices.forEach { key(EngineReplay.symOf(Keyboard.tapKey(keys[it], at[it]))) }
        pause()
    }

    @Test
    @Uat("PY-100")
    fun tappedOnTheLettersOfNihaoTheStripStartsWithNi() = with(EngineReplay()) {
        tap(nihao, listOf(0.75f, 0.75f, 0.75f, 0.25f, 0.25f))
        assertEquals("你好", candidates.first())
        assertEquals("ni", panel.syllables.firstOrNull())
    }

    @Test
    @Uat("PY-101")
    fun tappedOnTheLettersOfBuhaoTheStripStartsWithBu() = with(EngineReplay()) {
        tap(nihao, listOf(0.25f, 0.25f, 0.75f, 0.25f, 0.25f))
        assertEquals("不好", candidates.first())
        assertEquals("bu", panel.syllables.firstOrNull())
    }

    // the strip's syllable picked (GroupedKeyboardUatTest sends its key): the code starts with it, ⌫ gives it back
    @Test
    @Uat("PY-86")
    fun aSyllablePickedLeadsTheCodeAndBackspaceGivesItBack() = with(EngineReplay()) {
        type(g14("bn ui"))
        val before = preedit
        val offered = panel.syllables
        assertTrue("$offered", "ni" in offered && "bu" in offered)
        syllable("ni")
        assertTrue(preedit, preedit.startsWith("ni"))
        assertTrue("moved on: ${panel.syllables}", panel.syllables != offered)
        assertTrue("$page", page.take(5).none { it.startsWith("不") || it.startsWith("步") })
        backspace()
        expectPreedit(before)
        assertTrue("${panel.syllables}", "ni" in panel.syllables)
        focus("")
        // zhuang's start: more syllables than the strip shows at once (its 60%)
        type(g14("zx gh ui as bn gh"))
        assertTrue("${panel.syllables}", panel.syllables.size >= 6 && panel.syllables.first() == "zhuang")
    }

    // ------------------------------------------------------------------ 07 双拼's grouped keys

    private fun codes(keys: String, letters: NineKeyLetters) = keys.map(letters::key).joinToString("")

    // the keyboard half is GroupedKeyboardUatTest's: what the engine makes of the keys in an address field
    @Test
    @Uat("SP-21")
    fun inAnAddressFieldShuangpinsGroupedKeysGoInAsLettersOrTheWordTyped() {
        for ((field, tail) in listOf(EngineReplay.Field.URL to ".com", EngineReplay.Field.EMAIL to "@")) with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
            focus("", field)
            type(codes("bi", NineKeyLetters.G18))
            assertEquals(PendingInputPolicy.Resolution.CommitAsTyped, commitOverEngine(tail))
            assertTrue(text, text.endsWith(tail) && text.dropLast(tail.length).let { it.isNotEmpty() && it.all { c -> c in 'a'..'z' } })
            assertTrue(text, EngineReplay.noPrivateUse(text))
        }
        with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
            focus("", EngineReplay.Field.URL)
            // 6 4: ni's keys
            type(codes("mg", NineKeyLetters.NINE_SP))
            assertTrue("${panel.syllables}", "ni" in panel.syllables)
            space()
            val word = text
            assertTrue(word, word.isNotEmpty() && EngineReplay.noPrivateUse(word) && word.none { it in 'a'..'z' })
            commit(".com")
            expectText("$word.com")
        }
    }

    // 九键双拼 微软 on the 123 page: a syllable of the strip taken (GroupedKeyboardUatTest sends it) goes into the code
    @Test
    @Uat("SP-24")
    fun aSyllableTakenOnTheNumbersPageGoesIntoTheCode() = with(EngineReplay(settings(EngineSettings(shuangpin = "MS")), im = InputMethodCatalog.SHUANGPIN)) {
        type(codes("m", NineKeyLetters.NINE_SP))
        val first = panel.syllables.firstOrNull()
        assertTrue("${panel.syllables}", first != null)
        syllable(first!!)
        assertTrue("$first in $preedit", first in preedit.replace("'", "").replace(" ", ""))
    }

    // ------------------------------------------------------------------ 06 the candidate menu

    @Test
    @Uat("PY-33")
    fun theMenuListsOnlyWhatApplies() = with(EngineReplay(engines = UatEngines.fresh())) {
        type("shi")
        val word = candidates.drop(3).first()
        val rows = menuRows(word)
        assertTrue("$rows", rows.containsAll(listOf(LearningMenu.Row.BLOCK, LearningMenu.Row.PIN, LearningMenu.Row.WHY)))
        assertTrue("$rows", rows.none { it in listOf(LearningMenu.Row.FORGET, LearningMenu.Row.DELETE, LearningMenu.Row.UNPIN) })
        pick(word)
        focus("")
        type("shi")
        val again = menuRows(word)
        assertTrue("$again", LearningMenu.Row.FORGET in again)
    }

    // ------------------------------------------------------------------ 09 handwriting

    @Test
    @Uat("HW-19")
    fun handwritingsMenuHasNoPinItsBlockUndoneAndForgettingReordersAtOnce() = with(EngineReplay(engines = UatEngines.fresh(), im = InputMethodCatalog.HANDWRITING)) {
        val ren = arrayOf("人", "入", "八")
        write(*ren)
        val order = candidates.take(3)
        pick("入")
        focus("")
        write(*ren)
        val rows = menuRows("入")
        assertTrue("$rows", LearningMenu.Row.FORGET in rows && LearningMenu.Row.BLOCK in rows)
        assertFalse("$rows", LearningMenu.Row.PIN in rows)
        val blocked = act(LearningMenu.Row.BLOCK, "入")
        assertTrue("$blocked", blocked is LearningResult.Done && blocked.undo != LearningResult.NO_UNDO)
        expectNotAmong("入")
        // not written again: the candidates as they were
        learn(LearningOp.Undo((blocked as LearningResult.Done).undo))
        // the list shown anew, as KotlinHost.learning sends AGAIN after an undo (EngineBridge.shownAnew)
        key(EngineReplay.symOf(Keyboard.AGAIN))
        expectAmong("入")
        act(LearningMenu.Row.FORGET, "入")
        assertEquals(order, candidates.take(3))
        focus("")
        write(*ren)
        assertEquals(order, candidates.take(3))
    }
}
