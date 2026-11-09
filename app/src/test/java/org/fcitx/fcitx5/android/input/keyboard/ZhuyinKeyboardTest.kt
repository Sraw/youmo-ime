/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import android.graphics.Rect
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.robolectric.RuntimeEnvironment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // a bubble shown as punctuation maps its character, the first and as a swipe updates it (back to the key's own,
    // KeySwipes.preview), as the key shows it and types it (060-render-p2 S4d H1)
    @Test
    fun aBubbleShowsThePunctuationAsMapped() {
        val keyboard = ZhuyinKeyboard(RuntimeEnvironment.getApplication(), TestThemes.PixelLight, nine = true)
        keyboard.onPunctuationUpdate(mapOf("?" to "？"))
        val shown = ArrayList<PopupAction>()
        keyboard.popupActionListener = PopupActionListener { shown += it }
        keyboard.onPopupAction(PopupAction.PreviewAction(KeyRef("r2c5"), "?", Rect()))
        keyboard.onPopupAction(PopupAction.PreviewUpdateAction(KeyRef("r2c5"), "?"))
        assertEquals(listOf("？", "？"), shown.map { (it as? PopupAction.PreviewAction)?.content ?: (it as PopupAction.PreviewUpdateAction).content })
    }

    // a group's long press: the zhuyin symbols on its face alone, nothing a layout P3's themes may bring
    // could put there besides (a space, a mark), none where it shows none (060-render-p2 S4d L3)
    @Test
    fun aGroupsLongPressOffersItsZhuyinSymbolsAlone() {
        val popup = ZhuyinKeyboard.groupPopup("ㄅ ㄉ!") as KeyDef.Popup.Keyboard.Explicit
        assertEquals(listOf("ㄅ", "ㄉ"), popup.items.toList())
        assertEquals(null, ZhuyinKeyboard.groupPopup("?!"))
        assertEquals(null, ZhuyinKeyboard.groupPopup(null))
    }

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

    // 紧凑: 大千's symbols as large as 注音九键's groups, or a small phone cuts them off
    @Test
    fun compactDachensSymbolsAreSmaller() {
        fun sizes(rows: List<List<KeyDef>>) = rows.dropLast(1).flatten().mapNotNull { (it.appearance as? KeyDef.Appearance.Text)?.textSize }.toSet()
        assertEquals(setOf(20f), sizes(ZhuyinKeyboard.fullLayout()))
        assertEquals(setOf(18f), sizes(ZhuyinKeyboard.fullLayout(compact = true)))
    }

    // their swipes' shortcuts: 大千's by QWERTY place, the groups' by theirs; no tone, no slot
    @Test
    @Uat("PY-112", "T9-61")
    fun theSymbolKeysAreNamedForTheirShortcuts() {
        val full = ZhuyinKeyboard.fullLayout().dropLast(1).map { row -> row.filter { it.appearance is KeyDef.Appearance.Text }.map { it.shortcut } }
        assertEquals(listOf("zyfull/1", "zyfull/q", "zyfull/a", "zyfull/z"), full.map { it.first() })
        assertEquals("ㄤ on ;", "zyfull/;", full[2].last())
        val nine = ZhuyinKeyboard.nineLayout().dropLast(1)
        assertEquals((1..11).map { "zy9/$it" }, nine.flatMap { row -> row.mapNotNull { it.shortcut?.takeIf { s -> s.startsWith("zy9/") } } })
        // a row: its tone, three of the grid (the last row's third the slot), the side key
        assertTrue(nine.all { it.first().shortcut == null })
        assertEquals(null, nine[3][3].shortcut)
    }

    @Test
    fun theSlotIsTheSeparatorWhileTyping() {
        assertEquals(sym(Keyboard.zhuyinNineTone(0)), ZhuyinKeyboard.slotAction(typing = true))
        assertEquals(KeyAction.SymAction(KeySym(','.code), KeyStates.Virtual), ZhuyinKeyboard.slotAction(typing = false))
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

    // the layouts' action: words (060-render-p2 U4 B): what each sends on a key showing its label, 大千's and 注音九键's
    @Test
    fun theLayoutsPlaceholdersAreTheKeyboardsCodes() {
        fun full(name: String, label: String?) = ZhuyinKeyboard.placeholder(name, label, nine = false)
        fun nine(name: String, label: String?) = ZhuyinKeyboard.placeholder(name, label, nine = true)
        assertEquals(sym(Keyboard.zhuyinKey('ㄅ')), full("zhuyin", "ㄅ"))
        assertEquals(sym(Keyboard.zhuyinKey('ㄩ')), full("zhuyin", "ㄩ"))
        // a tone by its number whatever the face; ˙ the fifth
        (1..ZhuyinKeys.TONES).forEach { assertEquals(sym(Keyboard.zhuyinTone(it)), full("tone-$it", "x")) }
        (1..ZhuyinKeys.TONES).forEach { assertEquals(sym(Keyboard.zhuyinNineTone(it)), nine("tone-$it", null)) }
        // a group's code by the symbols it shows
        ZhuyinKeys.GROUP_SYMBOLS.forEachIndexed { g, symbols -> assertEquals(sym(Keyboard.zhuyinNineKey(g)), nine("zhuyin", symbols)) }
        assertEquals(ZhuyinKeyboard.slotAction(typing = false), nine("zhuyin-slot", ZhuyinKeyboard.PUNCTUATION))
        // nothing for what is no symbol, no tone, no group, no word of theirs
        listOf(full("zhuyin", "a"), full("zhuyin", "ㄅㄉ"), full("zhuyin", null), full("tone-0", "ˉ"), full("tone-6", "ˉ"), full("tone-", "ˉ"),
            full("zhuyin-slot", "，"), full("group", "ㄅ"), nine("zhuyin", "ㄅ"), nine("zhuyin", "ㄉㄅㄚ")).forEach { assertEquals(null, it) }
        assertEquals("zhuyin", ZhuyinKeyboard.layoutOf(nine = false))
        assertEquals("zhuyin-nine", ZhuyinKeyboard.layoutOf(nine = true))
    }

    @After
    fun noSyllables() {
        EngineState.syllablesState.value = EngineState.SyllableState()
    }
}
