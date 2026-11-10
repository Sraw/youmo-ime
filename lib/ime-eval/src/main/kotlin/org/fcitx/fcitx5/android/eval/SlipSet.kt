/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.KeyNeighbours
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
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
 *
 * In 双拼 ([generate] with a scheme) the same kinds, typed in the scheme: a [Fuzzy] partner's keys,
 * [Typo.V] as ü's own key after j, q, x, y (`jv` for ju in 小鹤, the one slip 双拼 reads), and a
 * key slipped onto its neighbour on the QWERTY keyboard 双拼 is typed on. Samples with no exact
 * reading, or a syllable the scheme has no keys for, are left out, as [ShuangpinSet] leaves them.
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

    /** [samples] typed in [scheme], each with one syllable mixed up or slipped, or one key slipped. */
    fun generate(samples: List<Sample>, scheme: ShuangpinScheme): List<Sample> {
        val out = LinkedHashMap<String, Sample>()
        for (sample in samples) {
            val reading = ExactReading.of(sample) { scheme.encode(it) != null } ?: continue
            val codes = reading.map { scheme.encode(it.syllable)!! }
            for ((kind, slip) in SHUANGPIN_SLIPS) {
                val changed = reading.indices.mapNotNull { i ->
                    val (init, fin) = Syllables.split(Syllables.spelling(reading[i].syllable)) ?: return@mapNotNull null
                    slip(scheme, init, fin)?.takeIf { it != codes[i] }?.let { i to it }
                }
                if (changed.isEmpty()) continue
                val (i, code) = changed[pick(sample.expected + kind, changed.size)]
                val input = codes.toMutableList().apply { set(i, code) }.joinToString("")
                out.getOrPut(input) { sample.copy(input = input, tag = kind) }
            }
            keySlip(sample.copy(input = codes.joinToString("")))?.let { out.getOrPut(it.input) { it } }
        }
        return out.values.toList()
    }

    private val SHUANGPIN_SLIPS: List<Pair<String, (ShuangpinScheme, String, String) -> String?>> =
        Fuzzy.entries.map { rule ->
            FUZZY + rule.name.lowercase() to { scheme: ShuangpinScheme, i: String, f: String ->
                fuzzy(rule, i, f)?.let { scheme.encode(Syllables.id(it)) }
            }
        } + (TYPO + Typo.V.name.lowercase() to { scheme: ShuangpinScheme, i: String, f: String ->
            // ü's own key for the u these initials spell it as, what ShuangpinSegmenter reads with typos: the
            // key of lü's or lüe's final, after this syllable's initial key (no lüan, so no key for üan)
            Typo.V.of(i, f)?.let { slipped ->
                val own = scheme.encode(Syllables.id("l" + slipped.substring(i.length)).takeIf { it >= 0 } ?: return@let null)
                val code = scheme.encode(Syllables.id(i + f))
                if (own == null || code == null) null else code.take(1) + own.drop(1)
            }
        })

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
            sample.copy(input = sample.input.substring(0, at.at) + spelling + sample.input.substring(at.at + at.length), tag = kind)
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
        return sample.copy(input = input.substring(0, at) + slipped + input.substring(at + 1), tag = KEY)
    }

    private fun pick(key: String, size: Int): Int {
        val crc = CRC32().apply { update(key.toByteArray()) }
        return (crc.value % size).toInt()
    }
}
