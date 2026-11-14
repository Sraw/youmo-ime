/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/**
 * Every way to cut [input] into syllables. Nodes are positions in [input], from [start] to
 * [end]; an edge covers the typed text from one node to the next and carries the syllables that
 * text may stand for. Separators (`'`) belong to no edge: an edge ending before one leads to the
 * node after it, so no edge can span a separator.
 *
 * Every node reachable from [start] has an edge onwards and every edge moves forward, so every
 * path from [start] reaches [end]. Which path is right is not decided here: `xian` holds both
 * xian and xi'an, and the decoder scores them.
 */
class SyllableGraph internal constructor(
    val input: String,
    val start: Int,
    private val firstEdge: IntArray,
    private val edgeFrom: IntArray,
    private val edgeTo: IntArray,
    private val edgeKind: Array<Kind>,
    private val edgeMatches: Array<SyllableMatches>,
    // per edge, how far each of its matches' letters go against where their keys were tapped; null for none at all
    private val edgeAgainst: Array<FloatArray?>? = null,
) {
    enum class Kind {
        /** Whole syllables, exactly or through [Fuzzy] or a typo. */
        SYLLABLE,

        /** An initial on its own (简拼): `zh` for any syllable starting with it. */
        INITIAL,

        /** The start of a syllable, still being typed at the end of the input or closed by a separator: `zho`. */
        PARTIAL,

        /** A whole syllable read as the start of a longer one, as [PARTIAL]: `xian` on the way to xiang. */
        EXTENDED,

        /** Input that is no pinyin at all (`i`, a digit): no syllables, kept as typed. */
        RAW,

        /** A letter of a Latin word typed in lower case, as its letter syllable: `i` of iPhone as I. */
        LETTER,
    }

    val end: Int get() = input.length

    val edgeCount: Int get() = edgeTo.size

    /**
     * The edges leaving the node at [position]; empty for [end] and for positions no path reaches.
     * Not sorted by where they lead: an initial comes after the longer syllables from the same place.
     */
    fun edges(position: Int): IntRange = firstEdge[position] until firstEdge[position + 1]

    fun from(edge: Int): Int = edgeFrom[edge]

    /** The node this edge leads to, past any separators after its text. */
    fun to(edge: Int): Int = edgeTo[edge]

    fun kind(edge: Int): Kind = edgeKind[edge]

    fun matches(edge: Int): SyllableMatches = edgeMatches[edge]

    /**
     * How far the letters the [i]th of [edge]'s matches reads its keys as go against where the keys
     * were tapped: for each key of two letters, 0 tapped at its centre or on its letter's side, up
     * to 1 at the other edge, summed over the edge. 0 where nothing was tapped with a place (any
     * keyboard but the grouped ones). What it costs is the decoder's to say, as for the flags.
     */
    fun against(edge: Int, i: Int): Float = edgeAgainst?.get(edge)?.get(i) ?: 0f

    /** The typed text of [edge], without separators. */
    fun text(edge: Int): String = text(edgeFrom[edge], edgeTo[edge])

    /** The typed text from [from] to [to], without the separators it ends with. */
    fun text(from: Int, to: Int): String {
        var until = to
        while (until > from && isSeparator(input[until - 1])) until--
        return input.substring(from, until)
    }

    /**
     * The tone typed after [edge]'s syllable on the zhuyin keyboards ([ZhuyinKeys.toneOf]), 1 to 5
     * (5 轻声); 0 for none, the separator alone too.
     */
    fun tone(edge: Int): Int {
        var at = edgeTo[edge]
        while (at > edgeFrom[edge] && isSeparator(input[at - 1])) {
            ZhuyinKeys.toneOf(input[at - 1]).let { if (it > 0) return it }
            at--
        }
        return 0
    }

    /**
     * Whether [edge] is 双拼's key read alone where it and the next make a syllable ([SyllableMatches.ABBREV]).
     * Its first match's flags stand for all: ShuangpinSegmenter flags every match of such an edge, fuzzy
     * partners too, and those of no other.
     */
    fun isAbbreviation(edge: Int): Boolean = edgeMatches[edge].let { it.size > 0 && it.flags(0) and SyllableMatches.ABBREV != 0 }

    /** Whether any edge [isAbbreviation]: only 双拼's graphs have one. */
    val hasAbbreviations: Boolean by lazy(LazyThreadSafetyMode.NONE) { (0 until edgeCount).any(::isAbbreviation) }

    /**
     * This graph without [isAbbreviation]'s edges, nor what only they reach: the input read in
     * whole syllables, a key alone an initial only where it and the next make none, as before 简拼
     * in the middle. Itself where it has none (every way of typing but 双拼). Whether the input
     * ends on a syllable, or `;` ends one, is asked of this: `lj;` in 微软 is 蓝 and ；, though `l`
     * alone and `j;` (jing) read it too.
     */
    fun withoutAbbreviations(): SyllableGraph = whole

    // made once a graph: asked of the same one on a key by the session and the ranking more than once
    private val whole: SyllableGraph by lazy(LazyThreadSafetyMode.NONE) {
        if (hasAbbreviations) dropAbbreviations() else this
    }

    private fun dropAbbreviations(): SyllableGraph {
        val reached = BooleanArray(end + 1).also { it[start] = true }
        val kept = ArrayList<Int>(edgeCount)
        val first = IntArray(end + 2)
        for (at in 0..end) {
            first[at] = kept.size
            if (!reached[at]) continue
            for (e in edges(at)) {
                if (isAbbreviation(e)) continue
                kept += e
                reached[edgeTo[e]] = true
            }
        }
        first[end + 1] = kept.size
        return SyllableGraph(
            input, start, first, IntArray(kept.size) { edgeFrom[kept[it]] }, IntArray(kept.size) { edgeTo[kept[it]] },
            Array(kept.size) { edgeKind[kept[it]] }, Array(kept.size) { edgeMatches[kept[it]] },
            edgeAgainst?.let { a -> Array(kept.size) { a[kept[it]] } },
        )
    }

    /**
     * Whether the input from [from] to [to] reads as whole syllables, by [Kind.SYLLABLE] and
     * [Kind.LETTER] edges that are no [isAbbreviation]: `xm` (xian) and `xwmm` (xia mian) in 自然码,
     * not `ufmuh`, whose h is only begun. A code so read is the user's whole syllables: the words
     * their habits raise under it are read so too, not by its keys as initials (HabitOrder).
     */
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

    // a whole syllable or a letter, as typed: not a key read alone where it and the next make a syllable
    private fun asTyped(e: Int) = edgeKind[e].let { it == Kind.SYLLABLE || it == Kind.LETTER } && !isAbbreviation(e)

    /**
     * The first position whose edges, or the input up to it, differ from [other]'s; past both ends
     * when the two are alike. What is read from before it, along edges that stay before it, reads
     * the same in both: the decoder keeps those words from key to key (PinyinDecoder.buildLattice).
     */
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
        }
        return true
    }

    override fun toString(): String = (0 until edgeCount).joinToString("\n") { e ->
        "${from(e)}-${to(e)} ${kind(e)} ${text(e)}: ${matches(e)}"
    }

    companion object {
        const val SEPARATOR = '\''

        /**
         * Whether [c] ends a syllable: [SEPARATOR], or a tone of the zhuyin keyboards ([KeySpelling.Bopomofo]'s
         * separators, the one place they are said). The graph reads every input so, its spelling not
         * known here: a tone's code is in [ZhuyinKeys.BLOCK], which only the zhuyin keyboards send.
         */
        fun isSeparator(c: Char): Boolean = c == SEPARATOR || ZhuyinKeys.toneOf(c) > 0
    }
}
