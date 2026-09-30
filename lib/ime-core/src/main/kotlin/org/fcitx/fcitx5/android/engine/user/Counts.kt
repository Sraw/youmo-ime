/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

/**
 * Counts by a long key, in open addressing: the decoder looks one up for nearly every word it
 * scores, and a HashMap would box each key.
 */
internal class Counts {
    private var keys = LongArray(INITIAL) { EMPTY }
    private var values = FloatArray(INITIAL)

    var size = 0
        private set

    operator fun get(key: Long): Float {
        val mask = keys.size - 1
        var i = slot(key, mask)
        while (true) {
            val k = keys[i]
            if (k == key) return values[i]
            if (k == EMPTY) return 0f
            i = (i + 1) and mask
        }
    }

    fun add(key: Long, count: Float) {
        require(key != EMPTY) { "key $key" }
        if ((size + 1) * 4 > keys.size * 3) resize(keys.size * 2)
        val mask = keys.size - 1
        var i = slot(key, mask)
        while (keys[i] != EMPTY && keys[i] != key) i = (i + 1) and mask
        if (keys[i] == EMPTY) {
            keys[i] = key
            size++
        }
        values[i] += count
    }

    fun forEach(visit: (Long, Float) -> Unit) {
        for (i in keys.indices) if (keys[i] != EMPTY) visit(keys[i], values[i])
    }

    /** Multiplies every count by [factor], dropping those that fall below [min]. */
    fun scale(factor: Float, min: Float) {
        val oldKeys = keys
        val oldValues = values
        keys = LongArray(oldKeys.size) { EMPTY }
        values = FloatArray(oldKeys.size)
        size = 0
        for (i in oldKeys.indices) {
            val v = oldValues[i] * factor
            if (oldKeys[i] != EMPTY && v >= min) add(oldKeys[i], v)
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
