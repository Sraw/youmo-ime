/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

/**
 * The word ids a pass has seen, each once: a `HashSet<Int>` without its boxes. The decoder's
 * passes over the arcs from the start ran one a key, boxing an id per arc (S15 A8.3: a fifth of
 * the nine keys' bytes a key). Open addressing; [clear] is a new stamp, not a fill, so a pass
 * costs its words, not the table. One pass at a time: a decoder's, on its thread.
 */
internal class SeenWords {
    private var words = IntArray(INITIAL)
    private var stamps = IntArray(INITIAL)
    private var stamp = 1
    private var size = 0

    /** Forgets every word seen. */
    fun clear() {
        size = 0
        if (++stamp == Int.MAX_VALUE) {
            stamps.fill(0)
            stamp = 1
        }
    }

    /** Whether [word] is new to this pass; it is seen from now on. */
    fun add(word: Int): Boolean {
        if ((size + 1) * 2 > words.size) grow()
        return put(word)
    }

    private fun put(word: Int): Boolean {
        val mask = words.size - 1
        val h = word * SPREAD
        var i = (h xor (h ushr Short.SIZE_BITS)) and mask
        while (stamps[i] == stamp) {
            if (words[i] == word) return false
            i = (i + 1) and mask
        }
        words[i] = word
        stamps[i] = stamp
        size++
        return true
    }

    private fun grow() {
        val oldWords = words
        val oldStamps = stamps
        words = IntArray(oldWords.size * 2)
        stamps = IntArray(oldWords.size * 2)
        val was = stamp
        stamp = 1
        size = 0
        for (i in oldWords.indices) if (oldStamps[i] == was) put(oldWords[i])
    }

    private companion object {
        const val INITIAL = 64

        // Fibonacci hashing: ids near each other (a reading's words) land apart
        const val SPREAD = -0x61c88647
    }
}
