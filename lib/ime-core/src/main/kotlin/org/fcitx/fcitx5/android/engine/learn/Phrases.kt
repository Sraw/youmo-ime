/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

/**
 * The whole phrases 联想 learns: a commit's whole text, letters only (Han, Latin), [MIN] to [MAX] of them.
 * Digits, punctuation, emoji and spaces keep phone numbers, addresses and codes out; longer text splits
 * at punctuation into commits of its own. Not Chinese numerals alone ([NUMERALS]: 一三八幺二三四,
 * 贰仟伍佰), a number written in words; with other words (三个人, 一起去) it is a phrase like any.
 */
object Phrases {
    const val MIN = 3

    /** A chat's whole sentences fit, and the bar has room for one. */
    const val MAX = 12

    /** Characters numbers are written in: the digits (〇 零, 幺 as phone numbers are read, 两), units up to 亿, 大写 amounts (壹 to 玖, 拾佰仟), and traditional 萬億兩. */
    const val NUMERALS = "〇零幺一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟萬億兩"

    fun learnable(text: String): Boolean {
        val n = text.codePointCount(0, text.length)
        if (n !in MIN..MAX) return false
        var i = 0
        var numerals = true
        while (i < text.length) {
            val c = text.codePointAt(i)
            if (!Character.isLetter(c)) return false
            if (numerals && (c > Char.MAX_VALUE.code || NUMERALS.indexOf(c.toChar()) < 0)) numerals = false
            i += Character.charCount(c)
        }
        return !numerals
    }
}
