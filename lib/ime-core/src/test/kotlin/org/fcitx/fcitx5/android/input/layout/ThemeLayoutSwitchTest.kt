/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.layout

import org.fcitx.fcitx5.android.engine.InputMethodCatalog.ENGLISH
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.SHUANGPIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.WUBI
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.keyboard.CustomChoice
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards.Chosen
import org.fcitx.fcitx5.android.input.layout.ThemeLayoutSwitch.Made
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeLayoutSwitchTest {
    private val xiaohe = ShuangpinScheme.XIAOHE
    private val microsoft = ShuangpinScheme.MICROSOFT
    private val g18 = listOf("q", "we", "rt", "y", "u", "io", "p", "a", "sd", "fg", "h", "jk", "l;", "z", "xc", "v", "bn", "m")
    private val g14 = listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m")

    // k17-motion's: 17 groups of its own, pinyin's alone
    private val g17 = listOf("q", "we", "rt", "y", "u", "io", "p", "a", "sd", "fg", "h", "jkl", "z", "xc", "v", "bn", "m")

    private fun made(pack: String, board: String, family: String, groups: List<String> = emptyList(), drawn: Boolean = true, tableExtras: Boolean = false) =
        Made(pack, board, CustomChoice("t$pack-$board", "$pack · $board", family, board, groups, drawn, tableExtras))

    @Test
    fun `the board each input method shows is the one its choice stood for`() {
        val chosen = Chosen(pinyin = ImKeyboard.KEYS18, shuangpin = ImKeyboard.KEYS14, english = ImKeyboard.NINE_KEYS)
        assertEquals("k18", ThemeLayoutSwitch.boardOf(PINYIN, chosen, xiaohe))
        assertEquals("k14", ThemeLayoutSwitch.boardOf(SHUANGPIN, chosen, xiaohe))
        // 微软 has no 14 keys: the 18 keys shown
        assertEquals("k18", ThemeLayoutSwitch.boardOf(SHUANGPIN, chosen, microsoft))
        assertEquals("nine", ThemeLayoutSwitch.boardOf(ENGLISH, chosen, xiaohe))
        assertEquals("qwerty", ThemeLayoutSwitch.boardOf(WUBI, chosen, xiaohe))
        // 注音's were never a listed board; a layout of the user's is the user's
        assertNull(ThemeLayoutSwitch.boardOf(PINYIN, Chosen(pinyin = ImKeyboard.ZHUYIN_FULL), xiaohe))
        assertNull(ThemeLayoutSwitch.boardOf(PINYIN, Chosen(pinyin = ImKeyboard.CUSTOM, layouts = mapOf(PINYIN to "mine")), xiaohe))
    }

    @Test
    fun `each input method on a board the pack in use had is switched where it may be typed on it`() {
        val k17 = made("mint", "k18", "grouped", g17)
        val qwerty = made("mint", "qwerty", "letters")
        val chosen = Chosen(pinyin = ImKeyboard.KEYS18, shuangpin = ImKeyboard.KEYS18)
        val plan = ThemeLayoutSwitch.plan(chosen, xiaohe, emptyMap(), "mint", null, listOf(k17, qwerty))
        // 双拼 on 17 keys of other groups: not (S8), drawn the app's 18 keys as in 0.6.0
        assertEquals(mapOf(PINYIN to k17.choice.id, ENGLISH to qwerty.choice.id), plan.slots)
        assertEquals(mapOf(k17 to listOf(PINYIN), qwerty to listOf(ENGLISH)), plan.uses)
        // the tables on the full keyboard: not on letters without their extra keys
        assertEquals(emptyList<String>(), plan.aside.map { it.board })
        val withExtras = made("mint", "qwerty", "letters", tableExtras = true)
        assertEquals(withExtras.choice.id, ThemeLayoutSwitch.plan(Chosen(), xiaohe, emptyMap(), "mint", null, listOf(withExtras)).slots[ImKeyboards.TABLE])
    }

    @Test
    fun `the 18 keys of the 18 keys' groups are 双拼's too, the 14 keys' not under a scheme that types a semicolon`() {
        val k18 = made("mint", "k18", "grouped", g18.reversed())
        val k14 = made("mint", "k14", "grouped", g14)
        val chosen = Chosen(shuangpin = ImKeyboard.KEYS18)
        assertEquals(mapOf(SHUANGPIN to k18.choice.id), ThemeLayoutSwitch.plan(chosen, xiaohe, emptyMap(), "mint", null, listOf(k18, k14)).slots)
        val on14 = Chosen(shuangpin = ImKeyboard.KEYS14)
        assertEquals(mapOf(SHUANGPIN to k14.choice.id), ThemeLayoutSwitch.plan(on14, xiaohe, emptyMap(), "mint", null, listOf(k18, k14)).slots)
        // 微软: the 14 keys shown as the 18 keys, the 18 keys' layout on it
        assertEquals(mapOf(SHUANGPIN to k18.choice.id), ThemeLayoutSwitch.plan(on14, microsoft, emptyMap(), "mint", null, listOf(k18, k14)).slots)
    }

    @Test
    fun `one not drawn, one of a pack in neither slot, and a choice of the user's change nothing`() {
        val refused = made("mint", "k18", "grouped", g18, drawn = false)
        val chosen = Chosen(pinyin = ImKeyboard.KEYS18)
        assertEquals(emptyMap<String, String>(), ThemeLayoutSwitch.plan(chosen, xiaohe, emptyMap(), "mint", null, listOf(refused)).slots)
        val k18 = made("other", "k18", "grouped", g18)
        val plan = ThemeLayoutSwitch.plan(chosen, xiaohe, emptyMap(), "mint", null, listOf(k18))
        assertEquals(emptyMap<String, String>(), plan.slots)
        assertEquals(emptyMap<Made, List<String>>(), plan.uses)
        val own = Chosen(pinyin = ImKeyboard.CUSTOM, layouts = mapOf(PINYIN to "mine"))
        assertEquals(emptyMap<String, String>(), ThemeLayoutSwitch.plan(own, xiaohe, emptyMap(), "other", null, listOf(k18)).slots)
    }

    @Test
    fun `both slots' packs with the same board, the shown one's switched in, the other's aside as Q15 has it`() {
        val light = made("day", "k18", "grouped", g18)
        val dark = made("night", "k18", "grouped", g18)
        val darkNine = made("night", "nine", "nine")
        val chosen = Chosen(pinyin = ImKeyboard.KEYS18, english = ImKeyboard.NINE_KEYS)
        val plan = ThemeLayoutSwitch.plan(chosen, xiaohe, emptyMap(), "day", "night", listOf(dark, darkNine, light))
        // the other slot's board the shown one has not: it was drawn there, it is taken
        assertEquals(mapOf(PINYIN to light.choice.id, ENGLISH to darkNine.choice.id), plan.slots)
        assertEquals(listOf(dark), plan.aside)
        assertEquals(listOf(darkNine, light), plan.uses.keys.toList())
        // one pack of both versions in both slots: nothing aside
        assertEquals(emptyList<Made>(), ThemeLayoutSwitch.plan(chosen, xiaohe, emptyMap(), "day", "day", listOf(light)).aside)
    }

    @Test
    fun `the number keyboard and the symbol panel take their family's, where the app's is chosen`() {
        val number = made("mint", "number", "number")
        val symbols = made("mint", "symbols", "symbols")
        val plan = ThemeLayoutSwitch.plan(Chosen(pinyin = ImKeyboard.ZHUYIN_NINE), xiaohe, mapOf(ThemeLayoutSwitch.NUMBER to ""), "mint", null, listOf(number, symbols))
        assertEquals(mapOf(ThemeLayoutSwitch.NUMBER to number.choice.id, ThemeLayoutSwitch.SYMBOLS to symbols.choice.id), plan.panels)
        // 拼音 on 注音: not a board of these; English and the tables on the full keyboard, none made of it
        assertEquals(emptyMap<String, String>(), plan.slots)
        val chosenAlready = ThemeLayoutSwitch.plan(Chosen(), xiaohe, mapOf(ThemeLayoutSwitch.NUMBER to "mine"), "mint", null, listOf(number))
        assertEquals(emptyMap<String, String>(), chosenAlready.panels)
        val refused = made("mint", "number", "number", drawn = false)
        assertEquals(emptyMap<String, String>(), ThemeLayoutSwitch.plan(Chosen(), xiaohe, emptyMap(), "mint", null, listOf(refused)).panels)
    }
}
