/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

/**
 * Which letters each key of a keyboard of letter groups types, ü as v, each key standing in the
 * input for its code of [codes]. The nine keys' 2 to 9 (codes `23456789`, 1 the separator): as on
 * a phone (`2` abc … `9` wxyz) unless the user set them otherwise, at most [MAX] a key. A letter
 * may be on more than one key, then a syllable has as many ways to be typed; one on no key cannot
 * be typed. The grouped keyboards ([G14], [G18], [NINE_SP]) are fixed, their codes private use characters no other key types.
 *
 * @property codes each key's code, in the keys' order
 * @property separator what typed splits syllables on these keys, null for none (the grouped
 * keyboards have a key of their own for it, an apostrophe)
 */
class NineKeyLetters private constructor(
    private val keys: List<String>,
    val codes: String = DIGITS,
    val separator: Char? = '1',
) {

    /** Whether the codes are a grouped keyboard's private use codes ([G14], [G18], [NINE_SP]), not the nine keys' digits: shown as letters. */
    val isGrouped: Boolean = codes != DIGITS

    /** The letters [code] types; none for what is no key of them. */
    fun letters(code: Char): String = codes.indexOf(code).let { if (it < 0) "" else keys[it] }

    /**
     * How far a tap at [at] across a key (0 its left edge, 1 its right) goes against its letter
     * [index] (of [letters]), 0 on that letter's half to 1 at the far edge. Two letters a key (the
     * 14 keys', the only ones tapped with a place: Keyboard.TAPS): the first is the left half, any
     * other the right. One rule for the segmenter, the syllable bar and Keyboard.typedLetter.
     */
    fun against(index: Int, at: Float): Float {
        val lean = 2 * (at - HALF) // -1 the left edge, 1 the right
        return if (index == 0) maxOf(0f, lean) else maxOf(0f, -lean)
    }

    /** Whether [c] is one of the keys' codes. */
    fun isCode(c: Char): Boolean = c in codes

    /** The keys typing [c], in order; none for a letter on no key. */
    fun keysOf(c: Char): String = buildString { keys.forEachIndexed { k, letters -> if (c in letters) append(codes[k]) } }

    /** The first key typing [c], to stand for it in the input; [c] itself where none does. */
    fun key(c: Char): Char {
        // the keys hold a to z alone (normalize), 双拼's ; besides on the grouped keyboards, and a
        // digit, most of what is read, is none of them
        if (c !in 'a'..'z' && c != ';') return c
        return keys.indexOfFirst { c in it }.let { if (it < 0) c else codes[it] }
    }

    /** Whether [key], a code or a letter typed as itself (upper case or not), shares a key with the letter [c]. */
    fun sameKey(key: Char, c: Char): Boolean =
        if (isCode(key)) c in letters(key) else keys.any { c in it && key.lowercaseChar() in it }

    /**
     * [text] as the user may read it: each code its key's first letter, the rest lower case. What
     * is typed and cannot be read is shown and committed so, a grouped key's code never reaching
     * the user's text (a 14-key UI alone reads as `u`). On the nine keys the digits stay digits.
     */
    fun asTyped(text: String): String = buildString(text.length) {
        for (c in text) {
            val k = if (isGrouped) codes.indexOf(c) else -1
            // String.lowercase, as the nine keys' asTyped had it (it differs from Char's for İ)
            if (k < 0) append(c.toString().lowercase()) else append(keys[k].first())
        }
    }

    /** [typed], keys' codes, as their letters, a space between keys: `bn ui` for BN UI's codes, read aloud as letters. */
    fun shown(typed: String): String = typed.map { c -> letters(c).ifEmpty { c.toString() } }.joinToString(" ")

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

    /**
     * As kept in the app's preferences, read back by [parse]: the eight keys' alone. A grouped
     * keyboard is fixed and kept by nothing; [grouped] finds it from its [tag].
     */
    fun serialize(): String {
        check(!isGrouped) { "a grouped keyboard is not kept: see grouped(tag)" }
        return if (isStandard) "" else keys.joinToString(",")
    }

    override fun equals(other: Any?) = other is NineKeyLetters && other.keys == keys && other.codes == codes

    override fun hashCode() = keys.hashCode() * 31 + codes.hashCode()

    // made once: grouped(tag) compares it for each code of each word typed on the 14 keys (SpelledForm)
    private val tagText = keys.joinToString(",")

    /** The layout as the learning record files the nine keys' codes under it: every key's letters, the standard ones too. */
    fun tag(): String = tagText

    override fun toString() = keys.joinToString(",")

    companion object {
        /** The most letters a key types. */
        const val MAX = 5

        private const val TYPINGS = 64

        private const val HALF = 0.5f

        private val STANDARD_KEYS = listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")

        val STANDARD = NineKeyLetters(STANDARD_KEYS)

        private const val DIGITS = "23456789"

        /**
         * The 14 keys (拼音双键): `QW ER TY UI OP / AS DF GH JK L / ZX CV BN M`, each key's code
         * a private use character, U+F8DA plus its first letter's place, as [G18]'s and right after
         * its block (not ⒜ to ⒵ as before: text a user may type, a long press's character too).
         * Made as it is, not through [normalize]. The codes and [tag] go into the learning record:
         * once in a release, never changed.
         */
        val G14 = grouped(FIRST_G14_CODE, "qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m")

        /**
         * 双拼's 18 keys: `Q WE RT Y U IO P / A SD FG H JK L; / Z XC V BN M`, ; (微软's and 紫光's
         * ing) on L as msime has it. Each key's code is a private use character, U+F8C0 plus its
         * first letter's place: codes of their own, so one code is one group whatever the input
         * method (a code shared with [G14] would be read as that keyboard's groups in the moment
         * after a switch the keyboard shown has not caught up with). Private use:
         * no text a user types (braille is: blind users'), no case, no digit, dropped wherever
         * the app keeps such characters from the field; in the BMP, as every code is a Char, past
         * the syllables' (Keyboard.SYLLABLES ends at U+F8BF). As on [G14], a code less its
         * block's first is its key's first letter.
         */
        val G18 = grouped(FIRST_PRIVATE_CODE, "q", "we", "rt", "y", "u", "io", "p", "a", "sd", "fg", "h", "jk", "l;", "z", "xc", "v", "bn", "m")

        /**
         * 双拼 on the nine keys: the letters on a phone, ; with jkl, fixed (the user's 九键 letters
         * are 全拼's), codes private use, U+F8F4 plus (the key's digit − 2), after [G14]'s block:
         * apart from the digits, so no t9: row, and from the other grouped keyboards' codes. Not
         * through [normalize].
         */
        val NINE_SP = NineKeyLetters(
            listOf("abc", "def", "ghi", "jkl;", "mno", "pqrs", "tuv", "wxyz"),
            (0 until 8).map { FIRST_NINE_SP_CODE + it }.joinToString(""),
            separator = null,
        )

        // three blocks one after the other in the last 64 of the BMP's private use area, past the
        // syllables'; U+F8FC–F8FF left free
        private const val FIRST_PRIVATE_CODE = '\uF8C0'
        private const val FIRST_G14_CODE = '\uF8DA'
        private const val FIRST_NINE_SP_CODE = '\uF8F4'

        /**
         * The blocks the grouped keyboards' codes are taken from, whole: [G18]'s U+F8C0 to U+F8D9,
         * [G14]'s U+F8DA to U+F8F3, [NINE_SP]'s U+F8F4 to U+F8FB. The one place they are written:
         * what must never reach a field as text (KeyCodes) reads them here.
         */
        val G14_BLOCK = FIRST_G14_CODE.code..FIRST_G14_CODE.code + ('z' - 'a')
        val G18_BLOCK = FIRST_PRIVATE_CODE.code..FIRST_PRIVATE_CODE.code + ('z' - 'a')
        val NINE_SP_BLOCK = FIRST_NINE_SP_CODE.code..FIRST_NINE_SP_CODE.code + 7

        /** Every grouped keyboard's code, the three blocks together (U+F8C0–F8FB). */
        val CODES = G18_BLOCK.first..NINE_SP_BLOCK.last

        private val GROUPED by lazy { listOf(G14, G18, NINE_SP) }

        /**
         * The grouped keyboard whose [tag] is [tag], as a `qk:` or `spk:` kind of the learning
         * record holds it (Kinds.keys, Kinds.shuangpinKeys; Kinds.keysTag reads it back); null for none, an eight-key layout's tag too. Not [parse]: that reads
         * the eight keys alone and has anything else as on a phone.
         */
        fun grouped(tag: String): NineKeyLetters? = GROUPED.firstOrNull { it.tag() == tag }

        /**
         * The grouped keyboard [c] is a code of ([G14], [G18], [NINE_SP]), null for anything else, the
         * nine keys' digits too: one code is of one keyboard alone, so this is all a reader needs.
         */
        fun ofCode(c: Int): NineKeyLetters? = if (c > Char.MAX_VALUE.code) null else GROUPED.firstOrNull { it.isCode(c.toChar()) }

        private fun grouped(first: Char, vararg keys: String) =
            NineKeyLetters(keys.toList(), keys.map { first + (it[0] - 'a') }.joinToString(""), separator = null)

        /**
         * The letters of [keys], 2's first: each as [normalize] has it, a key not given as on a
         * phone. The keys of no key left are those on a phone too.
         */
        fun of(keys: List<String>): NineKeyLetters {
            val all = STANDARD_KEYS.indices.map { k -> keys.getOrNull(k)?.let(::normalize) ?: STANDARD_KEYS[k] }
            return if (all == STANDARD_KEYS) STANDARD else NineKeyLetters(all)
        }

        /** [text] as [serialize] wrote it; what is not, as on a phone (a grouped keyboard's tag too: see [grouped]). */
        fun parse(text: String): NineKeyLetters {
            val keys = text.split(',')
            return if (keys.size == STANDARD_KEYS.size) of(keys) else STANDARD
        }

        /** [typed] as a key's letters: a to z alone, lower case, each once, at most [MAX]. */
        fun normalize(typed: String): String =
            typed.lowercase().filter { it in 'a'..'z' }.toCharArray().distinct().take(MAX).joinToString("")
    }
}
