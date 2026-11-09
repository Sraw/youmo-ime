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
 * never a letter, and no place across the key yet; corners 1 to 0 from the top row on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class GroupedKeyboard18Test {

    private val context = RuntimeEnvironment.getApplication()

    private val layout = GroupedKeyboard.layout(NineKeyLetters.G18) { letter ->
        when (letter) {
            "l" -> listOf(")", "L")
            ";" -> listOf(":")
            else -> emptyList()
        }
    }

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

    @Test
    fun aTapIsTheCodeAloneWhereverItFalls() {
        val we = press("WE")
        // the 18 keys' layout has none of its taps lean (lean="off", 060-render-v2 §15)
        val k18 = SceneLayouts.of(context, "k18")!!
        val leaning = SceneGroups.leaning(k18.layout, k18.fragments)
        assertEquals(emptySet<String>(), leaning)
        assertEquals(we, GroupedKeyboard.tapped(we, 0.1f, NineKeyLetters.G18, leaning))
        assertEquals(we, GroupedKeyboard.tapped(we, 0.9f, NineKeyLetters.G18, leaning))
        // a layout's own lean on them: the engine has no places for G18's codes yet (Keyboard.TAPS, P3)
        assertEquals(we, GroupedKeyboard.tapped(we, 0.1f, NineKeyLetters.G18, setOf("we")))
    }

    @Test
    fun theLongPressOfLSemicolonOffersBoth() {
        val l = key("L;").popup!!.filterIsInstance<KeyDef.Popup.Keyboard.Explicit>().single()
        assertEquals(listOf("L", ":", "-", "l", ";", ")"), l.items.toList())
        assertEquals(2, l.inRow)
    }

    // pinyin's 18 keys have no ;: L alone on the key, its long press L 0 l and the symbols, no ; or :
    @Test
    @Uat("SP-28")
    fun pinyinsEighteenKeysShowLWithoutTheSemicolon() {
        val pinyin = GroupedKeyboard.layout(NineKeyLetters.G18, semicolon = false) { letter ->
            when (letter) {
                "l" -> listOf(")", "L")
                ";" -> listOf(":")
                else -> emptyList()
            }
        }
        val faces = pinyin.take(3).flatten().mapNotNull { (it.appearance as? KeyDef.Appearance.AltText)?.displayText }
        assertEquals(listOf("A", "SD", "FG", "H", "JK", "L"), faces.subList(7, 13))
        val l = pinyin.flatten().single { (it.appearance as? KeyDef.Appearance.AltText)?.displayText == "L" }
        assertEquals(listOf("L", "-", "l", ")"), l.popup!!.filterIsInstance<KeyDef.Popup.Keyboard.Explicit>().single().items.toList())
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
