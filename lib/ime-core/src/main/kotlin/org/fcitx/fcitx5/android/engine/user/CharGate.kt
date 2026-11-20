/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.Vocabulary
import java.util.BitSet

/** Which code points 生僻字屏蔽 holds back (never by `Char`: 𤝰 is two): the BMP as a bitmap made once (8 KB), [beyond] asked past it. [RareChoice.gate] makes the user's. */
class CharGate private constructor(private val plane0: BitSet, private val beyond: (Int) -> Boolean, val none: Boolean) {

    fun holds(codePoint: Int): Boolean = if (codePoint < PLANE) plane0[codePoint] else beyond(codePoint)

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

    /** The characters of [text] held back, each once, in its order: what a user word's row names. */
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

        val NONE = CharGate(BitSet(), { false }, none = true)

        fun of(holds: (Int) -> Boolean): CharGate = of(BitSet(PLANE).apply { for (cp in 0 until PLANE) if (holds(cp)) set(cp) }, holds)

        internal fun of(plane0: BitSet, beyond: (Int) -> Boolean) = CharGate(plane0, beyond, none = false)
    }
}

/** Which dictionary words a [gate] holds back, a bit per id, made once reading chars in place (no strings); words past the dictionary are asked of [gate] by text. */
class HeldWords private constructor(val gate: CharGate, private val words: BitSet) {

    fun has(id: Int): Boolean = words[id]

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
