/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.Syllables

/**
 * Types the full-pinyin samples of an evaluation set in 双拼, so both are measured on the same
 * sentences. The reading typed is the [ExactReading]: only samples with one are kept, which
 * leaves out abbreviations, unfinished input and typos.
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

    fun convert(samples: List<Sample>, scheme: ShuangpinScheme): List<Sample> = samples.mapNotNull { sample ->
        val reading = ExactReading.of(sample) { typed(scheme, it) != null } ?: return@mapNotNull null
        sample.copy(input = reading.joinToString("") { typed(scheme, it.syllable)!! })
    }

    /** How [syllable] is typed in [scheme]: a Latin letter as itself; null for 嗯 and the like, which it has no keys for. */
    private fun typed(scheme: ShuangpinScheme, syllable: Int): String? =
        scheme.encode(syllable) ?: Syllables.spelling(syllable).takeIf { it[0].isUpperCase() }
}
