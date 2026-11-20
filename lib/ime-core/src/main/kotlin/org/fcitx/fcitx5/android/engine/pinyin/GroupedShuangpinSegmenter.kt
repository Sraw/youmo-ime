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
 * 双拼 on grouped keys ([NineKeyLetters.G18], [NineKeyLetters.NINE_SP]): the scheme's two keys per
 * syllable, each as its group's code, so each edge holds every syllable the groups allow, for the decoder
 * to weigh. Bar-taken syllables are as on [T9Segmenter]. ü's own key (`jv`) is not read: on groups it
 * read worse.
 *
 * With [neighbours], a pair also reads with one key slipped to an adjacent group, priced by tap place
 * ([SlipCost]); the 18/14 keys read none past -5 and the nine keys none past -3 (accuracy vs work trade-off).
 * With [abbreviations], a lone key also reads as an initial ([SyllableMatches.ABBREV]), mid-input 简拼,
 * over which only user-typed words are read.
 */
class GroupedShuangpinSegmenter(
    private val scheme: ShuangpinScheme,
    override val letters: NineKeyLetters,
    fuzzy: Set<Fuzzy> = emptySet(),
    private val neighbours: Boolean = false,
    private val slipCost: SlipCost = if (letters == NineKeyLetters.NINE_SP) SlipCost(floor = -3f) else SlipCost(floor = -5f),
    private val abbreviations: Boolean = true,
) : KeyedSegmenter {

    override val spellsFullPinyin: Boolean get() = false

    override fun typedAs(syllable: Int): String? = scheme.encode(syllable)

    override val typing: Any get() = scheme

    private val pairs = HashMap<String, SyllableMatches>()
    private val leads = HashMap<Char, SyllableMatches>()
    private val abbrevs = HashMap<Char, SyllableMatches>()
    private val vowels = HashMap<Char, SyllableMatches>()

    // an initial taken off the bar (zh for 自然码's v): its syllables
    private val byInitial = HashMap<String, SyllableMatches>()

    // an initial taken, then its final's key: that initial's syllables the group may end
    private val withFinal = HashMap<String, SyllableMatches>()

    // each syllable's codes in letters, fuzzy ones too: what [keysShown] picks from
    private val codesOf = HashMap<Int, ArrayList<String>>()

    init {
        val table = HashMap<String, TreeMap<Int, Int>>()
        val spelt = ArrayList<Triple<String, Int, Int>>()
        val initialIds = HashMap<String, TreeMap<Int, Int>>()
        val initialRules = fuzzy.filter { it.onInitial }
        // a scheme's keys as group codes; null if a key is on no group
        fun grouped(code: String): String? = code.map { letters.key(it) }.takeIf { it.all(letters::isCode) }?.joinToString("")
        val finalIds = HashMap<String, TreeMap<Int, Int>>()
        fun keep(code: String, id: Int, flags: Int, init: String) {
            val keys = grouped(code) ?: return
            keepBest(table.getOrPut(keys) { TreeMap() }, id, flags)
            codesOf.getOrPut(id) { ArrayList() }.let { if (code !in it) it += code }
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
        // spelt only where the keys type nothing else exactly, as ShuangpinSegmenter
        val taken = table.filterValues { it.containsValue(0) }.keys
        for ((code, id, flags) in spelt) {
            val keys = grouped(code) ?: continue
            if (scheme.leadVowel || keys !in taken) {
                keepBest(table.getOrPut(keys) { TreeMap() }, id, flags)
                codesOf.getOrPut(id) { ArrayList() }.let { if (code !in it) it += code }
            }
        }
        val byLead = HashMap<Char, TreeMap<Int, Int>>()
        for ((code, syllables) in table) {
            pairs[code] = toMatches(syllables)
            val lead = byLead.getOrPut(code[0]) { TreeMap() }
            syllables.forEach { (s, f) -> keepBest(lead, s, f or SyllableMatches.COMPLETION) }
        }
        byLead.forEach { (key, syllables) ->
            leads[key] = toMatches(syllables)
            if (abbreviations) abbrevs[key] = toMatches(TreeMap(syllables.mapValues { it.value or SyllableMatches.ABBREV }))
        }
        for (vowel in "aeo") {
            for (key in scheme.finalKeys(vowel.toString())) {
                if (!scheme.typesInitial(key) && letters.isCode(letters.key(key))) vowels[letters.key(key)] = single(Syllables.id(vowel.toString()))
            }
        }
        initialIds.forEach { (k, m) -> byInitial[k] = toMatches(m) }
        finalIds.forEach { (k, m) -> withFinal[k] = toMatches(m) }
    }

    /** Whether [final] ends a syllable after [lead] (a key code, or a bar-taken initial's letters). */
    override fun endsSyllable(lead: String, final: Char): Boolean =
        if (lead.length == 1 && letters.isCode(lead[0])) "$lead$final" in pairs else withFinal["$lead$final"] != null

    /**
     * The scheme's letters that read [syllable] off [text] (codes, after a bar-taken initial or syllable): of the
     * syllable's codes, the one most on the keys pressed, as many letters as were pressed; a taken syllable its code.
     */
    override fun keysShown(text: String, syllable: Int): String? {
        val codes = codesOf[syllable] ?: return null
        var taken = 0
        while (taken < text.length && text[taken] in 'a'..'z') taken++
        val initial = text.substring(0, taken)
        if (taken > 0 && initial !in byInitial) return scheme.encode(syllable) ?: codes.first()
        // what each place may be: a taken initial's keys, then each key's letters
        val places = listOfNotNull(initial.takeIf { it.isNotEmpty() }?.let(scheme::initialKeys)) + text.filter(letters::isCode).map(letters::letters)
        val best = codes.maxBy { code -> places.indices.count { j -> j < code.length && code[j] in places[j] } }
        return best.take(places.size)
    }

    override fun reads(c: Char) = letters.isCode(c)

    override fun isKey(c: Char) = letters.isCode(c)

    override fun initialsOf(code: Char): List<String> =
        letters.letters(code).flatMap { scheme.initialsOn(it) }.distinct().filter { it in byInitial }

    override fun started(syllable: Int, keys: Int): String {
        val spelling = Syllables.spelling(syllable)
        return SpellingIndex.split(spelling)?.first?.ifEmpty { null } ?: spelling.take(1)
    }

    // as a phrase key on 26 keys: an initial as its key, a syllable each way the scheme types it
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

    /** The bar-taken syllable or initial at [at]: its letters up to the separator. */
    private fun chosen(input: String, at: Int, edges: GraphEdges) {
        var end = at
        while (end < input.length && input[end] in 'a'..'z') end++
        val text = input.substring(at, end)
        val id = Syllables.id(text)
        when {
            // a taken initial binds the next key as its final, by group (XC IO, c taken: ci, cuo …), never a syllable
            // alone; at the end, the initial
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
            abbrevs[key]?.let { edges.add(at, 1, Kind.INITIAL, it) }
            return
        }
        leads[key]?.let { edges.add(at, 1, if (at + 1 == input.length) Kind.PARTIAL else Kind.INITIAL, it) }
        vowels[key]?.let { edges.add(at, 1, Kind.SYLLABLE, it) }
    }

    /** Adds an edge of the syllables the two keys at [at] make with one slipped to a neighbouring group, those [pair] lacks, each at its cheapest slip. */
    private fun slipped(input: String, at: Int, pair: SyllableMatches?, touches: Array<Touch?>, edges: GraphEdges) {
        val legal = pair != null || leads[input[at]] != null && leads[input[at + 1]] != null
        // made at the first slip read: none on most keys
        var best: TreeMap<Int, Slip>? = null
        for (j in 0..1) {
            val touch = touches.getOrNull(at + j) ?: continue
            for (near in touch.near) {
                // cost first: a tap mid-key reads none and looks nothing up
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

    private fun slipMatches(input: String, at: Int, j: Int, d: Char): SyllableMatches? =
        if (d == input[at + j] || !letters.isCode(d)) null else pairs[if (j == 0) "$d${input[at + 1]}" else "${input[at]}$d"]

    // [m]'s syllables [pair] lacks into [out], each at its cheapest [slip]
    private fun keep(out: TreeMap<Int, Slip>, m: SyllableMatches, pair: SyllableMatches?, slip: Slip): TreeMap<Int, Slip> {
        for (i in 0 until m.size) {
            val syllable = m.syllable(i)
            if (pair != null && pair.indexOf(syllable) >= 0) continue
            val old = out[syllable]
            if (old == null || slip.cost > old.cost) out[syllable] = Slip(slip.cost, slip.at, slip.meant, m.flags(i) or SyllableMatches.NEIGHBOUR)
        }
        return out
    }

    private class Slip(val cost: Float, val at: Int, val meant: Char, val flags: Int)

    private companion object {
        fun single(syllable: Int) = SyllableMatches(intArrayOf(syllable), intArrayOf(0))
    }
}
