/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.pinyin.SyllableMatches

/**
 * The path [LatticeBuilder.collect] is on: syllables and tones typed after them (0 none), [length] and
 * [toned], and how many a lone 双拼 key read as an initial ([SyllableMatches.ABBREV]) or a key read as its
 * neighbour ([SyllableMatches.NEIGHBOUR]): neither is the input as typed. [alone] counts the first kind
 * only, over which only user-typed words are read ([UserWords.typed]).
 */
internal class Path {
    var syllables = IntArray(PATH)
    var tones = IntArray(PATH)
    var length = 0
    var toned = 0
    var abbreviated = 0
        private set
    var alone = 0
        private set

    fun clear() {
        length = 0
        toned = 0
        counted(0)
    }

    /** Both counts as one number, for [counted] to restore. */
    fun counts() = abbreviated or (alone shl ALONE_SHIFT)

    fun counted(counts: Int) {
        abbreviated = counts and (1 shl ALONE_SHIFT) - 1
        alone = counts ushr ALONE_SHIFT
    }

    fun enter(syllable: Int, tone: Int, flags: Int) {
        if (length == syllables.size) {
            syllables = syllables.copyOf(2 * length)
            tones = tones.copyOf(2 * length)
        }
        syllables[length] = syllable
        tones[length] = tone
        length++
        if (tone > 0) toned++
        count(flags, 1)
    }

    fun leave(tone: Int, flags: Int) {
        length--
        if (tone > 0) toned--
        count(flags, -1)
    }

    fun count(flags: Int, by: Int) {
        if (flags and (SyllableMatches.ABBREV or SyllableMatches.NEIGHBOUR) != 0) abbreviated += by
        if (flags and SyllableMatches.ABBREV != 0) alone += by
    }

    /** [penalty] where a tone typed on the path is one a character of the word ([chars] long, [charAt]) lacks by [allows]. */
    inline fun toneCost(chars: Int, allows: (codePoint: Int, syllable: Int, tone: Int) -> Boolean, penalty: Float, charAt: (Int) -> Char): Float {
        // one code point per syllable, or no telling which character a tone is of
        var count = 0
        var at = 0
        while (at < chars) {
            at += if (charAt(at).isHighSurrogate() && at + 1 < chars && charAt(at + 1).isLowSurrogate()) 2 else 1
            count++
        }
        if (count != length) return 0f
        at = 0
        for (k in 0 until length) {
            val c = charAt(at)
            val pair = c.isHighSurrogate() && at + 1 < chars && charAt(at + 1).isLowSurrogate()
            val cp = if (pair) Character.toCodePoint(c, charAt(at + 1)) else c.code
            if (tones[k] > 0 && !allows(cp, syllables[k], tones[k])) return penalty
            at += if (pair) 2 else 1
        }
        return 0f
    }

    private companion object {
        // initial room for the syllables
        const val PATH = 16
        // counts: lone-key count above this bit; paths are far shorter than 2^15 keys
        const val ALONE_SHIFT = 15
    }
}
