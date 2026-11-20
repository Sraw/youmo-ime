/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

/** Where in the input each place of a preedit is, so a tap on the preedit moves the input cursor and back. */
internal class PreeditPlaces private constructor(
    private val restAt: Int,
    private val inInput: IntArray,
) {
    fun inputAt(at: Int, readFrom: Int) = if (at <= restAt) readFrom else inInput[minOf(at - restAt, inInput.size - 1)]

    /** Whether some place of the preedit is input place [place]: one inside a piece shown whole is none. */
    fun shows(place: Int) = place in inInput

    /** Where in the preedit input place [place] is: before a space between syllables, not after. */
    fun preeditAt(place: Int): Int {
        val first = inInput.indexOfFirst { it >= place }
        return restAt + if (first < 0) inInput.size - 1 else first
    }

    class Builder {
        private val text = StringBuilder()
        private var places = IntArray(INITIAL)
        private var size = 0

        val rest get() = text.toString()

        /**
         * [shown] for keys [from]..[to]: a character a key, spread evenly (rounded down) where spelt out; a shown space is a
         * zero-width place. [whole]: every character at [from], so the cursor goes before or after the keys, never among them.
         */
        fun add(shown: String, from: Int, to: Int, whole: Boolean = false): Builder {
            text.append(shown)
            for (j in shown.indices) put(if (whole) from else from + (to - from) * j / shown.length)
            return this
        }

        fun places(picked: Int, end: Int): PreeditPlaces {
            put(end)
            return PreeditPlaces(picked, places.copyOf(size))
        }

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
