/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.FloatList
import org.fcitx.fcitx5.android.engine.data.IntList

/**
 * Arcs grouped by start (`start[at] until start[at + 1]`), each group in end order. That puts what a key
 * changes, arcs ending near the input's end, last in each group; in dictionary order a change deep in a
 * long word would come before the short arcs and hide that they are the same.
 */
internal class Lattice {
    var start = IntArray(0)
    val to = IntList()
    val word = IntList() // NO_WORD for input kept as typed
    val scored = IntList() // the word the model scores: word, or a longer one it starts
    val cost = FloatList()
    val flags = IntList() // SHORT: over a syllable typed short (no user counts); START_ONLY: a start-only word;
    // ALONE: over a lone 双拼 key read as an initial; SLIPPED: over a key read as its neighbour
    val size get() = to.size

    // per position: the furthest position collecting read edges of, and the furthest those went
    private var reachRead = IntArray(0)
    private var reachTo = IntArray(0)
    // per position: its arcs were copied from the last decode, so equal without comparing
    private var copied = BooleanArray(0)

    // per position: its collecting's steps onto its [crossFrom] or past, in order
    private var crossings = arrayOfNulls<Crossings>(0)
    private var crossFrom = IntArray(0)

    fun copiedAt(at: Int) = copied[at]

    /** Of the arcs from [from] on, the first end before [end] that differs from [last]; [end] for none. */
    fun sameUntil(last: Lattice, from: Int, end: Int): Int {
        var until = end
        var at = from
        while (at < until) {
            // copied from the last decode: the same
            if (copied[at]) {
                at++
                continue
            }
            var a = last.start[at]
            var b = start[at]
            val aEnd = last.start[at + 1]
            val bEnd = start[at + 1]
            while (a < aEnd && b < bEnd && last.same(a, this, b)) {
                a++
                b++
            }
            // the groups differ from here; sorted by end, every arc ending before the first of these ends is in the same place in both
            for (i in a until aEnd) until = minOf(until, last.to[i])
            for (i in b until bEnd) until = minOf(until, to[i])
            at++
        }
        return until
    }

    // whether arcs added now are over a mid-input 简拼 or a slip, as the decoder's path is
    private var alone = false
    private var slipped = false

    /** Marks arcs added from now on as [path] is: over a lone key, over a slip. */
    fun mark(path: Path) {
        alone = path.alone > 0
        slipped = path.abbreviated > path.alone
    }

    private var ends = IntArray(0)
    private val toCopy = IntList()
    private val wordCopy = IntList()
    private val scoredCopy = IntList()
    private val costCopy = FloatList()
    private val flagsCopy = IntList()

    fun clear(end: Int) {
        if (start.size < end + 2) {
            start = IntArray(end + 2)
            ends = IntArray(end + 2)
            reachRead = IntArray(end + 2)
            reachTo = IntArray(end + 2)
            copied = BooleanArray(end + 2)
            crossings = arrayOfNulls(end + 2)
            crossFrom = IntArray(end + 2)
        }
        copied.fill(false, 0, end + 2)
        crossings.fill(null, 0, end + 2)
        to.clear()
        word.clear()
        scored.clear()
        cost.clear()
        flags.clear()
    }

    /** Stably orders the arcs from [first] on, none ending past [end], by end. */
    fun sortByEnd(first: Int, end: Int) {
        var sorted = true
        for (a in first + 1 until size) {
            if (to[a] < to[a - 1]) {
                sorted = false
                break
            }
        }
        if (sorted) return
        // counting sort: ends[q] becomes where the arcs ending at q go
        toCopy.clear()
        wordCopy.clear()
        scoredCopy.clear()
        costCopy.clear()
        flagsCopy.clear()
        ends.fill(0, 0, end + 1)
        toCopy.addAll(to, first, size)
        wordCopy.addAll(word, first, size)
        scoredCopy.addAll(scored, first, size)
        costCopy.addAll(cost, first, size)
        flagsCopy.addAll(flags, first, size)
        for (a in first until size) ends[to[a]]++
        var next = first
        for (q in 0..end) next += ends[q].also { ends[q] = next }
        for (i in 0 until toCopy.size) {
            val a = ends[toCopy[i]]++
            to[a] = toCopy[i]
            word[a] = wordCopy[i]
            scored[a] = scoredCopy[i]
            cost[a] = costCopy[i]
            flags[a] = flagsCopy[i]
        }
    }

    fun add(to: Int, word: Int, cost: Float, scored: Int = word, short: Boolean = false, startOnly: Boolean = false) {
        this.to += to
        this.word += word
        this.scored += scored
        this.cost += cost
        this.flags += (if (short) SHORT else 0) or (if (startOnly) START_ONLY else 0) or (if (alone) ALONE else 0) or (if (slipped) SLIPPED else 0)
    }

    fun reached(at: Int, read: Int, to: Int) {
        reachRead[at] = read
        reachTo[at] = to
        copied[at] = false
    }

    /**
     * Whether the arcs from [at] were collected reading only edges before [changed], the first position
     * where old and new graph differ: then the new one has them too. An edge to [changed] counts only if
     * it is the end of both graphs ([wasEnd], [isEnd]) or of neither: a partial syllable reads on at the end.
     */
    fun readBefore(at: Int, changed: Int, wasEnd: Boolean, isEnd: Boolean): Boolean =
        at < changed && reachRead[at] < changed && (reachTo[at] < changed || reachTo[at] == changed && wasEnd == isEnd)

    /** Adds [other]'s arcs from [at], with what collecting them read. */
    fun copy(other: Lattice, at: Int) {
        val from = other.start[at]
        val until = other.start[at + 1]
        to.addAll(other.to, from, until)
        word.addAll(other.word, from, until)
        scored.addAll(other.scored, from, until)
        cost.addAll(other.cost, from, until)
        flags.addAll(other.flags, from, until)
        reached(at, other.reachRead[at], other.reachTo[at])
        copied[at] = true
        crossings[at] = other.crossings[at]
        crossFrom[at] = other.crossFrom[at]
    }

    /** Adds [other]'s arcs from [at] ending before [end]: a prefix, as they are in end order. */
    fun copyEndingBefore(other: Lattice, at: Int, end: Int) {
        val from = other.start[at]
        val until = other.firstEndingAt(at, end)
        to.addAll(other.to, from, until)
        word.addAll(other.word, from, until)
        scored.addAll(other.scored, from, until)
        cost.addAll(other.cost, from, until)
        flags.addAll(other.flags, from, until)
    }

    /** The first arc from [at] ending at [end] or past. */
    fun firstEndingAt(at: Int, end: Int): Int {
        var lo = start[at]
        var hi = start[at + 1]
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (to[mid] < end) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun readFrom(at: Int) = reachRead[at]
    fun readTo(at: Int) = reachTo[at]

    /** Keeps a copy of the steps from [at] onto [from] or past (the caller reuses [steps]). */
    fun crossed(at: Int, from: Int, steps: Crossings) {
        crossFrom[at] = from
        crossings[at] = if (from == Int.MAX_VALUE) null else steps.copy()
    }

    /** Collecting can continue from [at]'s kept steps after a change at [changed]: same edges, every step onto [changed] or past kept. */
    fun goesOnFrom(at: Int, changed: Int): Boolean = at < changed && crossings[at] != null && crossFrom[at] <= changed

    /** In the order taken. */
    inline fun forEachCrossing(at: Int, visit: (from: Int, edge: Int, match: Int, node: Int, userNode: Int, mergedNode: Int, cost: Float, short: Boolean, abbreviated: Int) -> Unit) {
        val c = crossings[at] ?: return
        for (k in 0 until c.size) c.read(k, visit)
    }

    fun same(arc: Int, other: Lattice, otherArc: Int) =
        to[arc] == other.to[otherArc] && word[arc] == other.word[otherArc] && scored[arc] == other.scored[otherArc] &&
            cost[arc] == other.cost[otherArc] && flags[arc] == other.flags[otherArc]

    companion object {
        // [flags] bits
        const val SHORT = 1
        const val START_ONLY = 2
        const val ALONE = 4
        const val SLIPPED = 8
    }
}

/**
 * [LatticeBuilder.collect] steps, each [LatticeBuilder.step]'s arguments: position, edge (from the position's
 * first), match, the three tries' nodes, cost so far, whether an earlier syllable was short, and [Path.counts].
 */
internal class Crossings {
    private var ints = IntArray(0)
    private var costs = FloatArray(0)
    var size = 0
        private set

    fun clear() {
        size = 0
    }

    fun add(from: Int, edge: Int, match: Int, node: Int, userNode: Int, mergedNode: Int, cost: Float, short: Boolean, abbreviated: Int) {
        if (size == costs.size) {
            costs = costs.copyOf(maxOf(GROW, size * 2))
            ints = ints.copyOf(costs.size * FIELDS)
        }
        val k = size * FIELDS
        ints[k] = from
        ints[k + 1] = edge
        ints[k + 2] = match
        ints[k + 3] = node
        ints[k + 4] = userNode
        ints[k + 5] = mergedNode
        ints[k + 6] = if (short) -abbreviated - 1 else abbreviated
        costs[size++] = cost
    }

    inline fun read(index: Int, visit: (from: Int, edge: Int, match: Int, node: Int, userNode: Int, mergedNode: Int, cost: Float, short: Boolean, abbreviated: Int) -> Unit) {
        val k = index * FIELDS
        val packed = ints[k + 6]
        visit(ints[k], ints[k + 1], ints[k + 2], ints[k + 3], ints[k + 4], ints[k + 5], costs[index], packed < 0, if (packed < 0) -packed - 1 else packed)
    }

    fun copy() = Crossings().also {
        it.ints = ints.copyOf(size * FIELDS)
        it.costs = costs.copyOf(size)
        it.size = size
    }

    companion object {
        // not private: [read] is inlined into Lattice
        const val FIELDS = 7
        private const val GROW = 64
    }
}
