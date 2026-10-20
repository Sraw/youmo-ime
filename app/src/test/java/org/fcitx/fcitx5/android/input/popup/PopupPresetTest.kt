/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.NineKeySymbols
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in long press is looked up by the label a key shows. A label no key shows, or a list
 * that offers only the key's own character, is an entry that does nothing; a label listed twice
 * loses one of its lists, since the map keeps only the last.
 */
class PopupPresetTest {

    @Test
    fun everyLabelIsOneCharacterAsAKeyShowsIt() {
        PopupPreset.keys.forEach {
            assertEquals(it, 1, it.codePointCount(0, it.length))
        }
    }

    @Test
    fun noKeyOffersItsOwnCharacter() {
        PopupPreset.forEach { (label, items) ->
            assertFalse(label, label in items)
        }
    }

    @Test
    fun aHebrewLetterListedTwiceKeepsBothLists() {
        // yod: ײַ and ײ; het: ח׳ and ױ
        assertArrayEquals(arrayOf("\u05F2\u05B7", "\u05F2"), PopupPreset["\u05D9"])
        assertArrayEquals(arrayOf("\u05D7\u05F3", "\u05F1"), PopupPreset["\u05D7"])
        // tsadi offers tsadi with geresh, as gimel, zayin, het and tav do
        assertArrayEquals(arrayOf("\u05E6\u05F3"), PopupPreset["\u05E6"])
    }

    @Test
    fun lOffersTheSemicolonThatTwoShuangpinSchemesTypeAsAKey() {
        // 微软 and 紫光 read ';' as ing; one character picked goes through fcitx as a key
        assertTrue(";" in PopupPreset["l"]!!)
        assertTrue(PopupKeyboardUi.keyAction(";") is KeyAction.FcitxKeyAction)
    }

    // 九键's long press offers by default the full keyboard's corner characters of each key's letters
    @Test
    fun nineKeysDefaultSymbolsAreTheirLettersCornerCharacters() {
        for (digit in '2'..'9') {
            val corners = NineKeyLetters.STANDARD.letters(digit).map { PopupPreset.getValue(it.toString()).first() }
                .filter { it.length != 1 || it[0] !in '0'..'9' }
            assertEquals("$digit", corners, NineKeySymbols.DEFAULTS.getValue(digit))
        }
    }
}
