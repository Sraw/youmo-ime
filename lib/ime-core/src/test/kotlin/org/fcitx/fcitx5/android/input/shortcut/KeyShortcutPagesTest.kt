/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.Uat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyShortcutPagesTest {

    @Test
    fun eachPageHasItsKeyboardsKeysNamedAsTheKeyboardNamesThem() {
        assertEquals(listOf(10, 9, 7), KeyShortcutPages.Full.rows.map { it.size })
        assertEquals("full/a", KeyShortcutPages.Full.rows[1][0].name)
        assertEquals(listOf("t9/1", "t9/2", "t9/3"), KeyShortcutPages.Nine.rows[0].map { it.name })
        assertEquals(listOf(5, 5, 4), KeyShortcutPages.Grouped14.rows.map { it.size })
        assertEquals("g14/cv", KeyShortcutPages.Grouped14.rows[2][1].name)
        assertEquals(listOf(7, 6, 5), KeyShortcutPages.Grouped18.rows.map { it.size })
        assertEquals("the ; left off, as the keyboard's", "g18/l", KeyShortcutPages.Grouped18.rows[1].last().name)
        assertEquals("num/0", KeyShortcutPages.Number.rows.last().single().name)
    }

    @Test
    fun zhuyinKeysAreNamedByTheirQwertyKey() {
        val first = KeyShortcutPages.ZhuyinFull.rows.map { it.first() }
        assertEquals(listOf("ㄅ", "ㄆ", "ㄇ", "ㄈ"), first.map { it.label })
        assertEquals(listOf("zyfull/1", "zyfull/q", "zyfull/a", "zyfull/z"), first.map { it.name })
        assertEquals(listOf(11, 10, 10, 10), KeyShortcutPages.ZhuyinFull.rows.map { it.size })
        assertEquals("ㄤ on ;", "zyfull/;", KeyShortcutPages.ZhuyinFull.rows[2].last().name)
    }

    @Test
    @Uat("T9-61")
    fun zhuyinNinesGroupsAreNamedByTheirPlaceThreeARow() {
        val page = KeyShortcutPages.ZhuyinNine
        // a tone first, then the groups, ？ ！ ～ last, the ， in place (the first row's backspace left off)
        assertEquals(listOf(4, 5, 5, 5), page.rows.map { it.size })
        assertEquals(
            listOf("zy9/tone-1") + (1..3).map { "zy9/$it" } + listOf("zy9/tone-2") + (4..6).map { "zy9/$it" } + "zy9/question" +
                "zy9/tone-3" + (7..9).map { "zy9/$it" } + "zy9/exclam" + "zy9/tone-4" + listOf("zy9/10", "zy9/11", "zy9/tilde"),
            page.names,
        )
        assertEquals(listOf("ˉ", "ㄅㄉㄚ", "ㄍㄐㄞ", "ㄓㄗㄢㄦ"), page.rows.first().map { it.label })
        assertEquals(listOf("ˋ", "ㄈㄌㄝ", "ㄖㄥㄩ", "，", "～"), page.rows.last().map { it.label })
        // the ， is the function key's, edited on its tab
        assertEquals(listOf("fn/comma"), page.rows.flatten().filter(page::elsewhere).map { it.name })
        // no preset: a group's symbols are no letters
        assertTrue(page.names.all { KeyShortcutPreset.of(it) == KeyShortcut.None })
        // its tab before 大千's
        assertEquals(KeyShortcutPages.all.indexOf(KeyShortcutPages.ZhuyinFull) - 1, KeyShortcutPages.all.indexOf(page))
    }

    @Test
    fun zhuyinNineAndTheNumbersHaveNoPresetsAndZhuyinNinesShortRowIsLeft() {
        assertEquals(listOf(KeyShortcutPages.ZhuyinNine, KeyShortcutPages.Number), KeyShortcutPages.all.filterNot { it.hasPresets })
        assertEquals(listOf(KeyShortcutPages.ZhuyinNine), KeyShortcutPages.all.filter { it.leftAligned })
    }

    @Test
    fun aFunctionKeyHasItsWayUpThenDown() {
        val space = KeyShortcutPages.Function.rows.first()
        assertEquals(listOf("fn/space.up", "fn/space.down"), space.map { it.name })
        assertTrue(KeyShortcutPages.all.all { page -> page.names.all { KeyShortcuts.formOf(it) == page.form } })
        assertEquals(KeyShortcutPages.all.size, KeyShortcutPages.all.map { it.form }.toSet().size)
    }
}
