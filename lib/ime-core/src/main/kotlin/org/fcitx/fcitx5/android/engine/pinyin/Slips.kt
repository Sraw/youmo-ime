/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/** An edge's matches read with one key slipped: each one's cost ([SlipCost], log10) and which key [at] it reads as [meant]. Learning files such words under the keys meant. */
class Slips(private val cost: FloatArray, private val keys: IntArray) {
    init {
        require(cost.size == keys.size)
    }

    val size: Int get() = cost.size

    fun cost(i: Int): Float = cost[i]

    /** Places into the edge of the slipped key. */
    fun at(i: Int): Int = keys[i] ushr CHAR_BITS

    fun meant(i: Int): Char = (keys[i] and CHAR_MASK).toChar()

    override fun equals(other: Any?) = other is Slips && cost.contentEquals(other.cost) && keys.contentEquals(other.keys)

    override fun hashCode() = cost.contentHashCode() * 31 + keys.contentHashCode()

    /** In edge-match order. */
    class Builder {
        // empty until the first slip: most runs have none
        private var cost = FloatArray(0)
        private var keys = IntArray(0)
        var size = 0
            private set

        fun add(cost: Float, at: Int, meant: Char) {
            if (size == this.cost.size) {
                this.cost = this.cost.copyOf(maxOf(INITIAL, size * 2))
                keys = keys.copyOf(maxOf(INITIAL, size * 2))
            }
            this.cost[size] = cost
            keys[size++] = (at shl CHAR_BITS) or meant.code
        }

        fun build() = Slips(cost.copyOf(size), keys.copyOf(size))
    }

    private companion object {
        const val CHAR_BITS = 16
        const val CHAR_MASK = 0xFFFF
        const val INITIAL = 8
    }
}
