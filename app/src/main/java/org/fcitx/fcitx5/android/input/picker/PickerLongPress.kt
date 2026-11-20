/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

/**
 * Built in, outside the key model, so no setting reaches them. From fcitx's long press table
 * ([fcitx5/src/im/keyboard/longpress.cpp](https://github.com/fcitx/fcitx5/blob/5.0.18/src/im/keyboard/longpress.cpp#L15)).
 */
object PickerLongPress {

    private val table: Map<String, Array<String>> = hashMapOf(
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
        "." to arrayOf(",", "?", "!", ":", ";", "_", "%", "$", "^", "&"),
        // committed as shown (asIs: not the punctuation's map), so these ASCII marks type as ASCII; the marks that end
        // a sentence nearest the finger
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
        "$" to arrayOf("¢", "€", "£", "¥", "₹", "₽", "₺", "₩", "₱", "₿"),
    )

    operator fun get(symbol: String): Array<String>? = table[symbol]
}
