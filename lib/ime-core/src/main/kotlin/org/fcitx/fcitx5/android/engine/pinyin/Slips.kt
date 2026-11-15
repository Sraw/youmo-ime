/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/**
 * An edge's matches read with one key slipped from the key next to it (061-neighbour-correction):
 * what each costs ([SlipCost], log10) and which key it took for which, [at] places into the edge
 * the key [meant] in place of the one typed. Learning files a word so read under the keys meant
 * (§8.2, N1).
 */
class Slips(private val cost: FloatArray, private val keys: IntArray) {
    init {
        require(cost.size == keys.size)
    }

    val size: Int get() = cost.size

    /** What the [i]th match costs. */
    fun cost(i: Int): Float = cost[i]

    /** How many places into its edge the [i]th match's slipped key is. */
    fun at(i: Int): Int = keys[i] ushr CHAR_BITS

    /** The key the [i]th match reads in place of the one typed. */
    fun meant(i: Int): Char = (keys[i] and CHAR_MASK).toChar()

    override fun equals(other: Any?) = other is Slips && cost.contentEquals(other.cost) && keys.contentEquals(other.keys)

    override fun hashCode() = cost.contentHashCode() * 31 + keys.contentHashCode()

    /** Built a match at a time, in the order of the edge's matches. */
    class Builder {
        // none until the first slip: most runs a segmenter builds for have none (the work of keys tapped on target)
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
