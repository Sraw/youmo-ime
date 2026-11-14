/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.Vocabulary
import java.util.BitSet

/**
 * Which characters 生僻字屏蔽 holds back (dev/design/061-rare-char-block.md §3.1): asked by code
 * point, never by `Char`, so a character beyond the basic plane (𤝰, two chars) is one. It knows
 * no groups, only code points; [RareChoice.gate] makes the one the user chose, a test any it likes.
 * The basic plane is a bitmap made once (8 KB); beyond it [beyond] is asked, a character there
 * being rare in any text.
 */
class CharGate private constructor(private val plane0: BitSet, private val beyond: (Int) -> Boolean, val none: Boolean) {

    fun holds(codePoint: Int): Boolean = if (codePoint < PLANE) plane0[codePoint] else beyond(codePoint)

    /** Whether any character of [text] is held back. */
    fun holds(text: CharSequence): Boolean {
        if (none) return false
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (holds(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    /** The characters of [text] held back, each once, in its order: what a user word's row names (§5.4). */
    fun heldIn(text: CharSequence): List<String> {
        if (none) return emptyList()
        val out = LinkedHashSet<String>()
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (holds(cp)) out += String(Character.toChars(cp))
            i += Character.charCount(cp)
        }
        return out.toList()
    }

    companion object {
        private const val PLANE = 0x10000

        /** Off: nothing held back. */
        val NONE = CharGate(BitSet(), { false }, none = true)

        /** One holding back what [holds] says, the basic plane asked once a code point now. */
        fun of(holds: (Int) -> Boolean): CharGate = of(BitSet(PLANE).apply { for (cp in 0 until PLANE) if (holds(cp)) set(cp) }, holds)

        /** One of [plane0] made already, [beyond] asked past it. */
        internal fun of(plane0: BitSet, beyond: (Int) -> Boolean) = CharGate(plane0, beyond, none = false)
    }
}

/**
 * Which of the dictionary's words a [gate] holds back, by id: a bit each, made once by reading
 * every word's chars in place (395,036 of them; no string made of any), for the decoders to ask
 * as they ask what the user blocked ([UserModel.blocked]). Words past the dictionary's ids are
 * the user model's to ask of [gate] by their text.
 */
class HeldWords private constructor(val gate: CharGate, private val words: BitSet) {

    /** Whether the dictionary's word [id] has a character held back. */
    fun has(id: Int): Boolean = words[id]

    /** How many of the dictionary's words are held back. */
    val count: Int get() = words.cardinality()

    companion object {
        val NONE = HeldWords(CharGate.NONE, BitSet())

        fun of(gate: CharGate, vocabulary: Vocabulary): HeldWords {
            if (gate.none) return NONE
            val words = BitSet(vocabulary.size)
            for (id in 0 until vocabulary.size) {
                val n = vocabulary.length(id)
                var i = 0
                while (i < n) {
                    val c = vocabulary.char(id, i)
                    // a surrogate pair is one character: asked as its code point
                    val pair = Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(vocabulary.char(id, i + 1))
                    val cp = if (pair) Character.toCodePoint(c, vocabulary.char(id, i + 1)) else c.code
                    if (gate.holds(cp)) {
                        words.set(id)
                        break
                    }
                    i += Character.charCount(cp)
                }
            }
            return HeldWords(gate, words)
        }
    }
}
