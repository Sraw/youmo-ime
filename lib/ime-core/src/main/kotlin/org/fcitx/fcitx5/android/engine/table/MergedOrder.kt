/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

/**
 * A table session's candidates in order, as positions: those it found, in their [sorted] order
 * (by their [keys]), with the merged dictionaries' words of longer codes ([tail], each given a
 * position by [add]) put in where sorting them all would put them ([before]: a word of the tail
 * before the candidate of a key), read only as far as asked. A letter of 五笔 leads to some 80,000
 * of rime-frost's words: sorted with the rest at every key, they were 22 MB of garbage a key.
 */
internal class MergedOrder<T>(
    private val sorted: IntArray,
    private val keys: LongArray,
    private val tail: Iterator<T>,
    private val before: (Long, T) -> Boolean,
    private val add: (T) -> Int,
) {
    private var positions = IntArray(sorted.size + 1)
    private var filled = 0
    private var sortedAt = 0
    private var pendingTail: T? = null

    /** The [k]th position, null past the last. */
    operator fun get(k: Int): Int? {
        while (filled <= k && step()) Unit
        return if (k < filled) positions[k] else null
    }

    /** The first [count] positions (all, if fewer). */
    fun prefix(count: Int): IntArray {
        if (count > 0) get(count - 1)
        return positions.copyOf(minOf(count, filled))
    }

    /** Those from the [k]th on, read as asked for. */
    fun from(k: Int): Sequence<Int> = generateSequence(k) { it + 1 }.map(::get).takeWhile { it != null }.map { it!! }

    /** Where the first position [predicate] takes is; the count of all if none. */
    fun indexOfFirst(predicate: (Int) -> Boolean): Int {
        var k = 0
        while (true) {
            val at = get(k) ?: return k
            if (predicate(at)) return k
            k++
        }
    }

    /** The first of [sorted] [match] takes, or [outside] if it sorts before that: a position either way, null for neither. */
    fun first(match: (Int) -> Boolean, outside: T?): Int? {
        val k = sorted.indices.firstOrNull { match(sorted[it]) }
        return when {
            k == null -> outside?.let(add)
            outside != null && before(keys[k], outside) -> add(outside)
            else -> sorted[k]
        }
    }

    private fun step(): Boolean {
        if (pendingTail == null && tail.hasNext()) pendingTail = tail.next()
        val t = pendingTail
        val at = when {
            sortedAt < sorted.size && (t == null || !before(keys[sortedAt], t)) -> sorted[sortedAt++]
            t != null -> add(t).also { pendingTail = null }
            else -> return false
        }
        if (filled == positions.size) positions = positions.copyOf(filled * 2)
        positions[filled++] = at
        return true
    }
}
