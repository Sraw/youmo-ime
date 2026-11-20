/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.fcitx.fcitx5.android.engine.pinyin.Syllables

/**
 * Each [Syllables] syllable in zhuyin (`zhi` ㄓ, `lve` ㄌㄩㄝ), in code since it is a fixed rule. Two pairs
 * share a spelling, read back as the first: `er`/`r` (ㄦ), `weng`/`wong` (ㄨㄥ). Latin letters have none.
 */
object Zhuyin {

    private val INITIALS = listOf(
        "zh" to "ㄓ", "ch" to "ㄔ", "sh" to "ㄕ", "b" to "ㄅ", "p" to "ㄆ", "m" to "ㄇ", "f" to "ㄈ",
        "d" to "ㄉ", "t" to "ㄊ", "n" to "ㄋ", "l" to "ㄌ", "g" to "ㄍ", "k" to "ㄎ", "h" to "ㄏ",
        "j" to "ㄐ", "q" to "ㄑ", "x" to "ㄒ", "r" to "ㄖ", "z" to "ㄗ", "c" to "ㄘ", "s" to "ㄙ",
    )

    // initials whose bare `i` (zhi, ci) is no vowel: zhuyin writes the initial alone
    private const val BUZZING = "zh ch sh r z c s"

    private val FINALS = mapOf(
        "a" to "ㄚ", "o" to "ㄛ", "e" to "ㄜ", "ai" to "ㄞ", "ei" to "ㄟ", "ao" to "ㄠ", "ou" to "ㄡ",
        "an" to "ㄢ", "en" to "ㄣ", "ang" to "ㄤ", "eng" to "ㄥ", "er" to "ㄦ", "ong" to "ㄨㄥ",
        "i" to "ㄧ", "ia" to "ㄧㄚ", "io" to "ㄧㄛ", "ie" to "ㄧㄝ", "iao" to "ㄧㄠ", "iu" to "ㄧㄡ",
        "ian" to "ㄧㄢ", "in" to "ㄧㄣ", "iang" to "ㄧㄤ", "ing" to "ㄧㄥ", "iong" to "ㄩㄥ",
        "u" to "ㄨ", "ua" to "ㄨㄚ", "uo" to "ㄨㄛ", "uai" to "ㄨㄞ", "ui" to "ㄨㄟ", "uan" to "ㄨㄢ",
        "un" to "ㄨㄣ", "uang" to "ㄨㄤ", "v" to "ㄩ", "ve" to "ㄩㄝ", "van" to "ㄩㄢ", "vn" to "ㄩㄣ",
    )

    // no-initial syllables, whose y/w only spells a medial, and the interjections
    private val WHOLE = mapOf(
        "yi" to "ㄧ", "ya" to "ㄧㄚ", "yo" to "ㄧㄛ", "ye" to "ㄧㄝ", "yao" to "ㄧㄠ", "you" to "ㄧㄡ",
        "yan" to "ㄧㄢ", "yin" to "ㄧㄣ", "yang" to "ㄧㄤ", "ying" to "ㄧㄥ", "yong" to "ㄩㄥ",
        "wu" to "ㄨ", "wa" to "ㄨㄚ", "wo" to "ㄨㄛ", "wai" to "ㄨㄞ", "wei" to "ㄨㄟ", "wan" to "ㄨㄢ",
        "wen" to "ㄨㄣ", "wang" to "ㄨㄤ", "weng" to "ㄨㄥ", "wong" to "ㄨㄥ",
        "yu" to "ㄩ", "yue" to "ㄩㄝ", "yuan" to "ㄩㄢ", "yun" to "ㄩㄣ",
        "r" to "ㄦ", "m" to "ㄇ", "n" to "ㄋ", "ng" to "ㄫ", "hm" to "ㄏㄇ", "hng" to "ㄏㄫ",
    )

    private fun spell(pinyin: String): String? {
        WHOLE[pinyin]?.let { return it }
        FINALS[pinyin]?.let { return it }
        for ((p, z) in INITIALS) {
            if (!pinyin.startsWith(p)) continue
            var rest = pinyin.substring(p.length)
            if (rest == "i" && p in BUZZING.split(' ')) return z
            // ju, quan, xue: pinyin drops ü's dots after j, q, x
            if (p.length == 1 && p[0] in "jqx" && rest.startsWith('u')) rest = "v" + rest.substring(1)
            return FINALS[rest]?.let { z + it }
        }
        return null
    }

    private val bySyllable: Array<String?> = Array(Syllables.count) { spell(Syllables.spelling(it)) }

    private val byZhuyin: Map<String, Int> = HashMap<String, Int>().apply {
        bySyllable.forEachIndexed { id, z -> if (z != null) putIfAbsent(z, id) }
    }

    /** Null for a Latin letter. */
    fun of(syllable: Int): String? = bySyllable.getOrNull(syllable)

    /** Null if [spelling] is not a syllable. */
    fun of(spelling: String): String? = Syllables.id(spelling).takeIf { it >= 0 }?.let(::of)

    /** [zhuyin] without tone mark; -1 if none. */
    fun syllable(zhuyin: String): Int = byZhuyin[zhuyin] ?: -1

    /** Every symbol a syllable may use, initials first; ZhuyinKeys.SYMBOLS is the same set in Unicode order (ZhuyinKeysTest holds them equal). */
    const val symbols: String = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ"
}
