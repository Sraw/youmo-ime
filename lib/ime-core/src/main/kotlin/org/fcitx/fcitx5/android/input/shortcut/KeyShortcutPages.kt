/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys

/**
 * The 按键字符和快捷键 page's tabs: each keyboard's character keys in its rows, as the keyboard
 * draws them, and the function keys with their two ways. A key is its label and its name in
 * [KeyShortcuts].
 */
object KeyShortcutPages {

    data class Key(val label: String, val name: String)

    /**
     * A tab: [form] (a [KeyShortcutPreset] constant, or [KeyShortcuts.FUNCTION]) and its [rows], a row
     * shorter than the rest [leftAligned] as the keyboard has it or centred.
     */
    data class Page(val form: String, val rows: List<List<Key>>, val leftAligned: Boolean = false) {
        val names: List<String> get() = rows.flatten().map { it.name }

        /** Whether a key of it has a preset: 套用编辑快捷键 has none to put back otherwise (注音九键, the numbers). */
        val hasPresets: Boolean get() = names.any { KeyShortcutPreset.of(it) != KeyShortcut.None }
    }

    private val FULL_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    // 大千's keys by the QWERTY key each sits on (051-zhuyin §1.3): its symbol shown, its letter its name
    private val ZHUYIN_ROWS = listOf(
        "1234567890-" to "ㄅㄉˇˋㄓˊ˙ㄚㄞㄢㄦ",
        "qwertyuiop" to "ㄆㄊㄍㄐㄔㄗㄧㄛㄟㄣ",
        "asdfghjkl;" to "ㄇㄋㄎㄑㄕㄘㄨㄜㄠㄤ",
        "zxcvbnm,./" to "ㄈㄌㄏㄒㄖㄙㄩㄝㄡㄥ",
    )

    /** The function keys a shortcut goes on, by name, in the page's order. */
    val FUNCTION_KEYS = listOf("space", "backspace", "return", "shift", "comma", "period", "symbols", "numbers", "lang", "retype", "zero", "separator")

    /**
     * The function keys whose long press is a list of characters, set by name (060-swipe-slots), on every
     * keyboard at once, the handwriting one's too; the others' long press is a menu or an action.
     */
    val FUNCTION_POPUPS = setOf("comma", "period")

    /** Whether a key named [name] by its layout has a long press set by name: a character key's (`t9/2`), or one of [FUNCTION_POPUPS]. */
    fun longPressByName(name: String): Boolean = '/' in name || name in FUNCTION_POPUPS

    val Full = Page(KeyShortcutPreset.FULL, FULL_ROWS.map { row -> row.map { Key(it.uppercase(), KeyShortcuts.charKey(KeyShortcutPreset.FULL, "$it")) } })

    val Nine = Page(
        KeyShortcutPreset.NINE,
        listOf("123", "456", "789").map { row -> row.map { Key("$it", KeyShortcuts.charKey(KeyShortcutPreset.NINE, "$it")) } },
    )

    val Grouped14 = grouped(KeyShortcutPreset.GROUPED14, NineKeyLetters.G14, intArrayOf(5, 5, 4))

    val Grouped18 = grouped(KeyShortcutPreset.GROUPED18, NineKeyLetters.G18, intArrayOf(7, 6, 5))

    /**
     * 注音九键's 11 groups, three a row as the keyboard has them, each named by its place (1 to 11):
     * a group's symbols are no letters, and its tones' column and the 分词 key take no shortcut.
     */
    val ZhuyinNine = Page(
        KeyShortcutPreset.ZHUYIN_NINE,
        ZhuyinKeys.GROUP_SYMBOLS.mapIndexed { g, symbols -> Key(symbols, KeyShortcuts.charKey(KeyShortcutPreset.ZHUYIN_NINE, "${g + 1}")) }.chunked(3),
        // ㄈㄌㄝ and ㄖㄥㄩ under the first two, the 分词 key in the third place
        leftAligned = true,
    )

    val ZhuyinFull = Page(
        KeyShortcutPreset.ZHUYIN_FULL,
        ZHUYIN_ROWS.map { (keys, symbols) -> keys.indices.map { Key("${symbols[it]}", KeyShortcuts.charKey(KeyShortcutPreset.ZHUYIN_FULL, "${keys[it]}")) } },
    )

    val Number = Page(
        KeyShortcutPreset.NUMBER,
        listOf("123", "456", "789", "0").map { row -> row.map { Key("$it", KeyShortcuts.charKey(KeyShortcutPreset.NUMBER, "$it")) } },
    )

    /** A function key's two names, up then down, under its one [Key.label] (the key's name, labelled by the app). */
    val Function = Page(
        KeyShortcuts.FUNCTION,
        FUNCTION_KEYS.map { key -> listOf(true, false).map { Key(key, KeyShortcuts.functionKey(key, it)) } },
    )

    /** Every tab in order. */
    val all = listOf(Full, Nine, Grouped14, Grouped18, ZhuyinNine, ZhuyinFull, Number, Function)

    private fun grouped(form: String, letters: NineKeyLetters, sizes: IntArray): Page {
        // a key's name as the keyboard's: its letters, the `;` left off (GroupedKeyboard)
        val groups = letters.tag().split(',').map { it.replace(";", "") }
        var at = 0
        return Page(form, sizes.map { n -> groups.subList(at, at + n).also { at += n }.map { Key(it.uppercase(), KeyShortcuts.charKey(form, it)) } })
    }
}
