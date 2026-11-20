/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.stroke

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.SourceException
import java.io.BufferedReader

/** Characters by their strokes typed `h s p n z` (点 is 捺): those starting so, commonest first by [score], unknown ones after by fewest strokes. */
class Strokes(private val table: CodeTable, private val score: (String) -> Float) {

    private val order: IntArray by lazy(LazyThreadSafetyMode.NONE) {
        require(table.size < 1 shl INDEX_BITS) { "${table.size} entries" }
        // a sort of plain longs (it runs at the first key): score negated (non-negative, so bits order as values), strokes, table order
        val keys = LongArray(table.size) {
            val score = score(table.text(it)).takeIf(Float::isFinite) ?: Float.NEGATIVE_INFINITY
            val length = minOf(table.codeLength(it), (1 shl LENGTH_BITS) - 1)
            (java.lang.Float.floatToIntBits(maxOf(0f, -score)).toLong() shl Int.SIZE_BITS) or
                (length.toLong() shl INDEX_BITS) or it.toLong()
        }
        keys.sort()
        IntArray(keys.size) { (keys[it] and INDEX_MASK).toInt() }
    }

    // every entry by its character, for 笔画筛选: ~170 000 indexes, ~680 KB, made at the first stroke without making strings
    private val byChar: IntArray by lazy(LazyThreadSafetyMode.NONE) {
        val keys = LongArray(table.size) { (table.textChar(it, 0).code.toLong() shl INDEX_BITS) or it.toLong() }
        keys.sort()
        IntArray(keys.size) { (keys[it] and INDEX_MASK).toInt() }
    }

    private fun charAt(i: Int) = table.textChar(byChar[i], 0)

    /** Each entry's character as a code point (repeats as the table has them), for 生僻字屏蔽 (RareGrid). */
    fun codePoints(): Sequence<Int> = (0 until table.size).asSequence().map { e ->
        val c = table.textChar(e, 0)
        if (Character.isHighSurrogate(c)) Character.toCodePoint(c, table.textChar(e, 1)) else c.code
    }

    private fun firstOf(c: Char): Int {
        var lo = 0
        var hi = byChar.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (charAt(mid) < c) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Whether [c] is written in an order starting with [strokes] (either of 望's two); false if unknown or nothing drawn. */
    fun startsWith(c: Char, strokes: CharSequence): Boolean {
        if (strokes.isEmpty()) return false
        var i = firstOf(c)
        while (i < byChar.size && charAt(i) == c) {
            val entry = byChar[i++]
            if (table.codeLength(entry) >= strokes.length && strokes.indices.all { table.codeKey(entry, it) == strokes[it] }) return true
        }
        return false
    }

    fun ordersOf(c: Char): List<String> {
        val orders = ArrayList<String>()
        var i = firstOf(c)
        while (i < byChar.size && charAt(i) == c) orders += table.code(byChar[i++])
        return orders
    }

    /** The characters whose strokes start with [strokes], likeliest first, each once, made as they are gone through. */
    fun find(strokes: String): Sequence<String> {
        if (strokes.isEmpty()) return emptySequence()
        val range = table.prefixRange(strokes)
        if (range.isEmpty()) return emptySequence()
        return sequence {
            val seen = HashSet<String>()
            val ranked = order.iterator()
            var left = range.last - range.first + 1
            while (left > 0) {
                val i = ranked.nextInt()
                if (i !in range) continue
                left--
                val text = table.text(i)
                if (seen.add(text)) yield(text)
            }
        }
    }

    companion object {
        private const val INDEX_BITS = 24
        private const val LENGTH_BITS = 8
        private const val INDEX_MASK = (1L shl INDEX_BITS) - 1

        const val KEYS = "hspnz"

        const val SHOWN = "一丨丿丶乛"

        fun shown(strokes: CharSequence) = strokes.map { SHOWN[KEYS.indexOf(it)] }.joinToString("")

        /**
         * A Rime stroke dictionary (rime-stroke's `stroke.dict.yaml`); only BMP characters are kept.
         *
         * @throws SourceException on a malformed line
         */
        fun read(text: BufferedReader, source: String): CodeTable.Builder {
            val builder = CodeTable.Builder()
            var data = false
            var longest = 0
            var number = 0
            text.forEachLine { line ->
                number++
                when {
                    !data -> data = line.trimEnd() == "..."
                    line.isBlank() || line.startsWith('#') -> {}
                    else -> {
                        val fields = line.trimEnd().split('\t')
                        val strokes = fields.getOrNull(1).orEmpty()
                        if (fields[0].isEmpty() || strokes.isEmpty() || strokes.any { it !in KEYS }) {
                            throw SourceException(source, number, "expected \"字<TAB>strokes\": $line")
                        }
                        // past the BMP a phone's fonts show next to none of them
                        if (fields[0].length == 1) {
                            builder.entry(strokes, fields[0])
                            longest = maxOf(longest, strokes.length)
                        }
                    }
                }
            }
            return builder.header("键码", KEYS).header("码长", longest.toString())
        }
    }
}
