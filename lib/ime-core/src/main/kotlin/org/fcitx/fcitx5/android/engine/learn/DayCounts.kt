/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

/**
 * Counts by typing day, oldest first: a few days each, for tens of thousands of rows, so two
 * arrays rather than a map of boxed days.
 */
class DayCounts {
    private var days = IntArray(2)
    private var counts = IntArray(2)

    var size = 0
        private set

    fun isEmpty() = size == 0

    fun day(i: Int) = days[i]
    fun count(i: Int) = counts[i]

    /** The last day counted, or [Int.MIN_VALUE] for none. */
    val lastDay get() = if (size == 0) Int.MIN_VALUE else days[size - 1]

    /** Adds [n] (less than none takes back, never below none) to [day]'s count; a day of none is dropped. */
    fun add(day: Int, n: Int) {
        var i = size - 1
        while (i >= 0 && days[i] > day) i--
        if (i >= 0 && days[i] == day) {
            counts[i] = (counts[i] + n).coerceIn(0, Short.MAX_VALUE.toInt())
            if (counts[i] == 0) removeAt(i)
            return
        }
        if (n <= 0) return
        if (size == days.size) {
            days = days.copyOf(size * 2)
            counts = counts.copyOf(size * 2)
        }
        val at = i + 1
        System.arraycopy(days, at, days, at + 1, size - at)
        System.arraycopy(counts, at, counts, at + 1, size - at)
        days[at] = day
        counts[at] = n.coerceAtMost(Short.MAX_VALUE.toInt())
        size++
    }

    /** The sum of the counts of the days from [from] on. */
    fun sumFrom(from: Int): Int {
        var sum = 0
        for (i in size - 1 downTo 0) {
            if (days[i] < from) break
            sum += counts[i]
        }
        return sum
    }

    /** Drops the days before [from]: what they counted. */
    fun dropBefore(from: Int): Int {
        var n = 0
        while (n < size && days[n] < from) n++
        if (n == 0) return 0
        var sum = 0
        for (i in 0 until n) sum += counts[i]
        System.arraycopy(days, n, days, 0, size - n)
        System.arraycopy(counts, n, counts, 0, size - n)
        size -= n
        return sum
    }

    private fun removeAt(i: Int) {
        System.arraycopy(days, i + 1, days, i, size - i - 1)
        System.arraycopy(counts, i + 1, counts, i, size - i - 1)
        size--
    }

    fun copy() = DayCounts().also { c ->
        c.days = days.copyOf(maxOf(2, size))
        c.counts = counts.copyOf(maxOf(2, size))
        c.size = size
    }

    override fun equals(other: Any?): Boolean {
        if (other !is DayCounts || other.size != size) return false
        for (i in 0 until size) if (days[i] != other.days[i] || counts[i] != other.counts[i]) return false
        return true
    }

    override fun hashCode(): Int {
        var h = size
        for (i in 0 until size) h = (h * 31 + days[i]) * 31 + counts[i]
        return h
    }

    override fun toString() = (0 until size).joinToString(",", "[", "]") { "${days[it]}:${counts[it]}" }

    companion object {
        fun of(vararg dayCounts: Pair<Int, Int>) = DayCounts().also { c -> dayCounts.forEach { (d, n) -> c.add(d, n) } }
    }
}
