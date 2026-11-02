/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
    fun nineKeysAreTheGroupsThreeARowAndTheToneKey() {
        val rows = ZhuyinKeyboard.nineLayout()
        val groups = rows.take(4).flatten().filter { (it.appearance as? KeyDef.Appearance.Text)?.displayText?.all { c -> c in ZhuyinKeys.SYMBOLS } == true }
        assertEquals(ZhuyinKeys.GROUP_SYMBOLS, groups.map { (it.appearance as KeyDef.Appearance.Text).displayText })
        groups.forEachIndexed { g, key -> assertEquals(sym(Keyboard.zhuyinNineKey(g)), press(key)) }
        val tone = rows[3].single { (it.appearance as? KeyDef.Appearance.Text)?.displayText == ZhuyinKeys.MARKS }
        assertEquals(sym(Keyboard.zhuyinNineTone(0)), press(tone))
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
}
