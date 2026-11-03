/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import android.view.ViewGroup
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 大千's 41 keys where Taiwan's keyboards print them and 注音九键's 11 groups and tone key, each
 * sending its own code; a long press's symbol or tone its code too, anything else as it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ZhuyinKeyboardTest {

    private fun sym(code: Int) = KeyAction.SymAction(KeySym(UNICODE + code), KeyStates.Virtual)

    private fun faces(rows: List<List<KeyDef>>) = rows.map { row -> row.mapNotNull { (it.appearance as? KeyDef.Appearance.Text)?.displayText } }

    private fun press(key: KeyDef) = key.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action

    @Test
    fun dachenHasEachSymbolAndToneWhereTaiwanPrintsIt() {
        val rows = ZhuyinKeyboard.fullLayout()
        assertEquals(listOf("ㄅ", "ㄉ", "ˇ", "ˋ", "ㄓ", "ˊ", "˙", "ㄚ", "ㄞ", "ㄢ", "ㄦ"), faces(rows)[0])
        assertEquals("ㄆㄊㄍㄐㄔㄗㄧㄛㄟㄣ", faces(rows)[1].joinToString(""))
        assertEquals("ㄇㄋㄎㄑㄕㄘㄨㄜㄠㄤ", faces(rows)[2].joinToString(""))
        assertEquals("ㄈㄌㄏㄒㄖㄙㄩㄝㄡㄥ", faces(rows)[3].filter { it.length == 1 && it[0] in ZhuyinKeys.SYMBOLS }.joinToString(""))
        val keys = rows.take(4).flatten().filter { (it.appearance as? KeyDef.Appearance.Text)?.displayText?.singleOrNull()?.let { c -> c in ZhuyinKeys.SYMBOLS || c in ZhuyinKeys.MARKS } == true }
        assertEquals(41, keys.size)
        assertEquals(sym(Keyboard.zhuyinKey('ㄅ')), press(keys[0]))
        assertEquals(sym(Keyboard.zhuyinTone(3)), press(keys[2]))
    }

    @Test
    fun nineKeysAreTheGroupsThreeARowTheTonesOnTheLeft() {
        val rows = ZhuyinKeyboard.nineLayout()
        val groups = rows.take(4).flatten().filter { (it.appearance as? KeyDef.Appearance.Text)?.displayText?.all { c -> c in ZhuyinKeys.SYMBOLS } == true }
        assertEquals(ZhuyinKeys.GROUP_SYMBOLS, groups.map { (it.appearance as KeyDef.Appearance.Text).displayText })
        groups.forEachIndexed { g, key -> assertEquals(sym(Keyboard.zhuyinNineKey(g)), press(key)) }
        assertEquals(listOf("ˉ", "ˊ", "ˇ", "ˋ"), faces(rows.take(4)).map { it.first() })
        rows.take(4).forEachIndexed { r, row -> assertEquals(sym(Keyboard.zhuyinNineTone(r + 1)), press(row.first())) }
        // ˙ on ˉ's swipe
        val swipe = rows[0].first().behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single().action
        assertEquals(sym(Keyboard.zhuyinNineTone(5)), swipe)
        // each tone previewed as it is tapped, ˉ too; ˙ on ˉ's long press
        rows.take(4).forEach { row -> assertTrue(row.first().popup.orEmpty().any { it is KeyDef.Popup.Preview }) }
        assertTrue(rows[0].first().popup.orEmpty().any { it is KeyDef.Popup.Keyboard.Explicit })
        assertEquals(ZhuyinKeyboard.PUNCTUATION, faces(rows)[3][3])
        assertEquals(ZhuyinKeyboard.slotAction(typing = false), press(rows[3][3]))
        // not the bottom row's comma, which stays one while typing
        assertTrue(GroupedKeyboard.bottomRow().none { it.behaviors.any { b -> b is KeyDef.Behavior.Press && b.action == press(rows[3][3]) } })
    }

    @Test
    fun theSlotIsTheSeparatorWhileTyping() {
        assertEquals(sym(Keyboard.zhuyinNineTone(0)), ZhuyinKeyboard.slotAction(typing = true))
        assertEquals(KeyAction.SymAction(KeySym(','.code), KeyStates.Virtual), ZhuyinKeyboard.slotAction(typing = false))
    }

    private fun faces(v: View): List<String> = when (v) {
        is TextKeyView -> listOf(v.mainText.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { faces(v.getChildAt(it)) }
        else -> emptyList()
    }

    // the face that changes is the slot's, beside ㄖㄥㄩ: not ~ in the side column (as it was once the tones came in)
    @Test
    fun whileTypingTheSlotBesideTheLastGroupShowsTheSeparator() {
        val keyboard = ZhuyinKeyboard(RuntimeEnvironment.getApplication(), ThemePreset.PixelDark, nine = true)
        val idle = faces(keyboard)
        keyboard.showTyping(true)
        val typing = faces(keyboard)
        assertEquals(idle.indexOf("ㄖㄥㄩ") + 1, typing.indexOf(ZhuyinKeyboard.SEPARATOR))
        assertEquals(ZhuyinKeyboard.PUNCTUATION, idle[idle.indexOf("ㄖㄥㄩ") + 1])
        assertEquals(idle.filter { it != ZhuyinKeyboard.PUNCTUATION }, typing.filter { it != ZhuyinKeyboard.SEPARATOR && it != ZhuyinKeyboard.PUNCTUATION })
        keyboard.showTyping(false)
        assertEquals(idle, faces(keyboard))
    }

    @Test
    fun aGroupOrSymbolMakesTheSlotTheSeparatorATapOfATone() {
        assertEquals(true, ZhuyinKeyboard.typesSymbol(sym(Keyboard.zhuyinNineKey(0))))
        assertEquals(true, ZhuyinKeyboard.typesSymbol(sym(Keyboard.zhuyinNineKey(ZhuyinKeys.GROUPS - 1))))
        assertEquals(true, ZhuyinKeyboard.typesSymbol(sym(Keyboard.zhuyinNineSymbol('ㄅ'))))
        assertEquals(true, ZhuyinKeyboard.typesSymbol(sym(Keyboard.zhuyinNineSymbol('ㄩ'))))
        // a tone, the separator among them, with nothing typed types nothing: the slot stays punctuation
        (0..ZhuyinKeys.TONES).forEach { assertEquals(false, ZhuyinKeyboard.typesSymbol(sym(Keyboard.zhuyinNineTone(it)))) }
        assertEquals(false, ZhuyinKeyboard.typesSymbol(sym(Keyboard.zhuyinKey('ㄅ'))))
        assertEquals(false, ZhuyinKeyboard.typesSymbol(KeyAction.HostKeyAction(",")))
    }

    @Test
    fun aLongPressTypesTheSymbolOrToneChosen() {
        val popup = KeyActionListener.Source.Popup
        assertEquals(sym(Keyboard.zhuyinNineSymbol('ㄉ')), ZhuyinKeyboard.resolve(KeyAction.HostKeyAction("ㄉ"), popup, nine = true))
        assertEquals(sym(Keyboard.zhuyinNineTone(4)), ZhuyinKeyboard.resolve(KeyAction.HostKeyAction("ˋ"), popup, nine = true))
        assertEquals(sym(Keyboard.zhuyinTone(2)), ZhuyinKeyboard.resolve(KeyAction.HostKeyAction("ˊ"), popup, nine = false))
        // anything else, and a key's own tap, as it is
        assertEquals(KeyAction.HostKeyAction("%"), ZhuyinKeyboard.resolve(KeyAction.HostKeyAction("%"), popup, nine = true))
        val tap = KeyAction.HostKeyAction("ㄉ")
        assertEquals(tap, ZhuyinKeyboard.resolve(tap, KeyActionListener.Source.Keyboard, nine = true))
    }

    @After
    fun noSyllables() {
        EngineState.syllablesState.value = EngineState.SyllableState()
    }

    private fun ZhuyinKeyboard.keyFaced(face: String): TextKeyView = keys(this).first { it.mainText.text.toString() == face }

    private fun keys(v: View): List<TextKeyView> = when (v) {
        is TextKeyView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { keys(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun nine(sent: MutableList<KeyAction>) =
        ZhuyinKeyboard(RuntimeEnvironment.getApplication(), ThemePreset.PixelDark, nine = true).apply {
            keyActionListener = KeyActionListener { action, _ -> sent += action }
        }

    @Test
    fun theSlotTypesACommaThenRightAfterAGroupTheSeparator() {
        EngineState.syllablesState.value = EngineState.SyllableState(im = InputMethodCatalog.PINYIN)
        val sent = ArrayList<KeyAction>()
        val keyboard = nine(sent)
        val slot = keyboard.keyFaced(ZhuyinKeyboard.PUNCTUATION)
        slot.performClick()
        // a tone with nothing typed: still the comma
        keyboard.keyFaced("ˇ").performClick()
        slot.performClick()
        // a group: 分词 before the engine tells
        keyboard.keyFaced("ㄖㄥㄩ").performClick()
        assertEquals(ZhuyinKeyboard.SEPARATOR, slot.mainText.text.toString())
        slot.performClick()
        val comma = ZhuyinKeyboard.slotAction(typing = false)
        assertEquals(listOf(comma, sym(Keyboard.zhuyinNineTone(3)), comma, sym(Keyboard.zhuyinNineKey(ZhuyinKeys.GROUPS - 1)), sym(Keyboard.zhuyinNineTone(0))), sent)
    }

    @Test
    fun aGroupSentToAnotherInputMethodLeavesTheSlotTheComma() {
        // the engine's last word from another input method: it would never say pinyin is done
        EngineState.syllablesState.value = EngineState.SyllableState(im = InputMethodCatalog.T9)
        val sent = ArrayList<KeyAction>()
        val keyboard = nine(sent)
        val slot = keyboard.keyFaced(ZhuyinKeyboard.PUNCTUATION)
        keyboard.keyFaced("ㄖㄥㄩ").performClick()
        assertEquals(ZhuyinKeyboard.PUNCTUATION, slot.mainText.text.toString())
    }

    @Test
    fun theSlotsCommaIsTheBottomRowsAsPunctuationMapsIt() {
        val keyboard = nine(ArrayList())
        val slot = keyboard.keyFaced(ZhuyinKeyboard.PUNCTUATION)
        // half-width punctuation: a comma unmapped
        keyboard.onPunctuationUpdate(emptyMap())
        assertEquals(",", slot.mainText.text.toString())
        keyboard.showTyping(true)
        keyboard.onPunctuationUpdate(mapOf("," to "，"))
        assertEquals(ZhuyinKeyboard.SEPARATOR, slot.mainText.text.toString())
        keyboard.showTyping(false)
        assertEquals("，", slot.mainText.text.toString())
    }
}
