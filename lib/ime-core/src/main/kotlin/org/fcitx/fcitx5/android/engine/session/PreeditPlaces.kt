/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

/**
 * Where in the input each place between the characters of a preedit is: the text picked, then the
 * rest of the input as shown ([Builder]), syllables with spaces between them, or spelt out. A tap
 * on the preedit puts the cursor in the input by it, and the cursor shows in the preedit by it.
 */
internal class PreeditPlaces private constructor(
    private val restAt: Int, // where the text picked ends in the preedit
    private val inInput: IntArray, // each place in the rest shown, and its end
) {
    /** Where in the input the place [at] characters into the preedit is; in the text picked, [readFrom]. */
    fun inputAt(at: Int, readFrom: Int) = if (at <= restAt) readFrom else inInput[minOf(at - restAt, inInput.size - 1)]

    /** Where in the preedit the place [place] of the input is: before a space between syllables, not after. */
    fun preeditAt(place: Int): Int {
        var g = 0
        while (g < inInput.size - 1 && inInput[g] < place) g++
        return restAt + g
    }

    /** The rest of a preedit, put together a piece of the input at a time. */
    class Builder {
        private val text = StringBuilder()
        private var places = IntArray(INITIAL)
        private var size = 0

        val rest get() = text.toString()

        /**
         * [shown] for the keys from [from] to [to] of the input: a key a character as typed,
         * spread evenly where spelt out (双拼, the nine keys' letters), rounded down so that no
         * character but the first of the next part is at [to]; a space shown between keys is
         * from and to the same place.
         */
        fun add(shown: String, from: Int, to: Int): Builder {
            text.append(shown)
            for (j in shown.indices) put(from + (to - from) * j / shown.length)
            return this
        }

        /** After the text picked, [picked] characters of it, the input ending at [end]. */
        fun places(picked: Int, end: Int): PreeditPlaces {
            put(end)
            return PreeditPlaces(picked, places.copyOf(size))
        }

        // on every key: no boxing
        private fun put(place: Int) {
            if (size == places.size) places = places.copyOf(size * 2)
            places[size++] = place
        }
    }

    companion object {
        val NONE = PreeditPlaces(0, IntArray(1))
        private const val INITIAL = 64
    }
}
