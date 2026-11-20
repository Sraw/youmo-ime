/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
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

/** 双拼's 18 keys: a tap sends the group's code, never a letter, and no place across the key (pinyin's does). */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class GroupedKeyboard18Test {

    private val context = RuntimeEnvironment.getApplication()

    // pinyin's 18 keys send where across the key the tap fell (Keyboard.pinyin18Tap), 双拼's the code alone
    @Test
    @Uat("PY-152")
    fun pinyinsTapGoesWithWhereItFellAndShuangpinsIsTheCodeAlone() {
        val we = GroupedKeyboard.code(NineKeyLetters.G18.key('w'))
        val k18 = SceneLayouts.of(context, "k18")!!
        val leaning = SceneGroups.leaning(k18.layout, k18.fragments)
        assertTrue("we" in leaning && "bn" in leaning && "q" !in leaning)
        assertEquals(we, GroupedKeyboard.tapped(we, 0.1f, NineKeyLetters.G18, leaning))
        assertEquals(we, GroupedKeyboard.tapped(we, 0.9f, NineKeyLetters.G18, leaning))
        val pinyinWe = GroupedKeyboard.code(NineKeyLetters.G18.key('w'), Keyboard::pinyin18Key)
        val code = NineKeyLetters.G18.key('w')
        for (at in listOf(0f, 0.1f, 0.9f, 1f)) {
            val sent = GroupedKeyboard.tapped(pinyinWe, at, NineKeyLetters.G18, leaning)
            assertEquals(KeyAction.SymAction(KeySym(UNICODE + Keyboard.pinyin18Tap(code, at)), KeyStates.Virtual), sent)
            assertEquals(code, Keyboard.tappedCode((sent as KeyAction.SymAction).sym.sym - UNICODE))
        }
        // no place (TalkBack's click) or a group not leaning: the code alone
        assertEquals(pinyinWe, GroupedKeyboard.tapped(pinyinWe, Float.NaN, NineKeyLetters.G18, leaning))
        assertEquals(pinyinWe, GroupedKeyboard.tapped(pinyinWe, 0.1f, NineKeyLetters.G18, leaning - "we"))
        // a one-letter key has its place too, for a slip from it onto a key of two; where nothing leans, the code alone
        for (shown in listOf("Q", "L")) {
            val one = NineKeyLetters.G18.key(shown.lowercase().single())
            val key = GroupedKeyboard.code(one, Keyboard::pinyin18Key)
            assertEquals(KeyAction.SymAction(KeySym(UNICODE + Keyboard.pinyin18Tap(one, 0.9f)), KeyStates.Virtual), GroupedKeyboard.tapped(key, 0.9f, NineKeyLetters.G18, leaning))
            assertEquals(key, GroupedKeyboard.tapped(key, 0.9f, NineKeyLetters.G18, emptySet()))
        }
        assertTrue("l;" in leaning)
    }

    // a layout stacking a key's two letters (W over E): a tap's place goes by down, the top letter first; in one row, across
    @Test
    fun aStackedKeysTapGoesByWhereDownItFell() {
        val g18 = NineKeyLetters.G18.tag().split(',')
        val stacked = NineKeyLetters.custom(g18.map { if (it == "we") "w e" else it })
        assertEquals(NineKeyLetters.G18, stacked)
        val pinyinWe = GroupedKeyboard.code(NineKeyLetters.G18.key('w'), Keyboard::pinyin18Key)
        val we = NineKeyLetters.G18.key('w')
        fun typed(at: Float, down: Float, letters: NineKeyLetters) =
            GroupedKeyboard.tapped(pinyinWe, at, letters, setOf("we"), down).let { Keyboard.typedLetter((it as KeyAction.SymAction).sym.sym - UNICODE) }
        assertEquals('w', typed(0.9f, 0.1f, stacked))
        assertEquals('e', typed(0.1f, 0.9f, stacked))
        assertEquals(KeyAction.SymAction(KeySym(UNICODE + Keyboard.pinyin18Tap(we, 0.9f)), KeyStates.Virtual), GroupedKeyboard.tapped(pinyinWe, 0.1f, stacked, setOf("we"), 0.9f))
        assertEquals('e', typed(0.9f, 0.1f, NineKeyLetters.G18))
        assertEquals(pinyinWe, GroupedKeyboard.tapped(pinyinWe, 0.1f, stacked, setOf("we"), Float.NaN))
        val g14 = NineKeyLetters.custom(NineKeyLetters.G14.tag().split(',').map { if (it == "qw") "q w" else it })
        val qw = NineKeyLetters.G14.key('q')
        val sent = GroupedKeyboard.tapped(GroupedKeyboard.code(qw), 0.1f, g14, setOf("qw"), 0.9f) as KeyAction.SymAction
        assertEquals(Keyboard.tapKey(qw, 0.9f), sent.sym.sym - UNICODE)
        assertEquals('w', Keyboard.typedLetter(sent.sym.sym - UNICODE))
    }

    // pinyin's 18 keys have no ;: L alone on the key; 双拼's L;
    @Test
    @Uat("SP-28")
    fun pinyinsEighteenKeysShowLWithoutTheSemicolon() {
        fun faces(k: GroupedKeyboard) = k.laidOut().shownKeys().filter { it.id?.matches(Regex("r[0-9]c[0-9]")) == true }.map { it.label }
        val pinyin = faces(GroupedKeyboard(context, TestThemes.PixelDark, NineKeyLetters.G18, Keyboard::pinyin18Key, semicolon = false))
        assertTrue(pinyin.toString(), "L" in pinyin && "L;" !in pinyin)
        assertTrue("L;" in faces(GroupedKeyboard(context, TestThemes.PixelDark, NineKeyLetters.G18)))
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
