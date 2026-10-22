/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.lattice.StartWords
import org.fcitx.fcitx5.android.engine.learn.ImportedWords

/**
 * [imports]' words but those [skip] says, by their syllables, packed for 100 000 of them: a run
 * of their places sorted by syllables (each node's words a part of it), and the nodes in arrays,
 * each node's children together. Made whole again as the words change: they change by batches.
 * Each word is [base] plus its place ([UserModel]'s ids of them).
 */
internal class ImportTrie(private val imports: ImportedWords, private val base: Int, skip: (Int) -> Boolean) : StartWords {
    // the places, sorted by syllables, a shorter reading before the longer ones it starts
    private val sorted: IntArray
    private var firstChildOf = IntArray(0)
    private var childCounts = IntArray(0)
    private var syllableOf = IntArray(0)
    private var wordFrom = IntArray(0)
    private var wordCounts = IntArray(0)
    private var nodes = 0

    init {
        val places = (0 until imports.places).filter { imports.has(it) && !skip(it) }
        sorted = places.sortedWith(::compare).toIntArray()
        build()
    }

    // breadth first: a node's children made together, so they are a run
    private fun build() {
        var syllables = 1
        for (p in sorted) syllables += imports.length(p)
        firstChildOf = IntArray(syllables)
        childCounts = IntArray(syllables)
        syllableOf = IntArray(syllables)
        wordFrom = IntArray(syllables)
        wordCounts = IntArray(syllables)
        // each node's run of sorted, and its depth: only while building
        val lo = IntArray(syllables)
        val hi = IntArray(syllables)
        val depthOf = IntArray(syllables)
        nodes = 1
        hi[0] = sorted.size
        var node = 0
        while (node < nodes) {
            val depth = depthOf[node]
            var i = lo[node]
            while (i < hi[node] && imports.length(sorted[i]) == depth) i++
            wordFrom[node] = lo[node]
            wordCounts[node] = i - lo[node]
            firstChildOf[node] = nodes
            while (i < hi[node]) {
                val s = imports.syllable(sorted[i], depth)
                var j = i
                while (j < hi[node] && imports.syllable(sorted[j], depth) == s) j++
                syllableOf[nodes] = s
                lo[nodes] = i
                hi[nodes] = j
                depthOf[nodes] = depth + 1
                nodes++
                i = j
            }
            childCounts[node] = nodes - firstChildOf[node]
            node++
        }
        // as many nodes as made: fewer than syllables where readings share their starts
        firstChildOf = firstChildOf.copyOf(nodes)
        childCounts = childCounts.copyOf(nodes)
        syllableOf = syllableOf.copyOf(nodes)
        wordFrom = wordFrom.copyOf(nodes)
        wordCounts = wordCounts.copyOf(nodes)
    }

    private fun compare(a: Int, b: Int): Int {
        val n = minOf(imports.length(a), imports.length(b))
        for (i in 0 until n) {
            val c = imports.syllable(a, i).compareTo(imports.syllable(b, i))
            if (c != 0) return c
        }
        return imports.length(a).compareTo(imports.length(b))
    }

    /** How many words it has. */
    val size: Int get() = sorted.size

    override fun child(node: Int, syllable: Int): Int {
        var low = firstChildOf[node]
        var high = low + childCounts[node] - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val s = syllableOf[mid]
            when {
                s < syllable -> low = mid + 1
                s > syllable -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    override fun firstChild(node: Int) = firstChildOf[node]

    override fun childCount(node: Int) = childCounts[node]

    override fun syllable(node: Int) = syllableOf[node]

    override fun wordCount(node: Int) = wordCounts[node]

    override fun word(node: Int, index: Int) = base + sorted[wordFrom[node] + index]
}
