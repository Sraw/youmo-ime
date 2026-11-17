/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.scene.SceneGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 双拼's 18 keys: Q WE RT Y U IO P, A SD FG H JK L;, then the separator, Z XC V BN M and
 * backspace; the 14 keys' bottom row. A tap sends the group's code ([NineKeyLetters.G18]),
 * never a letter, and no place across the key (pinyin's 18 keys' does); corners 1 to 0 from the top row on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class GroupedKeyboard18Test {

    private val context = RuntimeEnvironment.getApplication()

    private val layout = GroupedKeyboard.layout(NineKeyLetters.G18)

    private fun key(face: String) = layout.flatten().single { (it.appearance as? KeyDef.Appearance.AltText)?.displayText == face }

    private fun press(face: String) = key(face).behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action

    private fun swipe(face: String) = key(face).behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single().action

    @Test
    fun threeRowsOfSevenSixAndFiveFullWidth() {
        val faces = layout.take(3).map { row -> row.map { (it.appearance as? KeyDef.Appearance.Text)?.displayText } }
        assertEquals(listOf("Q", "WE", "RT", "Y", "U", "IO", "P"), faces[0])
        assertEquals(listOf("A", "SD", "FG", "H", "JK", "L;"), faces[1])
        assertEquals(listOf("分词", "Z", "XC", "V", "BN", "M", null), faces[2])
        val widths = layout.take(3).map { row -> row.map { it.appearance.percentWidth } }
        assertEquals(listOf(0.15f, 0.14f, 0.14f, 0.14f, 0.14f, 0.14f, 0.15f), widths[2])
        widths.forEach { assertEquals(1f, it.sum(), 1e-4f) }
        assertTrue(layout[2].last() is BackspaceKey)
        assertEquals(KeyAction.HostKeyAction("'"), (layout[2][0].behaviors.single() as KeyDef.Behavior.Press).action)
    }

    @Test
    fun cornersAreTheDigitsFromTheTopRowThenMarks() {
        val corners = layout.take(3).map { row -> row.mapNotNull { (it.appearance as? KeyDef.Appearance.AltText)?.altText } }
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7"), corners[0])
        assertEquals(listOf("8", "9", "0", "(", ")", "-"), corners[1])
        assertEquals(listOf("~", "!", "@", "?", ":"), corners[2])
        assertEquals(18, corners.flatten().distinct().size)
    }

    @Test
    fun aTapSendsTheGroupsCodeAndASwipeItsCorner() {
        assertEquals(KeyAction.SymAction(KeySym(UNICODE + 0xF8C0 + ('w' - 'a')), KeyStates.Virtual), press("WE"))
        NineKeyLetters.G18.codes.toList().zip(layout.take(3).flatten().filter { it.appearance is KeyDef.Appearance.AltText }).forEach { (code, def) ->
            assertEquals(GroupedKeyboard.code(code), def.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action)
        }
        // L; is 微软's ing too: one code for both
        assertEquals(GroupedKeyboard.code(NineKeyLetters.G18.key(';')), press("L;"))
        assertEquals(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_KP_0 + 8), KeyStates.Virtual), swipe("A"))
        assertEquals(KeyAction.HostKeyAction(":"), swipe("M"))
    }

    // 061-neighbour-18key: the 18 keys' layout has its two-letter groups lean (060-render-v2 §15); pinyin's 18 keys
    // send a tap with where across the key it fell (Keyboard.pinyin18Tap), 双拼's the code alone wherever it falls
    @Test
    @Uat("PY-152")
    fun pinyinsTapGoesWithWhereItFellAndShuangpinsIsTheCodeAlone() {
        val we = press("WE")
        val k18 = SceneLayouts.of(context, "k18")!!
        val leaning = SceneGroups.leaning(k18.layout, k18.fragments)
        assertTrue("we" in leaning && "bn" in leaning && "q" !in leaning)
        assertEquals(we, GroupedKeyboard.tapped(we, 0.1f, NineKeyLetters.G18, leaning))
        assertEquals(we, GroupedKeyboard.tapped(we, 0.9f, NineKeyLetters.G18, leaning))
        val pinyin = GroupedKeyboard.layout(NineKeyLetters.G18, Keyboard::pinyin18Key, semicolon = false)
        val pinyinWe = pinyin.flatten().single { (it.appearance as? KeyDef.Appearance.AltText)?.displayText == "WE" }
            .behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action
        val code = NineKeyLetters.G18.key('w')
        for (at in listOf(0f, 0.1f, 0.9f, 1f)) {
            val sent = GroupedKeyboard.tapped(pinyinWe, at, NineKeyLetters.G18, leaning)
            assertEquals(KeyAction.SymAction(KeySym(UNICODE + Keyboard.pinyin18Tap(code, at)), KeyStates.Virtual), sent)
            assertEquals(code, Keyboard.tappedCode((sent as KeyAction.SymAction).sym.sym - UNICODE))
        }
        // no place (TalkBack's click), a group not leaning (a layout's lean="off"): the code alone
        assertEquals(pinyinWe, GroupedKeyboard.tapped(pinyinWe, Float.NaN, NineKeyLetters.G18, leaning))
        assertEquals(pinyinWe, GroupedKeyboard.tapped(pinyinWe, 0.1f, NineKeyLetters.G18, leaning - "we"))
        // a key of one letter, and pinyin's L though its group is L; (微软 双拼's ing): its place too, for a slip from it
        // onto a key of two (T9Segmenter weighs nothing of its own); on a keyboard where nothing leans, the code alone
        for (shown in listOf("Q", "L")) {
            val key = pinyin.flatten().single { (it.appearance as? KeyDef.Appearance.AltText)?.displayText == shown }
                .behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action
            val one = NineKeyLetters.G18.key(shown.lowercase().single())
            assertEquals(KeyAction.SymAction(KeySym(UNICODE + Keyboard.pinyin18Tap(one, 0.9f)), KeyStates.Virtual), GroupedKeyboard.tapped(key, 0.9f, NineKeyLetters.G18, leaning))
            assertEquals(key, GroupedKeyboard.tapped(key, 0.9f, NineKeyLetters.G18, emptySet()))
        }
        assertTrue("l;" in leaning)
    }

    // review L4: a layout of the built-in groups stacking a key's two letters (W over E) has a tap's place along them go
    // by where down it fell: the top letter the first, as the engine and Keyboard.typedLetter read a built-in code's
    // place; in one row, across as before. The 14 keys' codes alike (Keyboard.tapKey)
    @Test
    fun aStackedKeysTapGoesByWhereDownItFell() {
        val g18 = NineKeyLetters.G18.tag().split(',')
        val stacked = NineKeyLetters.custom(g18.map { if (it == "we") "w e" else it })
        assertEquals(NineKeyLetters.G18, stacked)
        val pinyinWe = GroupedKeyboard.layout(NineKeyLetters.G18, Keyboard::pinyin18Key, semicolon = false).flatten()
            .single { (it.appearance as? KeyDef.Appearance.AltText)?.displayText == "WE" }
            .behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action
        val we = NineKeyLetters.G18.key('w')
        fun typed(at: Float, down: Float, letters: NineKeyLetters) =
            GroupedKeyboard.tapped(pinyinWe, at, letters, setOf("we"), down).let { Keyboard.typedLetter((it as KeyAction.SymAction).sym.sym - UNICODE) }
        // the top's right, the bottom's left
        assertEquals('w', typed(0.9f, 0.1f, stacked))
        assertEquals('e', typed(0.1f, 0.9f, stacked))
        assertEquals(KeyAction.SymAction(KeySym(UNICODE + Keyboard.pinyin18Tap(we, 0.9f)), KeyStates.Virtual), GroupedKeyboard.tapped(pinyinWe, 0.1f, stacked, setOf("we"), 0.9f))
        // in one row: across
        assertEquals('e', typed(0.9f, 0.1f, NineKeyLetters.G18))
        // stacked with no place down (a click no touch made): the code alone
        assertEquals(pinyinWe, GroupedKeyboard.tapped(pinyinWe, 0.1f, stacked, setOf("we"), Float.NaN))
        // the 14 keys' QW stacked
        val g14 = NineKeyLetters.custom(NineKeyLetters.G14.tag().split(',').map { if (it == "qw") "q w" else it })
        val qw = NineKeyLetters.G14.key('q')
        val sent = GroupedKeyboard.tapped(GroupedKeyboard.code(qw), 0.1f, g14, setOf("qw"), 0.9f) as KeyAction.SymAction
        assertEquals(Keyboard.tapKey(qw, 0.9f), sent.sym.sym - UNICODE)
        assertEquals('w', Keyboard.typedLetter(sent.sym.sym - UNICODE))
    }

    // pinyin's 18 keys have no ;: L alone on the key (its long press the resolver's, g18/l: KeyDefaults)
    @Test
    @Uat("SP-28")
    fun pinyinsEighteenKeysShowLWithoutTheSemicolon() {
        val pinyin = GroupedKeyboard.layout(NineKeyLetters.G18, semicolon = false)
        val faces = pinyin.take(3).flatten().mapNotNull { (it.appearance as? KeyDef.Appearance.AltText)?.displayText }
        assertEquals(listOf("A", "SD", "FG", "H", "JK", "L"), faces.subList(7, 13))
        val l = pinyin.flatten().single { (it.appearance as? KeyDef.Appearance.AltText)?.displayText == "L" }
        // the code is the group's all the same, as the session reads it
        assertEquals(key("L;").behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action, l.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action)
    }

    @Test
    @Uat("SP-21")
    fun theSpaceIsAtLeast15PercentOfTheRowInEveryField() {
        assertSpaceAtLeast15Percent(GroupedKeyboard(context, TestThemes.PixelDark, NineKeyLetters.G18))
    }

    @Test
    fun theKeyboardHasItsBottomRowAndTalkBackReadsEachGroup() {
        val keyboard = GroupedKeyboard(context, TestThemes.PixelDark, NineKeyLetters.G18).laidOut()
        listOf(R.id.button_lang, R.id.button_space, R.id.button_return, R.id.button_comma, R.id.button_backspace).forEach {
            assertTrue(keyboard.resources.getResourceEntryName(it), keyboard.shownKeyWithId(it) != null)
        }
        val described = keyboard.shownKeys().filter { it.id?.matches(Regex("r[0-9]c[0-9]")) == true }.associate { it.label to it.description }
        assertEquals(18, described.size)
        assertEquals(context.getString(R.string.a11y_key_grouped, "W E", "2"), described["WE"])
        assertEquals(context.getString(R.string.a11y_key_grouped, "Y", "4"), described["Y"])
    }
}
