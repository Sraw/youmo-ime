/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.pinyin.SpellingIndex.Companion.keepBest
import org.fcitx.fcitx5.android.engine.pinyin.SpellingIndex.Companion.toMatches
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Companion.SEPARATOR
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import java.util.TreeMap

/**
 * 双拼 on a keyboard of letter groups ([NineKeyLetters.G18], [NineKeyLetters.NINE_SP]): each
 * syllable typed as [ShuangpinSegmenter] has it, by the scheme's two keys, each key then the code
 * of the group it is in. Cut the same way, two keys a syllable where they make one, one key alone
 * otherwise or at the end, so the graph is still one path: what a group leaves open is in each
 * edge's syllables (BN BN: bin, biao, nin … in 小鹤), for the decoder to weigh by the words.
 *
 * As on [T9Segmenter], a syllable taken off the bar is in the input as its letters, then a
 * separator: read as that syllable alone, or an initial taken as that initial. No Latin letters,
 * no letter typed as itself: these keyboards have neither.
 */
class GroupedShuangpinSegmenter(
    private val scheme: ShuangpinScheme,
    override val letters: NineKeyLetters,
    fuzzy: Set<Fuzzy> = emptySet(),
    typos: Boolean = true,
) : KeyedSegmenter {

    private val pairs = HashMap<String, SyllableMatches>()
    private val leads = HashMap<Char, SyllableMatches>()
    private val vowels = HashMap<Char, SyllableMatches>()

    // an initial taken off the bar (zh for 自然码's v): its syllables
    private val byInitial = HashMap<String, SyllableMatches>()

    // an initial taken, then the key of its final: the syllables of that initial the key's group may end
    private val withFinal = HashMap<String, SyllableMatches>()

    init {
        val table = HashMap<String, TreeMap<Int, Int>>()
        val spelt = ArrayList<Triple<String, Int, Int>>()
        val initialIds = HashMap<String, TreeMap<Int, Int>>()
        val initialRules = fuzzy.filter { it.onInitial }
        // a scheme's keys as their groups' codes; none where a key is on no group
        fun grouped(code: String): String? = code.map { letters.key(it) }.takeIf { it.all(letters::isCode) }?.joinToString("")
        val finalIds = HashMap<String, TreeMap<Int, Int>>()
        fun keep(code: String, id: Int, flags: Int, init: String) {
            val keys = grouped(code) ?: return
            keepBest(table.getOrPut(keys) { TreeMap() }, id, flags)
            if (init.isNotEmpty()) keepBest(finalIds.getOrPut(init + keys[1]) { TreeMap() }, id, flags)
        }
        for (id in 0 until Syllables.count) {
            val (init, fin) = SpellingIndex.split(Syllables.spelling(id)) ?: continue
            if (init.isNotEmpty()) keepBest(initialIds.getOrPut(init) { TreeMap() }, id, SyllableMatches.COMPLETION)
            val finalRules = fuzzy.filter { !it.onInitial && it.appliesAfter(init) }
            for ((i, iFlags) in SpellingIndex.variants(init, initialRules)) {
                for ((f, fFlags) in SpellingIndex.finalsAfter(i, fin, finalRules)) {
                    val flags = iFlags or fFlags
                    for (code in scheme.codes(i, f)) keep(code, id, flags, i)
                    if (i.isEmpty() && f.length == 2) spelt += Triple(f, id, flags)
                    if (typos && i in SpellingIndex.U_IS_V && f.startsWith("u")) {
                        for (code in scheme.codes(i, "v" + f.substring(1))) keep(code, id, flags or SyllableMatches.TYPO, i)
                    }
                }
            }
        }
        // spelt only where the keys type nothing else exactly, as ShuangpinSegmenter has it
        val taken = table.filterValues { it.containsValue(0) }.keys
        for ((code, id, flags) in spelt) {
            val keys = grouped(code) ?: continue
            if (scheme.leadVowel || keys !in taken) keepBest(table.getOrPut(keys) { TreeMap() }, id, flags)
        }
        val byLead = HashMap<Char, TreeMap<Int, Int>>()
        for ((code, syllables) in table) {
            pairs[code] = toMatches(syllables)
            val lead = byLead.getOrPut(code[0]) { TreeMap() }
            syllables.forEach { (s, f) -> keepBest(lead, s, f or SyllableMatches.COMPLETION) }
        }
        byLead.forEach { (key, syllables) -> leads[key] = toMatches(syllables) }
        for (vowel in "aeo") {
            for (key in scheme.finalKeys(vowel.toString())) {
                if (!scheme.typesInitial(key) && letters.isCode(letters.key(key))) vowels[letters.key(key)] = single(Syllables.id(vowel.toString()))
            }
        }
        initialIds.forEach { (k, m) -> byInitial[k] = toMatches(m) }
        finalIds.forEach { (k, m) -> withFinal[k] = toMatches(m) }
    }

    override fun reads(c: Char) = letters.isCode(c)

    override fun isKey(c: Char) = letters.isCode(c)

    override fun initialsOf(code: Char): List<String> =
        letters.letters(code).flatMap { scheme.initialsOn(it) }.distinct().filter { it in byInitial }

    override fun started(syllable: Int, keys: Int): String {
        val spelling = Syllables.spelling(syllable)
        return SpellingIndex.split(spelling)?.first?.ifEmpty { null } ?: spelling.take(1)
    }

    override fun segment(input: String): SyllableGraph = GraphEdges.build(input) { at, edges ->
        if (input[at] in 'a'..'z') chosen(input, at, edges) else walk(input, at, edges)
    }

    /** The syllable, or initial, taken at [at]: its letters up to the separator. */
    private fun chosen(input: String, at: Int, edges: GraphEdges) {
        var end = at
        while (end < input.length && input[end] in 'a'..'z') end++
        val text = input.substring(at, end)
        val id = Syllables.id(text)
        when {
            // the user's decision (b): an initial taken binds the key after it as its final, read by group
            // (XC IO, c taken: ci, cuo …), never a syllable of its own; alone at the end, the initial
            text in byInitial -> {
                val bound = if (input.getOrNull(end) == SEPARATOR) input.getOrNull(end + 1)?.let { withFinal[text + it] } else null
                if (bound != null) edges.add(at, end + 2 - at, Kind.SYLLABLE, bound) else edges.add(at, end - at, Kind.INITIAL, byInitial.getValue(text))
            }
            id >= 0 -> edges.add(at, end - at, Kind.SYLLABLE, single(id))
        }
    }

    private fun walk(input: String, at: Int, edges: GraphEdges) {
        val key = input[at]
        if (!letters.isCode(key)) return
        val next = input.getOrNull(at + 1)
        val pair = if (next != null && letters.isCode(next)) pairs["$key$next"] else null
        if (pair != null) {
            edges.add(at, 2, Kind.SYLLABLE, pair)
            return
        }
        leads[key]?.let { edges.add(at, 1, if (at + 1 == input.length) Kind.PARTIAL else Kind.INITIAL, it) }
        vowels[key]?.let { edges.add(at, 1, Kind.SYLLABLE, it) }
    }

    private companion object {
        fun single(syllable: Int) = SyllableMatches(intArrayOf(syllable), intArrayOf(0))
    }
}
