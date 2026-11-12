/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

/**
 * The symbol panel's cells' long presses (060-keys-unify C1): built in, outside the key model, so no
 * setting reaches them. The digits', the punctuation's and the currency's of fcitx's long press table
 * ([fcitx5/src/im/keyboard/longpress.cpp](https://github.com/fcitx/fcitx5/blob/5.0.18/src/im/keyboard/longpress.cpp#L15));
 * the keys' lists are the scene's (PresetLists).
 */
object PickerLongPress {

    private val table: Map<String, Array<String>> = hashMapOf(
        //
        // Numbers
        //
        "0" to arrayOf("∅", "ⁿ", "⁰"),
        "1" to arrayOf("¹", "½", "⅓", "¼", "⅕", "⅙", "⅐", "⅛", "⅑", "⅒"),
        "2" to arrayOf("²", "⅖", "⅔"),
        "3" to arrayOf("³", "⅗", "¾", "⅜"),
        "4" to arrayOf("⁴", "⅘"),
        "5" to arrayOf("⁵", "⅝", "⅚"),
        "6" to arrayOf("⁶"),
        "7" to arrayOf("⁷", "⅞"),
        "8" to arrayOf("⁸"),
        "9" to arrayOf("⁹"),
        //
        // Punctuation
        //
        "." to arrayOf(",", "?", "!", ":", ";", "_", "%", "$", "^", "&"),
        // the bottom row's comma: its own long press was missing, so it offered only itself;
        // ASCII, so a Chinese input method shows and commits them as its punctuation; the first ones
        // sit nearest the finger, the full stop and the marks that end a sentence first
        "," to arrayOf(".", "?", "!", ":", ";", "\"", "'", "(", ")", "~"),
        "-" to arrayOf("—", "–", "·"),
        "?" to arrayOf("¿", "‽"),
        "'" to arrayOf("‘", "’", "‚", "›", "‹"),
        "!" to arrayOf("¡"),
        "\"" to arrayOf("“", "”", "„", "»", "«"),
        "/" to arrayOf("÷"),
        "#" to arrayOf("№"),
        "%" to arrayOf("‰", "℅"),
        "^" to arrayOf("↑", "↓", "←", "→"),
        "+" to arrayOf("±"),
        "<" to arrayOf("≤", "«", "‹", "⟨"),
        "=" to arrayOf("∞", "≠", "≈"),
        ">" to arrayOf("≥", "»", "›", "⟩"),
        "°" to arrayOf("′", "″", "‴"),
        //
        // Currency
        //
        "$" to arrayOf("¢", "€", "£", "¥", "₹", "₽", "₺", "₩", "₱", "₿"),
    )

    /** What a long press on the cell of [symbol] offers; null for none. */
    operator fun get(symbol: String): Array<String>? = table[symbol]
}
