/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.popup.PopupPreset
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPreset
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.scene.keys.Commit
import org.fcitx.fcitx5.android.scene.keys.DefaultList
import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant
import org.fcitx.fcitx5.android.scene.keys.PresetLists
import org.fcitx.fcitx5.android.scene.keys.SlotDefaults
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scene's generators (060-keys-unify S1) against the app's they replace, while both are in the tree:
 * the same lists, the same row, the same item under the finger. Gone with the old ones at S6 (v2 m3).
 * Where the design changes a list on purpose (九键 1's row of its digit, D7), the part it keeps is held.
 */
class GeneratorParityTest {
    private fun old(p: KeyDef.Popup.Keyboard.Explicit) = Triple(p.items.take(p.symbolsFrom), p.items.drop(p.symbolsFrom), p.inRow.coerceAtLeast(0))

    private fun new(l: DefaultList) = Triple(l.row.map { it.text }, l.symbols.map { it.text }, l.focus)

    @Test
    fun theNineKeysDigits() {
        val shuangpin = ('2'..'9').associateWith { NineKeyLetters.NINE_SP.letters(NineKeyLetters.NINE_SP.codes[it - '2']).lowercase() }
        val variants = listOf(
            KeyboardVariant(KeyboardVariant.Form.Nine) to (false to false),
            KeyboardVariant(KeyboardVariant.Form.Nine, KeyboardVariant.Language.English) to (true to false),
            KeyboardVariant(KeyboardVariant.Form.Nine, KeyboardVariant.Language.Shuangpin, shuangpin) to (false to true),
        )
        for ((variant, flags) in variants) for (d in '2'..'9') {
            val letters = T9Keyboard.lettersOn(d, NineKeyLetters.STANDARD, flags.first, flags.second)
            val before = T9Keyboard.ninePopup(d, letters, NineKeySymbols.STANDARD.symbols(d))
            assertEquals("$variant $d", old(before), new(KeyDefaults.nine(d, variant)))
        }
    }

    @Test
    fun theNineKeysOneKeepsItsSymbolsAsTheyAre() {
        for (english in listOf(false, true)) {
            val before = T9Keyboard.onePopup(english, NineKeySymbols.STANDARD)!!
            val variant = KeyboardVariant(KeyboardVariant.Form.Nine, if (english) KeyboardVariant.Language.English else KeyboardVariant.Language.Pinyin)
            val after = KeyDefaults.nine('1', variant)
            assertEquals(before.items.toList(), after.symbols.map { it.text })
            assertEquals(before.asIs, after.symbols.all { it.commit == Commit.AsIs })
        }
    }

    @Test
    fun theGroups() {
        val shapes = listOf(NineKeyLetters.G14 to GroupedKeyboard.CORNERS, NineKeyLetters.G18 to GroupedKeyboard.CORNERS18)
        for ((letters, corners) in shapes) letters.codes.forEachIndexed { i, code ->
            val group = letters.letters(code)
            val before = old(GroupedKeyboard.longPressOf(group, corners[i]) { PopupPreset[it].orEmpty().toList() })
            // the old one had 微软's `;` twice, in L's row and among its symbols: once now (060-keys-unify §3.3)
            val once = before.copy(second = before.second.filter { it !in before.first })
            assertEquals(group, once, new(KeyDefaults.grouped(group, corners[i])))
        }
    }

    @Test
    fun theZhuyinGroups() {
        for (label in listOf("ㄅㄉㄚ", "ㄓㄗㄢㄦ", "ㄏㄒㄠㄡ")) {
            val before = ZhuyinKeyboard.groupPopup(label) as KeyDef.Popup.Keyboard.Explicit
            assertEquals(before.items.toList(), KeyDefaults.zhuyinGroup(label)!!.items.map { it.text })
        }
    }

    @Test
    fun thePresets() {
        assertEquals(PopupPreset.mapValues { it.value.toList() }, PresetLists.TABLE)
    }

    @Test
    fun theSlots() {
        val names = ('a'..'z').map { "full/$it" } + ('a'..'z').map { "zyfull/$it" } + ('1'..'9').map { "t9/$it" } +
            (1..11).map { "zy9/$it" } + NineKeyLetters.G14.let { l -> l.codes.map { "g14/${l.letters(it)}" } } +
            NineKeyLetters.G18.let { l -> l.codes.map { "g18/${l.letters(it)}" } } + listOf("num/1", "full/,")
        for (name in names) {
            val before = (KeyShortcutPreset.of(name) as? KeyShortcut.Command)?.command?.key
            assertEquals(name, before, SlotDefaults.command(KeyId(name)))
        }
        for (key in listOf("space", "backspace", "comma", "shift")) for (up in listOf(true, false)) {
            val before = (KeyShortcutPreset.of(KeyShortcuts.functionKey(key, up)) as? KeyShortcut.Command)?.command?.key
            val after = (SlotDefaults.of(KeyId.function(key), up, firstUp = true) as? SlotDefaults.Slot.Command)?.key
            assertEquals("$key $up", before, after)
        }
    }
}
