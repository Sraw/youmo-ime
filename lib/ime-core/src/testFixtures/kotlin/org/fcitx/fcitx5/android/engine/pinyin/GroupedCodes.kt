/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

// The grouped keyboards' codes are private use characters, blank in source: tests write them
// through these, by letter or digit, never as literals.

/** The 14 keys' code for the key whose first letter is [first] (its block's place: a letter on no key's first is no key's code). */
fun g14Code(first: Char): Char = (NineKeyLetters.G14_BLOCK.first + (first - 'a')).toChar()

/** The 14 keys' codes for [keys], groups by their letters a space apart (`"bn ui"`); `'` as itself. */
fun g14(keys: String): String = keys.split(' ').joinToString("") { if (it == "'") it else g14Code(it[0]).toString() }

/** 九键双拼's code for its key [digit] (2 to 9). */
fun nineSpCode(digit: Char): Char = (NineKeyLetters.NINE_SP_BLOCK.first + (digit - '2')).toChar()

/** 九键双拼's codes for [digits] (`"6442"`). */
fun nineSp(digits: String): String = digits.map(::nineSpCode).joinToString("")
