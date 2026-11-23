/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/**
 * Every way to cut [input] into syllables: nodes are positions, edges carry the syllables their text may
 * be; separators belong to no edge. Every reachable node has an edge onward, so every path reaches [end];
 * the decoder chooses (`xian` holds xian and xi'an).
 */
class SyllableGraph internal constructor(
    val input: String,
    val start: Int,
    private val firstEdge: IntArray,
    private val edgeFrom: IntArray,
    private val edgeTo: IntArray,
    private val edgeKind: Array<Kind>,
    private val edgeMatches: Array<SyllableMatches>,
    // per edge, per match: how far letters go against tap places; null for none
    private val edgeAgainst: Array<FloatArray?>? = null,
    // per slipped edge: each match's cost and key; null for none
    private val edgeSlips: Array<Slips?>? = null,
) {
    enum class Kind {
        /** Whole syllables, exactly or through [Fuzzy] or a typo. */
        SYLLABLE,

        /** An initial on its own (简拼): `zh` for any syllable starting with it. */
        INITIAL,

        /** A syllable still being typed at the input's end or before a separator: `zho`. */
        PARTIAL,

        /** A whole syllable read as the start of a longer one: `xian` toward xiang. */
        EXTENDED,

        /** No pinyin (`i`, a digit): kept as typed. */
        RAW,

        /** A lower-case letter of a Latin word as its letter syllable: `i` of iPhone. */
        LETTER,
    }

    val end: Int get() = input.length

    val edgeCount: Int get() = edgeTo.size

    /** Empty for [end] and unreachable positions. Not sorted by target: an initial follows longer syllables. */
    fun edges(position: Int): IntRange = firstEdge[position] until firstEdge[position + 1]

    fun from(edge: Int): Int = edgeFrom[edge]

    /** Past any separators after its text. */
    fun to(edge: Int): Int = edgeTo[edge]

    fun kind(edge: Int): Kind = edgeKind[edge]

    fun matches(edge: Int): SyllableMatches = edgeMatches[edge]

    /** Summed over the edge's two-letter keys: 0 tapped on the letter's side, up to 1 at the far edge; 0 without tap places. The decoder prices it. */
    fun against(edge: Int, i: Int): Float = edgeAgainst?.get(edge)?.get(i) ?: 0f

    /** For a slipped edge (every match [SyllableMatches.NEIGHBOUR]), costs and keys; null otherwise. */
    fun slips(edge: Int): Slips? = edgeSlips?.get(edge)

    fun isSlip(edge: Int): Boolean = edgeSlips?.get(edge) != null

    /** Log10; 0 for none. */
    fun slipCost(edge: Int, i: Int): Float = edgeSlips?.get(edge)?.cost(i) ?: 0f

    fun text(edge: Int): String = text(edgeFrom[edge], edgeTo[edge])

    fun text(from: Int, to: Int): String {
        var until = to
        while (until > from && isSeparator(input[until - 1])) until--
        return input.substring(from, until)
    }

    /** The zhuyin tone typed after [edge] ([ZhuyinKeys.toneOf]), 1 to 5 (5 轻声); 0 for none. */
    fun tone(edge: Int): Int {
        var at = edgeTo[edge]
        while (at > edgeFrom[edge] && isSeparator(input[at - 1])) {
            ZhuyinKeys.toneOf(input[at - 1]).let { if (it > 0) return it }
            at--
        }
        return 0
    }

    /** 双拼's lone key read where it pairs with the next ([SyllableMatches.ABBREV]); ShuangpinSegmenter flags every match of such an edge, so the first stands for all. */
    fun isAbbreviation(edge: Int): Boolean = edgeMatches[edge].let { it.size > 0 && it.flags(0) and SyllableMatches.ABBREV != 0 }

    val hasAbbreviations: Boolean by lazy(LazyThreadSafetyMode.NONE) { (0 until edgeCount).any(::isAbbreviation) }

    fun readsOtherwise(edge: Int): Boolean = isAbbreviation(edge) || isSlip(edge)

    val hasOtherReadings: Boolean by lazy(LazyThreadSafetyMode.NONE) { hasAbbreviations || edgeSlips != null }

    /**
     * Without [readsOtherwise] edges and what only they reach: the input in whole typed syllables. Ask this
     * whether the input or `;` ends on a syllable (`lj;` in 微软 is 蓝 and ；; `nihap` ends on none).
     */
    fun withoutOtherReadings(): SyllableGraph = whole

    // made once per graph: asked several times per key
    private val whole: SyllableGraph by lazy(LazyThreadSafetyMode.NONE) {
        if (hasOtherReadings) dropOtherReadings() else this
    }

    val hasSlips: Boolean get() = edgeSlips != null

    /** Whether a slipped edge lies within [from]..[to]; if not, [withoutSlips] has the same paths there. HabitOrder holds back on it; a lone key is 简拼, not a slip. */
    fun readsSlipWithin(from: Int, to: Int): Boolean = hasSlips && slipsBefore.let { it[to] > it[from] }

    // slipped edges before each position: made once per graph, where it has any
    private val slipsBefore: IntArray by lazy(LazyThreadSafetyMode.NONE) {
        IntArray(end + 1).also { n ->
            for (at in 0 until end) n[at + 1] = n[at] + edges(at).count(::isSlip)
        }
    }

    /** Without slipped edges and what only they reach; lone keys kept. */
    fun withoutSlips(): SyllableGraph = noSlips

    private val noSlips: SyllableGraph by lazy(LazyThreadSafetyMode.NONE) {
        if (hasSlips) dropOtherReadings(::isSlip) else this
    }

    private fun dropOtherReadings(drop: (Int) -> Boolean = ::readsOtherwise): SyllableGraph {
        val reached = BooleanArray(end + 1).also { it[start] = true }
        val kept = ArrayList<Int>(edgeCount)
        val first = IntArray(end + 2)
        for (at in 0..end) {
            first[at] = kept.size
            if (!reached[at]) continue
            for (e in edges(at)) {
                if (drop(e)) continue
                kept += e
                reached[edgeTo[e]] = true
            }
        }
        first[end + 1] = kept.size
        return SyllableGraph(
            input, start, first, IntArray(kept.size) { edgeFrom[kept[it]] }, IntArray(kept.size) { edgeTo[kept[it]] },
            Array(kept.size) { edgeKind[kept[it]] }, Array(kept.size) { edgeMatches[kept[it]] },
            edgeAgainst?.let { a -> Array(kept.size) { a[kept[it]] } },
            // no slipped edge kept: none, or the graph still had other readings
            null,
        )
    }

    /** Whether [from]..[to] reads as whole typed syllables ([Kind.SYLLABLE]/[Kind.LETTER], no abbreviation or slip): `xm` yes, `ufmuh` no. Then lone-key typed words go after its first reading, and no slip raises a word. */
    fun readsWhole(from: Int, to: Int): Boolean {
        if (from >= to) return false
        val ok = BooleanArray(to + 1).also { it[from] = true }
        for (at in from until to) {
            if (!ok[at]) continue
            for (e in edges(at)) {
                if (asTyped(e) && edgeTo[e] <= to) ok[edgeTo[e]] = true
            }
        }
        return ok[to]
    }

    // a whole syllable or letter as typed: no lone key, no slip
    private fun asTyped(e: Int) = edgeKind[e].let { it == Kind.SYLLABLE || it == Kind.LETTER } && !readsOtherwise(e)

    /** The first position whose edges or input differ from [other]'s: what is read before it is the same in both (LatticeBuilder.build keeps those words, PinyinSession.Shows their readings). */
    fun sameUntil(other: SyllableGraph): Int {
        val ends = minOf(end, other.end)
        var at = 0
        while (at < ends && input[at] == other.input[at]) at++
        for (p in 0..at) if (!sameEdges(p, other)) return p
        return if (at == ends && end == other.end) end + 1 else at
    }

    private fun sameEdges(position: Int, other: SyllableGraph): Boolean {
        val mine = edges(position)
        val theirs = other.edges(position)
        if (mine.last - mine.first != theirs.last - theirs.first) return false
        for (i in 0..mine.last - mine.first) {
            val a = mine.first + i
            val b = theirs.first + i
            if (edgeTo[a] != other.edgeTo[b] || edgeKind[a] != other.edgeKind[b] || !edgeMatches[a].sameAs(other.edgeMatches[b])) return false
            if (!java.util.Arrays.equals(edgeAgainst?.get(a), other.edgeAgainst?.get(b))) return false
            if (edgeSlips?.get(a) != other.edgeSlips?.get(b)) return false
        }
        return true
    }

    override fun toString(): String = (0 until edgeCount).joinToString("\n") { e ->
        "${from(e)}-${to(e)} ${kind(e)} ${text(e)}: ${matches(e)}"
    }

    companion object {
        const val SEPARATOR = '\''

        /** [SEPARATOR], or a zhuyin tone: the one place it is said. Tone codes are in [ZhuyinKeys.BLOCK], which only zhuyin keyboards send. */
        fun isSeparator(c: Char): Boolean = c == SEPARATOR || ZhuyinKeys.toneOf(c) > 0
    }
}
