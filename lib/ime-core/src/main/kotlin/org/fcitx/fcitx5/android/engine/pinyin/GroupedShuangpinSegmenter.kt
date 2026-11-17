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
 * no letter typed as itself: these keyboards have neither. ü typed with its own key (`jv` for ju),
 * which [ShuangpinSegmenter] reads, is not read: on the groups it read one sentence fewer right
 * (the user's 2026-11-03, dev/design/060-correction-fuzzy-report.md).
 *
 * With [neighbours], two keys read too as the syllable they make with one of them slipped from a
 * group key around it, as likely as where it was tapped says ([SlipCost]), as [ShuangpinSegmenter]
 * reads them on the 26 keys: legal, costing more, where the groups as typed make a syllable or are
 * both read alone, which on groups is nearly always, so a tap in a key's middle reads none and one
 * at its edge towards another does (061-neighbour-groups). The 18 and 14 keys read none past -5: none
 * typed on target turns wrong to -6 (σ 0.15, the user's 11-16 rule), but past -5 a key took a sixth more arcs
 * on the 18 keys (NeighbourKeyWorkTest; a tenth allowed, 061-neighbour-18key-impl-report §5); the nine keys' digits none past -3,
 * as pinyin's nine keys (6T, 061-neighbour-correction §7.2). A key with no place reads no slip.
 */
class GroupedShuangpinSegmenter(
    private val scheme: ShuangpinScheme,
    override val letters: NineKeyLetters,
    fuzzy: Set<Fuzzy> = emptySet(),
    private val neighbours: Boolean = false,
    private val slipCost: SlipCost = if (letters == NineKeyLetters.NINE_SP) SlipCost(floor = -3f) else SlipCost(floor = -5f),
) : KeyedSegmenter {

    override val spellsFullPinyin: Boolean get() = false

    override fun typedAs(syllable: Int): String? = scheme.encode(syllable)

    override val typing: Any get() = scheme

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

    /**
     * Whether [final], a key's code, ends a syllable after [lead]: a key's code (the pair reads one) or
     * an initial taken off the bar, as letters (its syllables of that final's group).
     */
    override fun endsSyllable(lead: String, final: Char): Boolean =
        if (lead.length == 1 && letters.isCode(lead[0])) "$lead$final" in pairs else withFinal["$lead$final"] != null

    override fun reads(c: Char) = letters.isCode(c)

    override fun isKey(c: Char) = letters.isCode(c)

    override fun initialsOf(code: Char): List<String> =
        letters.letters(code).flatMap { scheme.initialsOn(it) }.distinct().filter { it in byInitial }

    override fun started(syllable: Int, keys: Int): String {
        val spelling = Syllables.spelling(syllable)
        return SpellingIndex.split(spelling)?.first?.ifEmpty { null } ?: spelling.take(1)
    }

    // as a phrase's key types it on the 26 keys: an initial its key, a syllable each way the scheme types it
    override fun takenAs(spelling: String): List<String> {
        if (spelling in byInitial) return scheme.initialKeys(spelling).map(Char::toString)
        val id = Syllables.id(spelling)
        if (id < 0) return emptyList()
        val (init, fin) = SpellingIndex.split(spelling) ?: return emptyList()
        return (scheme.codes(init, fin) + listOfNotNull(scheme.encode(id))).distinct()
    }

    override fun segment(input: String): SyllableGraph = segment(input, null, null)

    override fun segment(input: String, taps: FloatArray?, touches: Array<Touch?>?): SyllableGraph = GraphEdges.build(input) { at, edges ->
        if (input[at] in 'a'..'z') chosen(input, at, edges) else walk(input, at, edges, touches?.takeIf { neighbours })
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

    private fun walk(input: String, at: Int, edges: GraphEdges, touches: Array<Touch?>?) {
        val key = input[at]
        if (!letters.isCode(key)) return
        val next = input.getOrNull(at + 1)
        val pair = if (next != null && letters.isCode(next)) pairs["$key$next"] else null
        if (touches != null && next != null && letters.isCode(next)) slipped(input, at, pair, touches, edges)
        if (pair != null) {
            edges.add(at, 2, Kind.SYLLABLE, pair)
            return
        }
        leads[key]?.let { edges.add(at, 1, if (at + 1 == input.length) Kind.PARTIAL else Kind.INITIAL, it) }
        vowels[key]?.let { edges.add(at, 1, Kind.SYLLABLE, it) }
    }

    /**
     * Adds the edge of the syllables the two group keys at [at] make with one of them read as a group
     * key around it where it was tapped, those [pair] (what they make as typed) has left out, each at
     * its cheapest slip: [ShuangpinSegmenter]'s, on the groups' codes.
     */
    private fun slipped(input: String, at: Int, pair: SyllableMatches?, touches: Array<Touch?>, edges: GraphEdges) {
        val legal = pair != null || leads[input[at]] != null && leads[input[at + 1]] != null
        // made at the first slip read: none on most keys
        var best: TreeMap<Int, Slip>? = null
        for (j in 0..1) {
            val touch = touches.getOrNull(at + j) ?: continue
            for (near in touch.near) {
                // the cost first: a tap in a key's middle reads none, and looks nothing up
                val cost = slipCost.of(touch, near, legal)
                val m = if (cost.isNaN()) null else slipMatches(input, at, j, near.char)
                if (m != null) best = keep(best ?: TreeMap(), m, pair, Slip(cost, j, near.char, 0))
            }
        }
        val found = best
        if (found == null || found.isEmpty()) return
        val slips = Slips.Builder()
        found.values.forEach { slips.add(it.cost, it.at, it.meant) }
        edges.add(at, 2, Kind.SYLLABLE, SyllableMatches(found.keys.toIntArray(), found.values.map { it.flags }.toIntArray()), slip = slips.build())
    }

    // what the two keys at [at] make with key [j] read as [d], another group's code than the one typed
    private fun slipMatches(input: String, at: Int, j: Int, d: Char): SyllableMatches? =
        if (d == input[at + j] || !letters.isCode(d)) null else pairs[if (j == 0) "$d${input[at + 1]}" else "${input[at]}$d"]

    // [m]'s syllables [pair] left out into [out], each at its cheapest [slip]
    private fun keep(out: TreeMap<Int, Slip>, m: SyllableMatches, pair: SyllableMatches?, slip: Slip): TreeMap<Int, Slip> {
        for (i in 0 until m.size) {
            val syllable = m.syllable(i)
            if (pair != null && pair.indexOf(syllable) >= 0) continue
            val old = out[syllable]
            if (old == null || slip.cost > old.cost) out[syllable] = Slip(slip.cost, slip.at, slip.meant, m.flags(i) or SyllableMatches.NEIGHBOUR)
        }
        return out
    }

    // a syllable's cheapest slip so far: its cost, which of the two keys, the key meant, its match's flags
    private class Slip(val cost: Float, val at: Int, val meant: Char, val flags: Int)

    private companion object {
        fun single(syllable: Int) = SyllableMatches(intArrayOf(syllable), intArrayOf(0))
    }
}
