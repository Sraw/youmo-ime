/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.Typo
import java.util.zip.CRC32

/**
 * Makes input with one syllable typed as a user who mixes up two sounds or slips would type it,
 * from the [ExactReading] of each sample: for each [Fuzzy] pair, the syllable's partner where
 * that is a syllable (`zong` for zhong, tagged `fuzzy-z_zh`), and for each [Typo] the engine
 * forgives, the slip (`zhagn`, `typo-gn`). Which syllable of a sample is changed is chosen by
 * hash, so the set is the same on every run.
 */
object SlipSet {

    const val FUZZY = "fuzzy-"
    const val TYPO = "typo-"

    fun generate(samples: List<Sample>): List<Sample> {
        val out = LinkedHashMap<String, Sample>()
        for (sample in samples) {
            val reading = ExactReading.of(sample) ?: continue
            for ((kind, slip) in SLIPS) {
                val changed = reading.mapIndexedNotNull { i, typed ->
                    val (init, fin) = Syllables.split(Syllables.spelling(typed.syllable)) ?: return@mapIndexedNotNull null
                    slip(init, fin)?.let { i to it }
                }
                if (changed.isEmpty()) continue
                val (i, spelling) = changed[pick(sample.expected + kind, changed.size)]
                val at = reading[i]
                val input = sample.input.substring(0, at.at) + spelling + sample.input.substring(at.at + at.length)
                out.getOrPut(input) { Sample(input, sample.expected, kind) }
            }
        }
        return out.values.toList()
    }

    private val SLIPS: List<Pair<String, (String, String) -> String?>> =
        Fuzzy.entries.map { rule -> FUZZY + rule.name.lowercase() to { i: String, f: String -> fuzzy(rule, i, f) } } +
            Typo.entries.map { typo -> TYPO + typo.name.lowercase() to { i: String, f: String -> typo.of(i, f) } }

    /** The syllable typed with [rule]'s partner of its initial or final, if that is a syllable. */
    private fun fuzzy(rule: Fuzzy, init: String, fin: String): String? {
        val spelling = if (rule.onInitial) {
            rule.partner(init)?.let { it + fin }
        } else {
            rule.partner(fin)?.takeIf { rule.appliesAfter(init) }?.let { init + it }
        }
        return spelling?.takeIf { Syllables.id(it) >= 0 }
    }

    private fun pick(key: String, size: Int): Int {
        val crc = CRC32().apply { update(key.toByteArray()) }
        return (crc.value % size).toInt()
    }
}
