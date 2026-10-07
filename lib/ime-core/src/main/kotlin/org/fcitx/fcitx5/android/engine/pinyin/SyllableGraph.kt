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

    /** The typed text of [edge], without separators. */
    fun text(edge: Int): String = text(edgeFrom[edge], edgeTo[edge])

    /** The typed text from [from] to [to], without the separators it ends with. */
    fun text(from: Int, to: Int): String {
        var until = to
        while (until > from && input[until - 1] == SEPARATOR) until--
        return input.substring(from, until)
    }

    override fun toString(): String = (0 until edgeCount).joinToString("\n") { e ->
        "${from(e)}-${to(e)} ${kind(e)} ${text(e)}: ${matches(e)}"
    }

    companion object {
        const val SEPARATOR = '\''
    }
}
