/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/**
 * The syllables a piece of typed input may stand for, each with [flags] saying how far the
 * typing strays from the syllable's spelling. Sorted by syllable id. Instances are built once
 * per [PinyinSegmenter] and shared by every graph it makes.
 *
 * The flags only describe; what each costs is the decoder's decision, tuned on the evaluation set.
 */
class SyllableMatches internal constructor(private val syllables: IntArray, private val flags: IntArray) {

    val size: Int get() = syllables.size

    fun syllable(i: Int): Int = syllables[i]

    fun flags(i: Int): Int = flags[i]

    /** Whether [other] holds the same syllables with the same flags. */
    internal fun sameAs(other: SyllableMatches): Boolean =
        this === other || syllables.contentEquals(other.syllables) && flags.contentEquals(other.flags)

    /** These with [flag] on each. */
    internal fun with(flag: Int) = SyllableMatches(syllables, IntArray(size) { flags[it] or flag })

    // byFlags's, made at the first ask: a reading walks an edge's matches at each node it tries
    @Volatile
    private var byFlags: IntArray? = null

    /** The indexes, exact ones first: by [flags], ties in syllable order. */
    internal fun byFlags(): IntArray = byFlags ?: (0 until size).sortedBy { flags[it] }.toIntArray().also { byFlags = it }

    /** @return the index of [syllable], or -1 */
    fun indexOf(syllable: Int): Int = syllables.binarySearch(syllable).let { if (it >= 0) it else -1 }

    override fun toString(): String = (0 until size).joinToString(" ") { i ->
        Syllables.spelling(syllables[i]) + describe(flags[i])
    }

    companion object {
        /** Spelt with the other half of an enabled [Fuzzy] pair. */
        const val FUZZY = 1

        /** A common slip: `gn` for `ng`, `on` for `ong`, `v` for the ü of ju, qu, xu, yu. */
        const val TYPO = 2

        /** The typing is only the start of the syllable (简拼 `zh`, or `zho` still being typed). */
        const val COMPLETION = 4

        /** A letter slipped onto the key next to it: `nihap` for nihao. */
        const val NEIGHBOUR = 8

        /** 双拼's key read as an initial alone where it and the next make a syllable: 简拼 in the middle (ShuangpinSegmenter). */
        const val ABBREV = 16

        internal val EMPTY = SyllableMatches(IntArray(0), IntArray(0))

        private fun describe(flags: Int) = buildString {
            if (flags and FUZZY != 0) append('~')
            if (flags and TYPO != 0) append('!')
            if (flags and COMPLETION != 0) append('…')
            if (flags and NEIGHBOUR != 0) append('?')
            if (flags and ABBREV != 0) append('^')
        }
    }
}
