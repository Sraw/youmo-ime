/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    // the tag the learning record files the keys under where it is not their letters ([NINE_SP]'s)
    private val filedAs: String? = null,
    // each key's letters as rows ([custom]'s `pq rs`), where a tap's place falls on them; null: a row each
    private val rows: List<List<String>>? = null,
) {

    /** Whether the codes are a grouped keyboard's private use codes ([G14], [G18], [NINE_SP]), not the nine keys' digits: shown as letters. */
    val isGrouped: Boolean = codes != DIGITS

    /** The letters [code] types; none for what is no key of them. */
    fun letters(code: Char): String = codes.indexOf(code).let { if (it < 0) "" else keys[it] }

    /**
     * How far a tap at [at] across a key (0 its left edge, 1 its right) goes against its letter
     * [index] (of [letters]), 0 on that letter's half to 1 at the far edge. Two letters a key (the
     * 14 keys', tapped across alone: Keyboard.TAPS): the first is the left half, any other the
     * right; the two dimensional [against] gives the same for a row of two. The segmenter, the
     * syllable bar and Keyboard.typedLetter go by the key aware one (code, index, place).
     */
    fun against(index: Int, at: Float): Float {
        val lean = 2 * (at - HALF) // -1 the left edge, 1 the right
        return if (index == 0) maxOf(0f, lean) else maxOf(0f, -lean)
    }

    /** The rows the letters of [code] sit in on its key, top to bottom (one row for a built-in keyboard's). */
    fun rowsOf(code: Char): List<String> = codes.indexOf(code).let { k -> if (k < 0) emptyList() else rows?.get(k) ?: listOf(keys[k]) }

    /**
     * How far a tap at [place] ([NineKeyLetters.place], or a one dimensional x alone) on the key of
     * [code] goes against its letter [index] (of [letters]): [against] over the key's [rowsOf], a one
     * dimensional place its y the middle. A key of one row of two letters (the 14 keys') goes by the
     * two halves' rule, the same values (NineKeyLettersCustomTest), so built-in typing stays as it was.
     */
    fun against(code: Char, index: Int, place: Float): Float {
        val cells = rowsOf(code)
        val x = placeX(place)
        if (cells.size == 1 && cells[0].length == 2) return against(index, x)
        return against(index, x, placeY(place).takeUnless { it.isNaN() } ?: HALF, cells)
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
    private val tagText = filedAs ?: keys.joinToString(",")

    /** The layout as the learning record files the nine keys' codes under it: every key's letters, the standard ones too. */
    fun tag(): String = tagText

    override fun toString() = keys.joinToString(",")

    companion object {
        /** The most letters a key types. */
        const val MAX = 5

        private const val TYPINGS = 64

        /** The most groups a layout's own keyboard ([custom]) has: its codes' block, [CUSTOM_BLOCK]. */
        const val CUSTOM_MAX = 30

        // a two dimensional place: x plus twice (1 + its row of [PLACE_ROWS] places); a one dimensional one is x alone, 0 to 1.
        // Keyboard.TAP_ROWS is this: a tap's row sent is read back here
        internal const val PLACE_ROWS = 9
        private const val PLACE_SPAN = 2f

        /**
         * A tap's place on its key, [x] across (0 its left edge, 1 its right) and [y] down (0 its top,
         * 1 its bottom, to the nearest of 9), as one value the input's taps carry (PressedKeys, the
         * segmenter): [placeX] and [placeY] read it back. A place of x alone (the 14 keys') is that x.
         */
        fun place(x: Float, y: Float): Float = PLACE_SPAN * (1 + (y.coerceIn(0f, 1f) * (PLACE_ROWS - 1)).roundToInt()) + x.coerceIn(0f, 1f)

        /** [place]'s x. */
        fun placeX(place: Float): Float = if (place < PLACE_SPAN) place else place - PLACE_SPAN * (place / PLACE_SPAN).toInt()

        /** [place]'s y; NaN for a one dimensional place. */
        fun placeY(place: Float): Float =
            if (place.isNaN() || place < PLACE_SPAN) Float.NaN else ((place / PLACE_SPAN).toInt() - 1).toFloat() / (PLACE_ROWS - 1)

        /**
         * How far a tap at ([x], [y]) on a key (0 to 1 each way) goes against its letter [index], the
         * letters sitting in [rows] (top to bottom, each row's letters sharing its width, the rows its
         * height; 060-render-v2 §15): the distance from the tap to that letter's cell over the
         * distance from the cell to the key's farthest corner, 0 to 1. One row of two is the halves'
         * [against] point by point (each a straight distance, never through a square root).
         */
        fun against(index: Int, x: Float, y: Float, rows: List<String>): Float {
            var r = 0
            var i = index
            while (r < rows.size && i >= rows[r].length) i -= rows[r++].length
            if (r == rows.size) return 0f
            val cell = Cell(i.toFloat() / rows[r].length, (i + 1).toFloat() / rows[r].length, r.toFloat() / rows.size, (r + 1).toFloat() / rows.size)
            val far = maxOf(maxOf(cell.distance(0f, 0f), cell.distance(1f, 0f)), maxOf(cell.distance(0f, 1f), cell.distance(1f, 1f)))
            if (far == 0f) return 0f
            return (cell.distance(x, y) / far).coerceIn(0f, 1f)
        }

        // a letter's box on its key: from (x, y) to it straight along one axis where the other is within it
        private class Cell(val left: Float, val right: Float, val top: Float, val bottom: Float) {
            fun distance(x: Float, y: Float): Float {
                val dx = if (x < left) left - x else if (x > right) x - right else 0f
                val dy = if (y < top) top - y else if (y > bottom) y - bottom else 0f
                return if (dx == 0f) dy else if (dy == 0f) dx else sqrt(dx * dx + dy * dy)
            }
        }

        private const val HALF = 0.5f

        private val STANDARD_KEYS = listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")

        val STANDARD = NineKeyLetters(STANDARD_KEYS)

        private const val DIGITS = "23456789"

        /**
         * The 14 keys (拼音双键): `QW ER TY UI OP / AS DF GH JK L / ZX CV BN M`, each key's code
         * a private use character, U+F8DA plus its first letter's place, as [G18]'s and right after
         * its block (not ⒜ to ⒵ as before: text a user may type, a long press's character too).
         * Made as it is, not through [normalize]. The codes and [tag] go into the learning record:
         * once in a release, never changed. Nor are a layout's own keyboard's ([CUSTOM_BLOCK]).
         */
        val G14 = grouped(FIRST_G14_CODE, "qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m")

        /**
         * 双拼's 18 keys: `Q WE RT Y U IO P / A SD FG H JK L; / Z XC V BN M`, ; (微软's
         * ing) on L as msime has it. Each key's code is a private use character, U+F8C0 plus its
         * first letter's place: codes of their own, so one code is one group whatever the input
         * method (a code shared with [G14] would be read as that keyboard's groups in the moment
         * after a switch the keyboard shown has not caught up with). Private use:
         * no text a user types (braille is: blind users'), no case, no digit, dropped wherever
         * the app keeps such characters from the field; in the BMP, as every code is a Char, past
         * the syllables' (Keyboard.SYLLABLES ends at U+F83F, a layout's own keyboard's [CUSTOM_BLOCK] U+F840–F85D, ZhuyinKeys' block U+F880–F8B4). As on [G14], a code less its
         * block's first is its key's first letter.
         */
        val G18 = grouped(FIRST_PRIVATE_CODE, "q", "we", "rt", "y", "u", "io", "p", "a", "sd", "fg", "h", "jk", "l;", "z", "xc", "v", "bn", "m")

        /**
         * 双拼 on the nine keys: the letters on a phone, fixed (the user's 九键 letters are 全拼's),
         * and a ninth key `;` (微软's ing), typed by 1 where a final goes (PinyinSession); codes
         * private use, U+F8F4 plus (the key's digit − 2), `;` U+F8FC, after [G14]'s block: apart
         * from the digits, so no t9: row, and from the other grouped keyboards' codes. Not through
         * [normalize]. Filed under the tag it had when `;` was on 5 (`…,jkl;,…`): a code of 2 to 9
         * means what it meant, so what was learned stays its own; only `;` typed on 5 before
         * (微软's ing) is a code no key now types, never matched again.
         */
        val NINE_SP = NineKeyLetters(
            listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz", ";"),
            (0 until 9).map { FIRST_NINE_SP_CODE + it }.joinToString(""),
            separator = null,
            filedAs = "abc,def,ghi,jkl;,mno,pqrs,tuv,wxyz",
        )

        /** [NINE_SP]'s `;` key's code, which 1 sends while something is typed on 微软's keys. */
        val NINE_SP_SEMICOLON: Char = NINE_SP.key(';')

        // three blocks one after the other in the last 64 of the BMP's private use area, past the
        // syllables'; U+F8FD–F8FF left free
        private const val FIRST_PRIVATE_CODE = '\uF8C0'
        private const val FIRST_G14_CODE = '\uF8DA'
        private const val FIRST_NINE_SP_CODE = '\uF8F4'

        // a layout's own groups' codes ([custom]): U+F840 on, freed of Keyboard.SYLLABLES (97 ids, not 98)
        private const val FIRST_CUSTOM_CODE = '\uF840'

        /**
         * The codes of a layout's own keyboard ([custom]): its keys' in order from U+F840, at most
         * [CUSTOM_MAX]. U+F840 + a key's index is a stable encoding that is kept: the learning
         * record's `qk:<tag>` rows (and so backups) hold these codes, read by the key's place among
         * the tag's groups. Never move the block or renumber it. Which keyboard a code is of is the
         * one in use ([inUse]); a code is read again by that when the keyboard changes.
         */
        val CUSTOM_BLOCK = FIRST_CUSTOM_CODE.code until FIRST_CUSTOM_CODE.code + CUSTOM_MAX

        // the layout's own keyboard in use ([inUse]): the one a custom block's code is read as ([ofCode])
        @Volatile
        private var current: NineKeyLetters? = null

        /**
         * What is wrong with [groups] (each a key's letters, rows apart by a space) for a keyboard of
         * their own: a to z each on some key (ü as v), a key 1 to [MAX] letters of a to z and ;, none
         * twice, at most [CUSTOM_MAX] keys; null where nothing is.
         */
        @Suppress("ReturnCount")
        fun problem(groups: List<String>): String? {
            if (groups.isEmpty() || groups.size > CUSTOM_MAX) return "${groups.size} keys, not 1 to $CUSTOM_MAX"
            for (g in groups) {
                val letters = g.replace(" ", "")
                if (letters.isEmpty() || letters.length > MAX) return "\"$g\" is not 1 to $MAX letters"
                if (letters.any { it !in 'a'..'z' && it != ';' } || g.split(' ').any { it.isEmpty() }) return "\"$g\" is not letters in rows"
                if (letters.toSet().size != letters.length) return "\"$g\" has a letter twice"
            }
            val missing = ('a'..'z').filter { c -> groups.none { c in it } }
            return if (missing.isEmpty()) null else "no key types ${missing.joinToString("")}"
        }

        /**
         * The grouped keyboard of [groups] (a layout's `letters=`, rows apart by a space; [problem]
         * none, else IllegalArgumentException): [G14] or [G18] where their letters are those
         * keyboards' (their codes, their record, so the built-in 14 and 18 keys move nothing; equal
         * to them, and written in rows, those rows kept: a tap across alone then leans to no letter
         * of a key stacked, rather than to the wrong one), otherwise one of its own, its codes from
         * [CUSTOM_BLOCK], filed under its [tag].
         */
        fun custom(groups: List<String>): NineKeyLetters {
            problem(groups)?.let { throw IllegalArgumentException(it) }
            val keys = groups.map { it.replace(" ", "") }
            val rows = groups.map { it.split(' ') }.takeIf { r -> r.any { it.size > 1 } }
            listOf(G14, G18).firstOrNull { it.keys == keys }?.let { k ->
                return if (rows == null) k else NineKeyLetters(k.keys, k.codes, k.separator, k.filedAs, rows)
            }
            return NineKeyLetters(keys, keys.indices.map { FIRST_CUSTOM_CODE + it }.joinToString(""), separator = null, rows = rows)
        }

        /**
         * [keyboard], a layout's own ([custom]), as the one in use: its codes are read as its keys
         * ([ofCode], Keyboard.typedLetter) until another is. The session that types on it is to say
         * so when it is made (T9b wires it: till then only tests call it, and [ofCode] reads no
         * custom code); nothing else sets it (made, compared and filed keyboards do not).
         */
        fun inUse(keyboard: NineKeyLetters) {
            if (keyboard.codes.firstOrNull()?.code in CUSTOM_BLOCK) current = keyboard
        }

        // filedAs's keyboard of each tag asked (a few: the layouts used), null for none: SpelledForm
        // asks for each code of each word read, the record's rows of several tags in turn
        private val filed = ConcurrentHashMap<String, Filed>()

        private class Filed(val keyboard: NineKeyLetters?)

        /**
         * The grouped keyboard what is learnt under `qk:` [tag] was typed on: [G14] or [G18], else a
         * layout's own ([custom], a row a key); null for none. Not [NINE_SP] (双拼's, `spk:`): a layout
         * of its groups is a keyboard of its own here.
         */
        fun filedAs(tag: String): NineKeyLetters? {
            return filed.getOrPut(tag) {
                Filed(listOf(G14, G18).firstOrNull { it.tag() == tag } ?: tag.split(',').takeIf { problem(it) == null }?.let(::custom))
            }.keyboard
        }

        /**
         * The blocks the grouped keyboards' codes are taken from, whole: [G18]'s U+F8C0 to U+F8D9,
         * [G14]'s U+F8DA to U+F8F3, [NINE_SP]'s U+F8F4 to U+F8FC. The one place they are written:
         * what must never reach a field as text (KeyCodes) reads them here.
         */
        val G14_BLOCK = FIRST_G14_CODE.code..FIRST_G14_CODE.code + ('z' - 'a')
        val G18_BLOCK = FIRST_PRIVATE_CODE.code..FIRST_PRIVATE_CODE.code + ('z' - 'a')
        val NINE_SP_BLOCK = FIRST_NINE_SP_CODE.code..FIRST_NINE_SP_CODE.code + 8

        /** Every grouped keyboard's code, the three blocks together (U+F8C0–F8FC). */
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
        fun ofCode(c: Int): NineKeyLetters? = when {
            // past the keyboard in use's keys: none (isCode)
            c > Char.MAX_VALUE.code -> null
            c in CUSTOM_BLOCK -> current?.takeIf { it.isCode(c.toChar()) }
            else -> GROUPED.firstOrNull { it.isCode(c.toChar()) }
        }

        /** Keys of zhuyin symbols, [codes] theirs ([ZhuyinKeys]): no separator of their own, never grouped(tag)'s. */
        internal fun zhuyin(keys: List<String>, codes: String) = NineKeyLetters(keys, codes, separator = null)

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
