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
 * Where a key and the next make a syllable, the key is read alone too, as the initial of every
 * syllable typed starting with it ([Kind.INITIAL] flagged [SyllableMatches.ABBREV], which costs
 * [org.fcitx.fcitx5.android.engine.lattice.Penalties.abbrev] on top of the initial's): 简拼 in the
 * middle, as Rime's double_pinyin `abbrev`. So `ufmuh` in 自然码 is 什么时候, shen typed whole
 * and the rest by initials though mu and shang are pairs, while `xm` still reads 先 first: the
 * language model chooses. The pair alone was libime's reading; the abbreviation is priced so
 * that input typed in whole syllables reads as it did
 * (dev/design/061-shuangpin-abbrev-measure.md); [abbreviations] off is that old reading, for
 * measuring against. The one slip read with [typos] is ü typed with its own key after j, q, x, y
 * (`jv` for ju), which libime reads too. With [neighbours], two keys read too as the syllable
 * they make with one of them slipped from a key around it, as likely as where it was tapped says
 * ([SlipCost]; 061-neighbour-correction §4.2): legal, costing more, where the keys as typed make
 * a syllable or are both read alone (简拼, §4.5). A key with no place reads no slip: nearly any
 * two keys make a syllable, so a slip cannot be told from what was typed without one.
 */
class ShuangpinSegmenter(
    private val scheme: ShuangpinScheme,
    fuzzy: Set<Fuzzy> = emptySet(),
    typos: Boolean = true,
    private val abbreviations: Boolean = true,
    private val neighbours: Boolean = false,
    private val slipCost: SlipCost = SlipCost(),
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

    // 微软 types ing on `;`: a final, so read only as a syllable's second key; at a
    // syllable's start it is the punctuation key, as in Microsoft Pinyin (`ni;` is 你；)
    override fun readsAfter(before: String, c: Char): Boolean {
        if (!scheme.types(c) || c in 'a'..'z' || scheme.typesInitial(c)) return true
        // the graph walks only positions reached: an edge there ending on c is a syllable it ends.
        // Read in whole syllables: `lj;` is lan and `;`, not the l alone a 简拼 reads and `j;` (jing)
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

    /**
     * Adds the edge of the syllables the two keys at [at] make with one of them read as a key
     * around it where it was tapped, those [pair] (what they make as typed) has left out, each at
     * its cheapest slip; ü's slip (a [SyllableMatches.TYPO]) is not slipped again.
     */
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

    // [best] (made if none and one is read) with the syllables the two keys at [at] make with the [j]th read as [near], where [touch] says it may be
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

    // a syllable's cheapest slip so far: its cost, which of the two keys, the key meant, its match's flags
    private class Slip(val cost: Float, val at: Int, val meant: Char, val flags: Int)

    private companion object {
        /** Keys are ASCII; see [ShuangpinScheme.isKey]. */
        const val KEYS = 128

        fun single(syllable: Int) = SyllableMatches(intArrayOf(syllable), intArrayOf(0))
    }
}
