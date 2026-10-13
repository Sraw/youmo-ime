/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/**
 * Which letters each of the nine keys' 2 to 9 types, ü as v: as on a phone (`2` abc … `9` wxyz)
 * unless the user set them otherwise, at most [MAX] a key. A letter may be on more than one key,
 * then a syllable has as many ways to be typed; one on no key cannot be typed.
 */
class NineKeyLetters private constructor(private val keys: List<String>) {

    /** The letters [digit] types; none for what is no key of them. */
    fun letters(digit: Char): String = keys.getOrElse(digit - '2') { "" }

    /** The keys typing [c], in order; none for a letter on no key. */
    fun keysOf(c: Char): String = buildString { keys.forEachIndexed { k, letters -> if (c in letters) append('2' + k) } }

    /** The first key typing [c], to stand for it in the input; [c] itself where none does. */
    fun key(c: Char): Char {
        // the keys hold a to z alone (normalize), and a digit, most of what is read, is none of them
        if (c !in 'a'..'z') return c
        return keys.indexOfFirst { c in it }.let { if (it < 0) c else '2' + it }
    }

    /** Whether [key], a digit or a letter typed as itself (upper case or not), shares a key with the letter [c]. */
    fun sameKey(key: Char, c: Char): Boolean =
        if (key in '2'..'9') c in letters(key) else keys.any { c in it && key.lowercaseChar() in it }

    /**
     * Each way [spelling] is typed, its letters' keys in turn, at most [TYPINGS]: past that a
     * layout with a letter on many keys would make thousands of a long syllable. None where a
     * letter is on no key.
     */
    fun typings(spelling: String): List<String> {
        var out = listOf("")
        for (c in spelling) {
            val on = keysOf(c)
            if (on.isEmpty()) return emptyList()
            out = out.flatMap { typed -> on.map { typed + it } }.take(TYPINGS)
        }
        return out
    }

    /** The letters a to z on no key. */
    val missing: String get() = ('a'..'z').filter { c -> keys.none { c in it } }.joinToString("")

    val isStandard: Boolean get() = keys == STANDARD_KEYS

    /** As kept in the app's preferences, read back by [parse]. */
    fun serialize(): String = if (isStandard) "" else keys.joinToString(",")

    override fun equals(other: Any?) = other is NineKeyLetters && other.keys == keys

    override fun hashCode() = keys.hashCode()

    override fun toString() = keys.joinToString(",")

    companion object {
        /** The most letters a key types. */
        const val MAX = 5

        private const val TYPINGS = 64

        private val STANDARD_KEYS = listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")

        val STANDARD = NineKeyLetters(STANDARD_KEYS)

        /**
         * The letters of [keys], 2's first: each as [normalize] has it, a key not given as on a
         * phone. The keys of no key left are those on a phone too.
         */
        fun of(keys: List<String>): NineKeyLetters {
            val all = STANDARD_KEYS.indices.map { k -> keys.getOrNull(k)?.let(::normalize) ?: STANDARD_KEYS[k] }
            return if (all == STANDARD_KEYS) STANDARD else NineKeyLetters(all)
        }

        /** [text] as [serialize] wrote it; what is not, as on a phone. */
        fun parse(text: String): NineKeyLetters {
            val keys = text.split(',')
            return if (keys.size == STANDARD_KEYS.size) of(keys) else STANDARD
        }

        /** [typed] as a key's letters: a to z alone, lower case, each once, at most [MAX]. */
        fun normalize(typed: String): String =
            typed.lowercase().filter { it in 'a'..'z' }.toCharArray().distinct().take(MAX).joinToString("")
    }
}
