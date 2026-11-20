/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

/** A table's candidates in [sorted] order with the merged dictionaries' longer-code words ([tail]) merged in lazily: sorting ~80,000 of them at every 五笔 key made 22 MB of garbage a key. */
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

    operator fun get(k: Int): Int? {
        while (filled <= k && step()) Unit
        return if (k < filled) positions[k] else null
    }

    fun prefix(count: Int): IntArray {
        if (count > 0) get(count - 1)
        return positions.copyOf(minOf(count, filled))
    }

    fun from(k: Int): Sequence<Int> = generateSequence(k) { it + 1 }.map(::get).takeWhile { it != null }.map { it!! }

    fun indexOfFirst(predicate: (Int) -> Boolean): Int {
        var k = 0
        while (true) {
            val at = get(k) ?: return k
            if (predicate(at)) return k
            k++
        }
    }

    /** The first of [sorted] [match] takes, or [outside] if it sorts before that; null for neither. */
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
