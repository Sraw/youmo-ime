/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.scene.layout.Groups
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The letters each grouped key types (ü as v), each key a code in [codes] in the input. Nine keys: 2–9 as on
 * a phone unless the user set them otherwise; a letter may be on several keys or none. [G14], [G18] and
 * [NINE_SP] are fixed, with private-use codes no other key types.
 *
 * @property separator what splits syllables, null where a key of its own does (apostrophe)
 */
class NineKeyLetters private constructor(
    private val keys: List<String>,
    val codes: String = DIGITS,
    val separator: Char? = '1',
    // the record's tag where it is not the letters ([NINE_SP]'s)
    private val filedAs: String? = null,
    // each key's letters as rows ([custom]'s `pq rs`), where a tap falls; null: one row each
    private val rows: List<List<String>>? = null,
) {

    /** Private-use codes, not nine-key digits: shown as letters. */
    val isGrouped: Boolean = codes != DIGITS

    fun letters(code: Char): String = codes.indexOf(code).let { if (it < 0) "" else keys[it] }

    /** Whether tap place weighs [code]'s letters: two or more a–z (the 18 keys' `L;` is l alone). Engine, app and eval agree. */
    fun weighs(code: Char): Boolean = letters(code).count { it in 'a'..'z' } > 1

    /** How far a tap at [at] (0 left edge, 1 right) is from letter [index]: 0 on its half to 1 at the far edge; two letters a key. */
    fun against(index: Int, at: Float): Float {
        val lean = 2 * (at - HALF) // -1 the left edge, 1 the right
        return if (index == 0) maxOf(0f, lean) else maxOf(0f, -lean)
    }

    /** Top to bottom; one row for a built-in keyboard's. */
    fun rowsOf(code: Char): List<String> = codes.indexOf(code).let { k -> if (k < 0) emptyList() else rows?.get(k) ?: listOf(keys[k]) }

    /** [against] over [code]'s [rowsOf] for a [place]; a 1-D place takes y as the middle. A one-row two-letter key keeps the halves rule. */
    fun against(code: Char, index: Int, place: Float): Float {
        val cells = rowsOf(code)
        val x = placeX(place)
        if (cells.size == 1 && cells[0].length == 2) return against(index, x)
        return against(index, x, placeY(place).takeUnless { it.isNaN() } ?: HALF, cells)
    }

    fun isCode(c: Char): Boolean = c in codes

    fun keysOf(c: Char): String = buildString { keys.forEachIndexed { k, letters -> if (c in letters) append(codes[k]) } }

    /** The first key typing [c], to stand for it in the input; [c] itself if none. */
    fun key(c: Char): Char {
        // keys hold only a–z (and 双拼's ;): digits, most of what is read, are none
        if (c !in 'a'..'z' && c != ';') return c
        return keys.indexOfFirst { c in it }.let { if (it < 0) c else codes[it] }
    }

    fun sameKey(key: Char, c: Char): Boolean =
        if (isCode(key)) c in letters(key) else keys.any { c in it && key.lowercaseChar() in it }

    /** Codes as their key's first letter, the rest lower case: shown and committed so, a grouped code never reaching the user's text. Nine-key digits stay digits. */
    fun asTyped(text: String): String = buildString(text.length) {
        for (c in text) {
            val k = if (isGrouped) codes.indexOf(c) else -1
            // String.lowercase, which differs from Char's for İ
            if (k < 0) append(c.toString().lowercase()) else append(keys[k].first())
        }
    }

    /** Codes as their letters, space-separated: `bn ui`. */
    fun shown(typed: String): String = typed.map { c -> letters(c).ifEmpty { c.toString() } }.joinToString(" ")

    /** Each way [spelling] is typed, at most [TYPINGS] (a letter on many keys would explode long syllables); none if a letter is on no key. */
    fun typings(spelling: String): List<String> {
        var out = listOf("")
        for (c in spelling) {
            val on = keysOf(c)
            if (on.isEmpty()) return emptyList()
            out = out.flatMap { typed -> on.map { typed + it } }.take(TYPINGS)
        }
        return out
    }

    val missing: String get() = ('a'..'z').filter { c -> keys.none { c in it } }.joinToString("")

    val isStandard: Boolean get() = keys == STANDARD_KEYS

    /** As kept in preferences, read back by [parse]; grouped keyboards are fixed and found by [tag] ([grouped]). */
    fun serialize(): String {
        check(!isGrouped) { "a grouped keyboard is not kept: see grouped(tag)" }
        return if (isStandard) "" else keys.joinToString(",")
    }

    override fun equals(other: Any?) = other is NineKeyLetters && other.keys == keys && other.codes == codes

    override fun hashCode() = keys.hashCode() * 31 + codes.hashCode()

    // made once: compared for every code of every word typed on 14 keys
    private val tagText = filedAs ?: keys.joinToString(",")

    /** The tag the learning record files these keys' codes under. */
    fun tag(): String = tagText

    override fun toString() = keys.joinToString(",")

    companion object {
        const val MAX = Groups.MAX

        private const val TYPINGS = 64

        /** The most groups a custom keyboard has: its code block, [CUSTOM_BLOCK]. */
        const val CUSTOM_MAX = Groups.CUSTOM_MAX

        // 2-D place: x + 2·(1 + row of [PLACE_ROWS]); 1-D: x alone. Must match Keyboard.TAP_ROWS
        internal const val PLACE_ROWS = 9
        private const val PLACE_SPAN = 2f

        /** A tap's place on its key ([x] across, [y] down, each 0 to 1; y to the nearest of 9) as one value; [placeX], [placeY] read it back. */
        fun place(x: Float, y: Float): Float = PLACE_SPAN * (1 + (y.coerceIn(0f, 1f) * (PLACE_ROWS - 1)).roundToInt()) + x.coerceIn(0f, 1f)

        fun placeX(place: Float): Float = if (place < PLACE_SPAN) place else place - PLACE_SPAN * (place / PLACE_SPAN).toInt()

        /** NaN for a 1-D place. */
        fun placeY(place: Float): Float =
            if (place.isNaN() || place < PLACE_SPAN) Float.NaN else ((place / PLACE_SPAN).toInt() - 1).toFloat() / (PLACE_ROWS - 1)

        /** How far a tap at ([x], [y]) is from letter [index] laid out in [rows]: distance to its cell over the cell's distance to the farthest corner, 0 to 1. */
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

        /** The 14 keys (拼音双键), codes U+F8DA plus first-letter place. Codes and [tag] are in the learning record: never change them. */
        val G14 = grouped(FIRST_G14_CODE, "qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m")

        /**
         * 双拼's 18 keys (`;` on L, 微软's ing), codes U+F8C0 plus first-letter place: distinct from [G14]'s so a
         * code means one group even right after a keyboard switch. Private use: no text a user types, no case, in
         * the BMP past the syllables' (U+F83F), [CUSTOM_BLOCK] (U+F840–F85D) and ZhuyinKeys' (U+F880–F8B4).
         */
        val G18 = grouped(FIRST_PRIVATE_CODE, "q", "we", "rt", "y", "u", "io", "p", "a", "sd", "fg", "h", "jk", "l;", "z", "xc", "v", "bn", "m")

        /**
         * 双拼 on nine keys: phone letters, fixed, plus `;` (微软's ing) typed by 1 where a final goes; codes U+F8F4
         * plus (digit − 2), `;` U+F8FC. Filed under the old tag from when `;` was on 5, so what was learned stays.
         */
        val NINE_SP = NineKeyLetters(
            listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz", ";"),
            (0 until 9).map { FIRST_NINE_SP_CODE + it }.joinToString(""),
            separator = null,
            filedAs = "abc,def,ghi,jkl;,mno,pqrs,tuv,wxyz",
        )

        /** Sent by 1 while something is typed on 微软's keys. */
        val NINE_SP_SEMICOLON: Char = NINE_SP.key(';')

        // three blocks in the last 64 of the BMP's private use area; U+F8FD–F8FF free
        private const val FIRST_PRIVATE_CODE = '\uF8C0'
        private const val FIRST_G14_CODE = '\uF8DA'
        private const val FIRST_NINE_SP_CODE = '\uF8F4'

        // custom groups' codes: U+F840 on, freed from Keyboard.SYLLABLES (97 ids, not 98)
        private const val FIRST_CUSTOM_CODE = '\uF840'

        /** Custom keyboards' codes: U+F840 + key index, at most [CUSTOM_MAX]. Stored in the learning record (`qk:<tag>`) and backups: never move or renumber. Read as the keyboard [inUse]. */
        val CUSTOM_BLOCK = FIRST_CUSTOM_CODE.code until FIRST_CUSTOM_CODE.code + CUSTOM_MAX

        // the custom keyboard a custom code is read as ([ofCode])
        @Volatile
        private var current: NineKeyLetters? = null

        /** [Groups.problem], which the layout check also asks; null if fine. */
        fun problem(groups: List<String>): String? = Groups.problem(groups)

        /**
         * The grouped keyboard of [groups] (a layout's `letters=`; must pass [problem]): [G14] or [G18] when the
         * groups are theirs in any order (codes follow first letters, so rearranged built-ins keep codes and record,
         * with their drawn [rowsOf] kept), else one of its own ([own]) from [CUSTOM_BLOCK] under its [tag]. Letter
         * order within a group matters (`wq` is not `qw`): tap place leans to the first letter on the left.
         */
        fun custom(groups: List<String>): NineKeyLetters {
            problem(groups)?.let { throw IllegalArgumentException(it) }
            val keys = groups.map { it.replace(" ", "") }
            val rows = groups.map { it.split(' ') }.takeIf { r -> r.any { it.size > 1 } }
            // same groups exactly (a group twice would leave one out)
            listOf(G14, G18).firstOrNull { it.keys.size == keys.size && it.keys.toSet() == keys.toSet() }?.let { k ->
                return if (rows == null) k else NineKeyLetters(k.keys, k.codes, k.separator, k.filedAs, k.keys.map { rows[keys.indexOf(it)] })
            }
            return own(keys, rows)
        }

        /** The custom keyboard [groups] make; null if invalid or G14/G18 (built-ins have their own codes, sessions and record). */
        fun ownOf(groups: List<String>): NineKeyLetters? = if (problem(groups) != null) null else custom(groups).takeUnless { it == G14 || it == G18 }

        // a custom keyboard of [keys] in order, [rows] as written (null: a row each), codes from CUSTOM_BLOCK
        private fun own(keys: List<String>, rows: List<List<String>>? = null) =
            NineKeyLetters(keys, keys.indices.map { FIRST_CUSTOM_CODE + it }.joinToString(""), separator = null, rows = rows)

        /** Makes custom [keyboard] the one whose codes [ofCode] reads, until another is; set only by Engines from the settings. */
        fun inUse(keyboard: NineKeyLetters) {
            if (keyboard.codes.firstOrNull()?.code in CUSTOM_BLOCK) current = keyboard
        }

        /** None in use: custom codes read as no keyboard's until the next [inUse]. */
        fun clearInUse() {
            current = null
        }

        // filedAs per tag, asked for every code of every word; bounded (KEPT_FILED), as a damaged record may name any number of tags
        private val filed = ConcurrentHashMap<String, Filed>()
        private const val KEPT_FILED = 16

        private class Filed(val keyboard: NineKeyLetters?)

        /** The grouped keyboard `qk:` [tag] was typed on: [G14], [G18], or a custom one; null for none. Not [NINE_SP] (`spk:`), nor G14/G18 for reordered groups (those were custom keyboards before 0.6.1). */
        fun filedAs(tag: String): NineKeyLetters? {
            filed[tag]?.let { return it.keyboard }
            if (filed.size >= KEPT_FILED) filed.clear()
            return filed.getOrPut(tag) {
                Filed(listOf(G14, G18).firstOrNull { it.tag() == tag } ?: tag.split(',').takeIf { problem(it) == null }?.let { own(it) })
            }.keyboard
        }

        /** The grouped keyboards' code blocks, the one place they are written: KeyCodes reads them to keep them out of fields. */
        val G14_BLOCK = FIRST_G14_CODE.code..FIRST_G14_CODE.code + ('z' - 'a')
        val G18_BLOCK = FIRST_PRIVATE_CODE.code..FIRST_PRIVATE_CODE.code + ('z' - 'a')
        val NINE_SP_BLOCK = FIRST_NINE_SP_CODE.code..FIRST_NINE_SP_CODE.code + 8

        /** All grouped codes, the three blocks together (U+F8C0–F8FC). */
        val CODES = G18_BLOCK.first..NINE_SP_BLOCK.last

        private val GROUPED by lazy { listOf(G14, G18, NINE_SP) }

        /** The grouped keyboard of a `qk:`/`spk:` record [tag]; null for none, an eight-key layout's too. Not [parse], which reads eight keys only. */
        fun grouped(tag: String): NineKeyLetters? = GROUPED.firstOrNull { it.tag() == tag }

        /** The grouped keyboard [c] is a code of; null otherwise (nine-key digits too): each code belongs to one keyboard. */
        fun ofCode(c: Int): NineKeyLetters? = when {
            c > Char.MAX_VALUE.code -> null
            c in CUSTOM_BLOCK -> current?.takeIf { it.isCode(c.toChar()) }
            else -> GROUPED.firstOrNull { it.isCode(c.toChar()) }
        }

        /** Zhuyin keys with their own [codes] ([ZhuyinKeys]): no separator, never grouped(tag)'s. */
        internal fun zhuyin(keys: List<String>, codes: String) = NineKeyLetters(keys, codes, separator = null)

        private fun grouped(first: Char, vararg keys: String) =
            NineKeyLetters(keys.toList(), keys.map { first + (it[0] - 'a') }.joinToString(""), separator = null)

        /** From [keys], 2's first, each [normalize]d; missing keys as on a phone. */
        fun of(keys: List<String>): NineKeyLetters {
            val all = STANDARD_KEYS.indices.map { k -> keys.getOrNull(k)?.let(::normalize) ?: STANDARD_KEYS[k] }
            return if (all == STANDARD_KEYS) STANDARD else NineKeyLetters(all)
        }

        /** [text] as [serialize] wrote it; anything else as on a phone (grouped tags: see [grouped]). */
        fun parse(text: String): NineKeyLetters {
            val keys = text.split(',')
            return if (keys.size == STANDARD_KEYS.size) of(keys) else STANDARD
        }

        /** a–z only, lower case, each once, at most [MAX]. */
        fun normalize(typed: String): String =
            typed.lowercase().filter { it in 'a'..'z' }.toCharArray().distinct().take(MAX).joinToString("")
    }
}
