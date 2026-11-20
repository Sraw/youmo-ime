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
 * Cuts 双拼 into the same [SyllableGraph] as [PinyinSegmenter]: per position the syllables the next two
 * keys type (with [Fuzzy] partners), else one key as [Kind.PARTIAL] at the end or [Kind.INITIAL]; a, e,
 * o alone as in libime; upper case as Latin letters. With [abbreviations], a key that pairs with the next
 * is also read alone as an initial ([SyllableMatches.ABBREV], priced so whole-syllable input reads as
 * before): mid-input 简拼 like Rime's `abbrev` (`ufmuh` 什么时候). [typos] reads `jv` for ju. With
 * [neighbours], a pair also reads with one key slipped ([SlipCost]); a key without a tap place reads no
 * slip, since nearly any two keys make a syllable.
 */
class ShuangpinSegmenter(
    private val scheme: ShuangpinScheme,
    fuzzy: Set<Fuzzy> = emptySet(),
    typos: Boolean = true,
    private val abbreviations: Boolean = true,
    private val neighbours: Boolean = false,
    private val slipCost: SlipCost = SLIP,
) : Segmenter {

    override val spellsFullPinyin: Boolean get() = false

    override fun typedAs(syllable: Int): String? = scheme.encode(syllable)

    override val typing: Any get() = scheme

    private val pairs = arrayOfNulls<SyllableMatches>(KEYS * KEYS)
    private val leads = arrayOfNulls<SyllableMatches>(KEYS)
    // leads flagged ABBREV: read where the key and the next make a syllable
    private val abbrevs = arrayOfNulls<SyllableMatches>(KEYS)
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
                // with no initial the final is the syllable: ou's partner u is none (uu is shu in 小鹤)
                for ((f, fFlags) in SpellingIndex.finalsAfter(i, fin, finalRules)) {
                    val flags = iFlags or fFlags
                    for (code in scheme.codes(i, f)) keepBest(table.getOrPut(code) { TreeMap() }, id, flags)
                    if (i.isEmpty() && f.length == 2) spelt += Triple(f, id, flags)
                    if (typos && i in SpellingIndex.U_IS_V && f.startsWith("u")) {
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
        byLead.forEach { (key, syllables) ->
            leads[key.code] = toMatches(syllables)
            if (abbreviations) abbrevs[key.code] = toMatches(TreeMap(syllables.mapValues { it.value or SyllableMatches.ABBREV }))
        }
        for (vowel in "aeo") {
            for (key in scheme.finalKeys(vowel.toString())) {
                if (!scheme.typesInitial(key)) vowels[key.code] = single(Syllables.id(vowel.toString()))
            }
        }
    }

    override fun reads(c: Char) = scheme.types(c)

    // 微软 types ing on `;`: a final, read only as a syllable's second key; at a syllable start it is
    // punctuation, as in Microsoft Pinyin (`ni;` is 你；)
    override fun readsAfter(before: String, c: Char): Boolean {
        if (!scheme.types(c) || c in 'a'..'z' || scheme.typesInitial(c)) return true
        // the graph walks only reached positions: an edge ending on c is a syllable it ends. Whole syllables
        // only: `lj;` is lan and `;`, not l alone and `j;`
        val graph = segment(before + c).withoutOtherReadings()
        return graph.end >= 2 && graph.edges(graph.end - 2).any { graph.to(it) == graph.end && graph.kind(it) == Kind.SYLLABLE }
    }

    override fun segment(input: String): SyllableGraph = segment(input, null, null)

    override fun segment(input: String, taps: FloatArray?, touches: Array<Touch?>?): SyllableGraph =
        GraphEdges.build(input) { at, edges -> walk(input, at, edges, touches?.takeIf { neighbours }) }

    private fun walk(input: String, at: Int, edges: GraphEdges, touches: Array<Touch?>?) {
        val key = input[at].code
        if (key >= KEYS) return
        val letter = latin[key]
        if (letter != null) {
            edges.add(at, 1, Kind.SYLLABLE, letter)
            return
        }
        val next = if (at + 1 < input.length) input[at + 1].code else KEYS
        val pair = if (next < KEYS) pairs[key * KEYS + next] else null
        if (touches != null && next < KEYS && latin[next] == null) slipped(input, at, pair, touches, edges)
        if (pair != null) {
            edges.add(at, 2, Kind.SYLLABLE, pair)
            abbrevs[key]?.let { edges.add(at, 1, Kind.INITIAL, it) }
            return
        }
        leads[key]?.let { edges.add(at, 1, if (at + 1 == input.length) Kind.PARTIAL else Kind.INITIAL, it) }
        vowels[key]?.let { edges.add(at, 1, Kind.SYLLABLE, it) }
    }

    /** Adds an edge of the syllables the two keys at [at] make with one slipped to a neighbour, those [pair] lacks, each at its cheapest; ü's typo is not slipped again. */
    private fun slipped(input: String, at: Int, pair: SyllableMatches?, touches: Array<Touch?>, edges: GraphEdges) {
        val legal = pair != null || leads[input[at].code] != null && leads[input[at + 1].code] != null
        // made at the first slip read: none on most keys
        var best: TreeMap<Int, Slip>? = null
        for (j in 0..1) {
            val touch = touches.getOrNull(at + j) ?: continue
            for (near in touch.near) best = slipOnto(input, at, j, touch, near, pair, legal, best)
        }
        if (best == null || best.isEmpty()) return
        val slips = Slips.Builder()
        best.values.forEach { slips.add(it.cost, it.at, it.meant) }
        edges.add(at, 2, Kind.SYLLABLE, SyllableMatches(best.keys.toIntArray(), best.values.map { it.flags }.toIntArray()), slip = slips.build())
    }

    // [best] plus the syllables the keys at [at] make with key [j] read as [near], where [touch] allows
    private fun slipOnto(input: String, at: Int, j: Int, touch: Touch, near: Near, pair: SyllableMatches?, legal: Boolean, best: TreeMap<Int, Slip>?): TreeMap<Int, Slip>? {
        val d = near.char
        val a = if (j == 0) d.code else input[at].code
        val b = if (j == 1) d.code else input[at + 1].code
        val m = (if (d.code < KEYS && scheme.types(d)) pairs[a * KEYS + b] else null) ?: return best
        val cost = slipCost.of(touch, near, legal)
        if (cost.isNaN()) return best
        val out = best ?: TreeMap()
        for (i in 0 until m.size) {
            val syllable = m.syllable(i)
            if (m.flags(i) and SyllableMatches.TYPO != 0 || pair != null && pair.indexOf(syllable) >= 0) continue
            val old = out[syllable]
            if (old == null || cost > old.cost) out[syllable] = Slip(cost, j, d, m.flags(i) or SyllableMatches.NEIGHBOUR)
        }
        return out
    }

    private class Slip(val cost: Float, val at: Int, val meant: Char, val flags: Int)

    private companion object {
        const val KEYS = 128

        // the loosest floor that turns no on-target sentence wrong (σ 0.15); over -4 one did in each scheme
        val SLIP = SlipCost(floor = -3.75f)

        fun single(syllable: Int) = SyllableMatches(intArrayOf(syllable), intArrayOf(0))
    }
}
