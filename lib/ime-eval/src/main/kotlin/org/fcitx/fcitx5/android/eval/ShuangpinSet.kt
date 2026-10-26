/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.Syllables

/**
 * Types the full-pinyin samples of an evaluation set in 双拼, so both are measured on the same
 * sentences. The reading typed is the [ExactReading]: only samples with one are kept, which
 * leaves out abbreviations, unfinished input and typos. On a grouped keyboard ([KEYS]) each key
 * is then its group's code, as [T9Set] types the 14 keys.
 */
object ShuangpinSet {

    val SCHEMES = mapOf(
        "ziranma" to ShuangpinScheme.ZIRANMA,
        "ms" to ShuangpinScheme.MICROSOFT,
        "ziguang" to ShuangpinScheme.ZIGUANG,
        "abc" to ShuangpinScheme.ABC,
        "zhongwenzhixing" to ShuangpinScheme.ZHONGWENZHIXING,
        "pinyinjiajia" to ShuangpinScheme.PINYINJIAJIA,
        "xiaohe" to ShuangpinScheme.XIAOHE,
        "gb" to ShuangpinScheme.GB,
    )

    /** The grouped keyboards a set is typed on, `shuangpin --keys`: the 18 keys' or the nine keys'. */
    val KEYS = mapOf("18" to NineKeyLetters.G18, "nine" to NineKeyLetters.NINE_SP)

    /**
     * The schemes read on a grouped keyboard ([GroupedShuangpinSegmenter]): each of [SCHEMES]
     * with `-18` or `-nine`, a set typed `--keys` so.
     */
    val GROUPED: Map<String, Pair<ShuangpinScheme, NineKeyLetters>> =
        SCHEMES.flatMap { (name, scheme) -> KEYS.map { (k, letters) -> "$name-$k" to (scheme to letters) } }.toMap()

    /**
     * [samples] typed in [scheme], on [keys] if given, each key its group's code: there a sample
     * with a Latin letter is left out, the grouped keyboards having none.
     */
    fun convert(samples: List<Sample>, scheme: ShuangpinScheme, keys: NineKeyLetters? = null): List<Sample> = samples.mapNotNull { sample ->
        val reading = ExactReading.of(sample) { typed(scheme, it) != null } ?: return@mapNotNull null
        val input = reading.joinToString("") { typed(scheme, it.syllable)!! }
        if (keys == null) return@mapNotNull sample.copy(input = input)
        if (input.any { it.isUpperCase() }) return@mapNotNull null
        sample.copy(input = input.map(keys::key).joinToString(""))
    }

    /** How [syllable] is typed in [scheme]: a Latin letter as itself; null for 嗯 and the like, which it has no keys for. */
    private fun typed(scheme: ShuangpinScheme, syllable: Int): String? =
        scheme.encode(syllable) ?: Syllables.spelling(syllable).takeIf { it[0].isUpperCase() }
}
