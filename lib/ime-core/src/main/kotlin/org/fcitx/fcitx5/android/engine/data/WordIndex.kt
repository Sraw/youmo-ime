/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

/**
 * Text to word id, for the words of [vocabulary] below [size] (the model's): what the file
 * keeps is id to text only, since typing goes by readings. An open-addressing table of ids,
 * made when first asked for (2 MiB and some tens of milliseconds for the model's 270 000 words),
 * hashing the vocabulary's chars where they lie rather than making strings of them.
 */
class WordIndex(private val vocabulary: Vocabulary, private val size: Int) {

    // id + 1 of the word hashed there, 0 for none; at most two thirds full
    private val slots = IntArray(Integer.highestOneBit(maxOf(size, 1) * 3 / 2) shl 1)
    private val mask = slots.size - 1
    private val shift = Integer.SIZE - Integer.numberOfTrailingZeros(slots.size)

    init {
        for (id in 0 until size) {
            val length = vocabulary.length(id)
            var slot = hash(length) { vocabulary.char(id, it) }
            while (slots[slot] != 0) {
                // a word twice keeps its first id, as the model's lower id is its likelier
                if (same(slots[slot] - 1, length) { vocabulary.char(id, it) }) break
                slot = (slot + 1) and mask
            }
            if (slots[slot] == 0) slots[slot] = id + 1
        }
    }

    /** The id of [text]'s chars from [from] to [to], or [NgramModel.NO_WORD]. */
    fun find(text: CharSequence, from: Int = 0, to: Int = text.length): Int {
        val length = to - from
        var slot = hash(length) { text[from + it] }
        while (true) {
            val id = slots[slot] - 1
            if (id < 0) return NgramModel.NO_WORD
            if (same(id, length) { text[from + it] }) return id
            slot = (slot + 1) and mask
        }
    }

    private inline fun same(id: Int, length: Int, char: (Int) -> Char): Boolean {
        if (vocabulary.length(id) != length) return false
        for (i in 0 until length) if (vocabulary.char(id, i) != char(i)) return false
        return true
    }

    private inline fun hash(length: Int, char: (Int) -> Char): Int {
        var h = length
        for (i in 0 until length) h = h * HASH_MULTIPLIER + char(i).code
        // the high bits of a Fibonacci hash pick the slot: CJK chars are consecutive code points,
        // and the low bits of h alone put words in runs that linear probing makes longer
        return (h * GOLDEN) ushr shift
    }

    private companion object {
        const val HASH_MULTIPLIER = 31
        const val GOLDEN = -0x61c88647 // 2^32 / φ
    }
}
