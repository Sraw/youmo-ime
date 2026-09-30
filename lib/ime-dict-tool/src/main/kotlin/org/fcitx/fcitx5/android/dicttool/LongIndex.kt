/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

/**
 * Non-negative longs to dense indices 0, 1, 2 ... in the order they were added, for keeping
 * what belongs to each in plain arrays: tens of millions of n-grams in boxed maps would not fit
 * in the heap. An open-addressing table, at most [LOAD] full.
 */
class LongIndex(expected: Int = 16) {

    private var slots = IntArray(capacityFor(expected)) { EMPTY }
    private var keys = LongArray(maxOf(expected, 1))

    var size = 0
        private set

    /** The index of [key], or -1. */
    fun indexOf(key: Long): Int {
        var slot = slot(key, slots.size)
        while (true) {
            val index = slots[slot]
            if (index == EMPTY) return -1
            if (keys[index] == key) return index
            slot = (slot + 1) and (slots.size - 1)
        }
    }

    /** The index of [key], added last if it was not there. */
    fun add(key: Long): Int {
        require(key >= 0) { "negative key $key" }
        var slot = slot(key, slots.size)
        while (true) {
            val index = slots[slot]
            if (index == EMPTY) break
            if (keys[index] == key) return index
            slot = (slot + 1) and (slots.size - 1)
        }
        if (size == keys.size) keys = keys.copyOf(keys.size * 2)
        keys[size] = key
        slots[slot] = size
        size++
        if (size > slots.size * LOAD) grow()
        return size - 1
    }

    fun keyAt(index: Int): Long = keys[index]

    private fun grow() {
        val bigger = IntArray(slots.size * 2) { EMPTY }
        for (index in 0 until size) {
            var slot = slot(keys[index], bigger.size)
            while (bigger[slot] != EMPTY) slot = (slot + 1) and (bigger.size - 1)
            bigger[slot] = index
        }
        slots = bigger
    }

    private companion object {
        const val EMPTY = -1
        const val LOAD = 0.6
        const val GOLDEN = -0x61c8864680b583ebL // 2^64 / φ

        fun capacityFor(expected: Int) = Integer.highestOneBit(maxOf((expected / LOAD).toInt(), 2)) shl 1

        // the high bits of a Fibonacci hash: keys are ids packed side by side, their low bits alone cluster
        fun slot(key: Long, size: Int) = ((key * GOLDEN) ushr (Long.SIZE_BITS - Integer.numberOfTrailingZeros(size))).toInt()
    }
}

/** Grows as indices are handed out, for a [LongIndex]'s values. */
class IntColumn(capacity: Int = 16) {
    private var values = IntArray(maxOf(capacity, 1))

    operator fun get(index: Int) = if (index < values.size) values[index] else 0

    operator fun set(index: Int, value: Int) {
        if (index >= values.size) values = values.copyOf(maxOf(values.size * 2, index + 1))
        values[index] = value
    }

    fun add(index: Int, delta: Int) = set(index, get(index) + delta)
}

/** As [IntColumn], of doubles. */
class DoubleColumn(capacity: Int = 16) {
    private var values = DoubleArray(maxOf(capacity, 1))

    operator fun get(index: Int) = if (index < values.size) values[index] else 0.0

    operator fun set(index: Int, value: Double) {
        if (index >= values.size) values = values.copyOf(maxOf(values.size * 2, index + 1))
        values[index] = value
    }

    fun add(index: Int, delta: Double) = set(index, get(index) + delta)
}
