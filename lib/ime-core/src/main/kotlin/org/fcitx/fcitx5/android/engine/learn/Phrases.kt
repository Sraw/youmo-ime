/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

/**
 * The whole phrases 联想 learns (U-73): a commit's whole text, of letters alone (Han, Latin), of
 * [MIN] to [MAX] of them. Digits, punctuation, emoji and spaces keep a phone number, an address or a
 * code out; longer text is split by its punctuation into commits of its own. Nor one of Chinese
 * numerals alone ([NUMERALS]: 一三八幺二三四, 贰仟伍佰), a number put in as words (user 2026-11-02);
 * one with other words (三个人, 一起去) is a phrase as any.
 */
object Phrases {
    const val MIN = 3

    /** D-C1: a chat's whole sentences fit, and the bar has room for one. */
    const val MAX = 12

    /**
     * The characters a number is written in as words: the digits (〇 and 零, 幺 as a phone number
     * is read, 两), the units up to 亿, and the capital forms of an amount (大写: 壹 to 玖, 拾佰仟),
     * 萬億兩 as traditional text has them.
     */
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
