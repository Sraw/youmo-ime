/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards

/** Types an evaluation set's samples in 双拼 by their [ExactReading] (samples with none left out); on [KEYS] each key its group's code. */
object ShuangpinSet {

    val SCHEMES = mapOf(
        "ziranma" to ShuangpinScheme.ZIRANMA,
        "ms" to ShuangpinScheme.MICROSOFT,
        "xiaohe" to ShuangpinScheme.XIAOHE,
    )

    /** The grouped keyboards a set is typed on, `shuangpin --keys`: the 18 keys', the 14 keys' or the nine keys'. */
    val KEYS = mapOf("18" to NineKeyLetters.G18, "14" to NineKeyLetters.G14, "nine" to NineKeyLetters.NINE_SP)

    /** The schemes on a grouped keyboard: each of [SCHEMES] with `-18`, `-14` or `-nine`, no `-14` where the app has none. */
    val GROUPED: Map<String, Pair<ShuangpinScheme, NineKeyLetters>> =
        SCHEMES.flatMap { (name, scheme) -> KEYS.map { (k, letters) -> "$name-$k" to (scheme to letters) } }.toMap()
            .filterValues { (scheme, letters) -> letters != NineKeyLetters.G14 || ImKeyboards.has14(scheme) }

    /** [samples] typed in [scheme], on [keys] if given; there a sample with a Latin letter is left out. */
    fun convert(samples: List<Sample>, scheme: ShuangpinScheme, keys: NineKeyLetters? = null): List<Sample> = samples.mapNotNull { sample ->
        val reading = ExactReading.of(sample) { typed(scheme, it) != null } ?: return@mapNotNull null
        val input = reading.joinToString("") { typed(scheme, it.syllable)!! }
        if (keys == null) return@mapNotNull sample.copy(input = input)
        val typed = input.map(keys::key).joinToString("")
        // a key the keyboard has not (the 14 keys' ;, 微软's ing) is no sample of it, as a Latin letter is not
        if (typed.any { !keys.isCode(it) }) return@mapNotNull null
        sample.copy(input = typed)
    }

    /** How [syllable] is typed in [scheme]: a Latin letter as itself; null for 嗯 and the like, which it has no keys for. */
    private fun typed(scheme: ShuangpinScheme, syllable: Int): String? =
        scheme.encode(syllable) ?: Syllables.spelling(syllable).takeIf { it[0].isUpperCase() }
}
