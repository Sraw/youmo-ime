/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import java.text.Normalizer

/**
 * A syllable with its tone, written out: pinyin with a tone mark (hǎo, lǜ) or zhuyin with its tone
 * sign (ㄏㄠˇ). Tones are numbered 1–4, [NEUTRAL] for the neutral tone (轻声); [NONE] means not known,
 * and callers leave such a character unmarked rather than guess.
 */
object ToneMark {

    const val NONE = 0
    const val NEUTRAL = 5

    // Combining marks of tones 1–4, as NFD splits them off (ǎ = a + U+030C).
    private const val MARKS = "̄́̌̀"
    private const val DIAERESIS = '̈'

    // Tones 2–4 follow the syllable; the neutral tone's ˙ goes before it; tone 1 is left bare
    // (the usual way in Taiwan's dictionaries and textbooks).
    private const val ZHUYIN_SIGNS = "ˊˇˋ"
    private const val ZHUYIN_NEUTRAL = '˙'

    /**
     * [spelling] (a pinyin syllable, `v` for ü) in [tone]: the mark on a or e, else on the o of ou,
     * else on the last vowel (so iu → iū, ui → uí); m, n, ng, hm carry it on their m or n (ń, hm̀).
     */
    fun pinyin(spelling: String, tone: Int): String {
        val s = spelling.replace('v', 'ü')
        if (tone !in 1..4) return s
        val at = when {
            'a' in s -> s.indexOf('a')
            'e' in s -> s.indexOf('e')
            "ou" in s -> s.indexOf("ou")
            else -> s.indexOfLast { it in "iouü" }.takeIf { it >= 0 } ?: s.indexOfFirst { it in "mn" }.coerceAtLeast(0)
        }
        return Normalizer.normalize(s.substring(0, at + 1) + MARKS[tone - 1] + s.substring(at + 1), Normalizer.Form.NFC)
    }

    /** [zhuyin] (a syllable's symbols) in [tone]: ㄏㄠˇ, ˙ㄗ, ㄊㄧㄢ for tone 1. */
    fun zhuyin(zhuyin: String, tone: Int): String = when (tone) {
        in 2..4 -> zhuyin + ZHUYIN_SIGNS[tone - 2]
        NEUTRAL -> ZHUYIN_NEUTRAL + zhuyin
        else -> zhuyin
    }

    /**
     * A marked pinyin syllable as the reading lists write it (`hǎo`, `lüè`, `ḿ`, `zi` for the neutral
     * tone) back to its spelling and tone: `hao` 3, `lve` 4, `m` 2, `zi` [NEUTRAL]. A trailing digit
     * (`hao3`, `zi5`) is read too. Null if it has letters pinyin does not use.
     */
    fun parse(marked: String): Pair<String, Int>? {
        val d = Normalizer.normalize(marked.lowercase(), Normalizer.Form.NFD)
        var tone = NEUTRAL
        val out = StringBuilder(d.length)
        for (c in d) {
            val m = MARKS.indexOf(c)
            when {
                m >= 0 -> tone = m + 1
                c == DIAERESIS -> if (out.endsWith('u')) out.setCharAt(out.length - 1, 'v') else return null
                c in '1'..'5' -> tone = c - '0'
                c in 'a'..'z' -> out.append(c)
                else -> return null
            }
        }
        return if (out.isEmpty()) null else out.toString() to tone
    }
}
