/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

/**
 * Counts (or other floats) by a long key, in open addressing: the decoder looks one up for
 * nearly every word it scores, and a HashMap would box each key.
 */
internal class Counts {
    private var keys = LongArray(INITIAL) { EMPTY }
    private var values = FloatArray(INITIAL)

    var size = 0
        private set

    operator fun get(key: Long): Float = getOrDefault(key, 0f)

    /** The count of [key], or [default] if it has none. */
    fun getOrDefault(key: Long, default: Float): Float {
        val mask = keys.size - 1
        var i = slot(key, mask)
        while (true) {
            val k = keys[i]
            if (k == key) return values[i]
            if (k == EMPTY) return default
            i = (i + 1) and mask
        }
    }

    fun add(key: Long, count: Float) {
        // the slot first: taking it may resize, replacing values
        val i = slotOf(key)
        values[i] += count
    }

    operator fun set(key: Long, count: Float) {
        val i = slotOf(key)
        values[i] = count
    }

    // the slot of key, taken for it if it has none
    private fun slotOf(key: Long): Int {
        require(key != EMPTY) { "key $key" }
        if ((size + 1) * 4 > keys.size * 3) resize(keys.size * 2)
        val mask = keys.size - 1
        var i = slot(key, mask)
        while (keys[i] != EMPTY && keys[i] != key) i = (i + 1) and mask
        if (keys[i] == EMPTY) {
            keys[i] = key
            size++
        }
        return i
    }

    /** Whether a key [test] says so has a count, going no further than the first. */
    fun any(test: (Long) -> Boolean): Boolean {
        for (k in keys) if (k != EMPTY && test(k)) return true
        return false
    }

    fun forEach(visit: (Long, Float) -> Unit) {
        for (i in keys.indices) if (keys[i] != EMPTY) visit(keys[i], values[i])
    }

    /** Multiplies every count by [factor], dropping those that fall below [min]. */
    fun scale(factor: Float, min: Float) = rebuild { _, v -> (v * factor).takeIf { it >= min } }

    /** Drops the counts whose key [drop] says; seldom done, so it is a rebuild. */
    fun removeIf(drop: (Long) -> Boolean) = rebuild { key, v -> v.takeUnless { drop(key) } }

    /** Each count as [map] makes it anew, or dropped where it gives null. */
    private inline fun rebuild(map: (Long, Float) -> Float?) {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size) { EMPTY }
        values = FloatArray(oldKeys.size)
        size = 0
        for (i in oldKeys.indices) {
            if (oldKeys[i] == EMPTY) continue
            map(oldKeys[i], oldValues[i])?.let { add(oldKeys[i], it) }
        }
    }

    private fun resize(capacity: Int) {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(capacity) { EMPTY }
        values = FloatArray(capacity)
        size = 0
        for (i in oldKeys.indices) if (oldKeys[i] != EMPTY) add(oldKeys[i], oldValues[i])
    }

    private fun slot(key: Long, mask: Int): Int {
        val h = key * -0x61c8864680b583ebL // the golden ratio: spreads keys differing in low bits
        return (h ushr 32).toInt() and mask
    }

    private companion object {
        const val INITIAL = 64
        const val EMPTY = Long.MIN_VALUE
    }
}
