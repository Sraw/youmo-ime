/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.data.IntList
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Companion.SEPARATOR
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind

/** The edges of a [SyllableGraph] being built, grouped by the node they leave. */
internal class GraphEdges private constructor(private val input: String) {
    private val firstEdge = IntArray(input.length + 2)
    private val from = IntList()
    private val to = IntList()
    private val kinds = ArrayList<Kind>()
    private val matches = ArrayList<SyllableMatches>()

    /** Adds an edge over the [length] characters at [at], leading past any separators after them. */
    fun add(at: Int, length: Int, kind: Kind, m: SyllableMatches) {
        from += at
        to += skipSeparators(input, at + length)
        kinds += kind
        matches += m
    }

    companion object {
        /**
         * Builds the graph of [input] from the edges [walk] adds at each node reachable from the
         * start; where it adds none, one character is kept as typed, so every path goes on.
         */
        fun build(input: String, walk: (at: Int, edges: GraphEdges) -> Unit): SyllableGraph {
            val n = input.length
            val edges = GraphEdges(input)
            val start = skipSeparators(input, 0)
            val reachable = BooleanArray(n + 1).also { it[start] = true }
            for (at in 0..n) {
                edges.firstEdge[at] = edges.size
                if (at == n || !reachable[at]) continue
                val before = edges.size
                walk(at, edges)
                if (edges.size == before) edges.add(at, 1, Kind.RAW, SyllableMatches.EMPTY)
                for (e in before until edges.size) reachable[edges.to[e]] = true
            }
            edges.firstEdge[n + 1] = edges.size
            return edges.toGraph(start)
        }

        fun skipSeparators(input: String, at: Int): Int {
            var i = at
            while (i < input.length && input[i] == SEPARATOR) i++
            return i
        }
    }

    private val size get() = from.size

    private fun toGraph(start: Int) =
        SyllableGraph(input, start, firstEdge, from.toArray(), to.toArray(), kinds.toTypedArray(), matches.toTypedArray())
}
