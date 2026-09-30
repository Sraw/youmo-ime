/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.KeyNeighbours
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.Typo
import java.util.zip.CRC32

/**
 * Makes input with one syllable typed as a user who mixes up two sounds or slips would type it,
 * from the [ExactReading] of each sample: for each [Fuzzy] pair, the syllable's partner where
 * that is a syllable (`zong` for zhong, tagged `fuzzy-z_zh`), and for each [Typo] the engine
 * forgives, the slip (`zhagn`, `typo-gn`). And, tagged [KEY], one letter anywhere in the input
 * slipped onto a key next to it ([KeyNeighbours]), whatever it then spells: `nihap`, but also
 * `nigao`. Which syllable or letter of a sample is changed, and to what, is chosen by hash, so
 * the set is the same on every run.
 */
object SlipSet {

    const val FUZZY = "fuzzy-"
    const val TYPO = "typo-"
    const val KEY = "key"

    fun generate(samples: List<Sample>): List<Sample> {
        val out = LinkedHashMap<String, Sample>()
        for (sample in samples) {
            syllableSlips(sample).forEach { out.getOrPut(it.input) { it } }
            // any letter may slip: the reading is not needed. Last, so where one comes out as a
            // syllable slip did, that one stays
            keySlip(sample)?.let { out.getOrPut(it.input) { it } }
        }
        return out.values.toList()
    }

    /** A sample for each kind of [SLIPS] that changes a syllable of [sample]'s exact reading. */
    private fun syllableSlips(sample: Sample): List<Sample> {
        val reading = ExactReading.of(sample) ?: return emptyList()
        return SLIPS.mapNotNull { (kind, slip) ->
            val changed = reading.mapIndexedNotNull { i, typed ->
                val (init, fin) = Syllables.split(Syllables.spelling(typed.syllable)) ?: return@mapIndexedNotNull null
                slip(init, fin)?.let { i to it }
            }
            if (changed.isEmpty()) return@mapNotNull null
            val (i, spelling) = changed[pick(sample.expected + kind, changed.size)]
            val at = reading[i]
            Sample(sample.input.substring(0, at.at) + spelling + sample.input.substring(at.at + at.length), sample.expected, kind)
        }
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

    private fun keySlip(sample: Sample): Sample? {
        val input = sample.input
        val letters = input.indices.filter { KeyNeighbours.of(input[it]).isNotEmpty() }
        if (letters.isEmpty()) return null
        val at = letters[pick(sample.expected + KEY, letters.size)]
        val around = KeyNeighbours.of(input[at])
        val slipped = around[pick(sample.expected + KEY + at, around.length)]
        return Sample(input.substring(0, at) + slipped + input.substring(at + 1), sample.expected, KEY)
    }

    private fun pick(key: String, size: Int): Int {
        val crc = CRC32().apply { update(key.toByteArray()) }
        return (crc.value % size).toInt()
    }
}
