/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.pinyin.SpellingIndex.Companion.keepBest
import org.fcitx.fcitx5.android.engine.pinyin.SpellingIndex.Companion.toMatches
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import java.util.TreeMap

/**
 * Cuts 双拼 into the same [SyllableGraph] that [PinyinSegmenter] makes of full pinyin, so the
 * decoder needs nothing else. From each position the graph gets:
 * - the syllables the next two keys type, with their [Fuzzy] partners;
 * - failing that, or with one key left before the end or a separator, the one key for every
 *   syllable typed starting with it: [Kind.PARTIAL] at the end (`x` on the way to `xn` 小),
 *   [Kind.INITIAL] elsewhere; and a, e, o alone for 啊, 呃, 哦 where they type no initial, as
 *   in libime (`hka` 好啊 in 微软);
 * - an upper-case letter for the Latin letter, as in full pinyin.
 *
 * A key is not read alone where it and the next make a syllable, as in libime by default: the
 * graph stays one path, and 简拼 in the middle would read every syllable a second way. The one
 * slip read with [typos] is ü typed with its own key after j, q, x, y (`jv` for ju), which libime
 * reads too; a slipped key otherwise lands on another syllable, not on a spelling to correct.
 */
class ShuangpinSegmenter(private val scheme: ShuangpinScheme, fuzzy: Set<Fuzzy> = emptySet(), typos: Boolean = true) : Segmenter {

    private val pairs = arrayOfNulls<SyllableMatches>(KEYS * KEYS)
    private val leads = arrayOfNulls<SyllableMatches>(KEYS)
    private val vowels = arrayOfNulls<SyllableMatches>(KEYS)
    private val latin = arrayOfNulls<SyllableMatches>(KEYS)

    init {
        val table = HashMap<String, TreeMap<Int, Int>>()
        val spelt = ArrayList<Triple<String, Int, Int>>()
        val initialRules = fuzzy.filter { it.onInitial }
        for (id in 0 until Syllables.count) {
            val spelling = Syllables.spelling(id)
            val parts = SpellingIndex.split(spelling)
            if (parts == null) {
                if (spelling[0].isUpperCase()) latin[spelling[0].code] = single(id)
                continue
            }
            val (init, fin) = parts
            val finalRules = fuzzy.filter { !it.onInitial && it.appliesAfter(init) }
            for ((i, iFlags) in SpellingIndex.variants(init, initialRules)) {
                for ((f, fFlags) in SpellingIndex.finals(fin, finalRules)) {
                    // with no initial the final is the syllable: ou's partner u is none (uu is shu in 小鹤)
                    if (i.isEmpty() && Syllables.id(f) < 0) continue
                    val flags = iFlags or fFlags
                    for (code in scheme.codes(i, f)) keepBest(table.getOrPut(code) { TreeMap() }, id, flags)
                    if (i.isEmpty() && f.length == 2) spelt += Triple(f, id, flags)
                    if (typos && i in U_IS_V && f.startsWith("u")) {
                        for (code in scheme.codes(i, "v" + f.substring(1))) {
                            keepBest(table.getOrPut(code) { TreeMap() }, id, flags or SyllableMatches.TYPO)
                        }
                    }
                }
            }
        }
        // spelt only where the keys type nothing else exactly
        val taken = table.filterValues { it.containsValue(0) }.keys
        for ((code, id, flags) in spelt) {
            if (scheme.leadVowel || code !in taken) keepBest(table.getOrPut(code) { TreeMap() }, id, flags)
        }
        val byLead = HashMap<Char, TreeMap<Int, Int>>()
        for ((code, syllables) in table) {
            pairs[code[0].code * KEYS + code[1].code] = toMatches(syllables)
            val lead = byLead.getOrPut(code[0]) { TreeMap() }
            syllables.forEach { (s, f) -> keepBest(lead, s, f or SyllableMatches.COMPLETION) }
        }
        byLead.forEach { (key, syllables) -> leads[key.code] = toMatches(syllables) }
        for (vowel in "aeo") {
            for (key in scheme.finalKeys(vowel.toString())) {
                if (!scheme.typesInitial(key)) vowels[key.code] = single(Syllables.id(vowel.toString()))
            }
        }
    }

    override fun reads(c: Char) = scheme.types(c)

    override fun segment(input: String): SyllableGraph = GraphEdges.build(input) { at, edges -> walk(input, at, edges) }

    private fun walk(input: String, at: Int, edges: GraphEdges) {
        val key = input[at].code
        if (key >= KEYS) return
        val letter = latin[key]
        if (letter != null) {
            edges.add(at, 1, Kind.SYLLABLE, letter)
            return
        }
        val next = if (at + 1 < input.length) input[at + 1].code else KEYS
        val pair = if (next < KEYS) pairs[key * KEYS + next] else null
        if (pair != null) {
            edges.add(at, 2, Kind.SYLLABLE, pair)
            return
        }
        leads[key]?.let { edges.add(at, 1, if (at + 1 == input.length) Kind.PARTIAL else Kind.INITIAL, it) }
        vowels[key]?.let { edges.add(at, 1, Kind.SYLLABLE, it) }
    }

    private companion object {
        /** Keys are ASCII; see [ShuangpinScheme.isKey]. */
        const val KEYS = 128

        /** Initials whose u is ü. */
        val U_IS_V = setOf("j", "q", "x", "y")

        fun single(syllable: Int) = SyllableMatches(intArrayOf(syllable), intArrayOf(0))
    }
}
