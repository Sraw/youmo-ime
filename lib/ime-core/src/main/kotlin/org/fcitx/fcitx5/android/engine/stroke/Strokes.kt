/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.stroke

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.SourceException
import java.io.BufferedReader

/**
 * Characters by their strokes in writing order, each one of 横竖撇捺折 typed as `h s p n z`
 * (点 is 捺): what to type for a character one cannot read. Those whose strokes start with what
 * was typed are found, the commonest first ([score], higher for commoner, as the pinyin model
 * has them); those it does not know after, fewest strokes first, so a rare character typed whole
 * is the first of them.
 */
class Strokes(private val table: CodeTable, private val score: (String) -> Float) {

    // every entry, the likeliest first: ranked once, then filtered by what was typed
    private val order: IntArray by lazy(LazyThreadSafetyMode.NONE) {
        require(table.size < 1 shl INDEX_BITS) { "${table.size} entries" }
        // a sort of plain numbers, as it runs when the user first types the key: score (negated, its
        // bits ordered as the value is, being no less than 0), strokes, then the table's order
        val keys = LongArray(table.size) {
            val score = score(table.text(it)).takeIf(Float::isFinite) ?: Float.NEGATIVE_INFINITY
            val length = minOf(table.codeLength(it), (1 shl LENGTH_BITS) - 1)
            (java.lang.Float.floatToIntBits(maxOf(0f, -score)).toLong() shl Int.SIZE_BITS) or
                (length.toLong() shl INDEX_BITS) or it.toLong()
        }
        keys.sort()
        IntArray(keys.size) { (keys[it] and INDEX_MASK).toInt() }
    }

    // every entry by its character, then the table's order: what a character is written as, for
    // 笔画筛选 (StrokeFilter); made at its first stroke, ~170 000 entries' indexes, ~680 KB. The
    // characters are read off the table, not made strings of: the first stroke waits for this
    private val byChar: IntArray by lazy(LazyThreadSafetyMode.NONE) {
        // the character above the index's bits: one sort of plain numbers, as for [order]
        val keys = LongArray(table.size) { (table.textChar(it, 0).code.toLong() shl INDEX_BITS) or it.toLong() }
        keys.sort()
        IntArray(keys.size) { (keys[it] and INDEX_MASK).toInt() }
    }

    private fun charAt(i: Int) = table.textChar(byChar[i], 0)

    // where [c]'s entries start in [byChar], or where they would
    private fun firstOf(c: Char): Int {
        var lo = 0
        var hi = byChar.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (charAt(mid) < c) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Whether [c] is written in an order that starts with [strokes] (a character of more than one
     * order, 望's two, in either): false for one the table has not, or nothing drawn.
     */
    fun startsWith(c: Char, strokes: CharSequence): Boolean {
        if (strokes.isEmpty()) return false
        var i = firstOf(c)
        while (i < byChar.size && charAt(i) == c) {
            val entry = byChar[i++]
            if (table.codeLength(entry) >= strokes.length && strokes.indices.all { table.codeKey(entry, it) == strokes[it] }) return true
        }
        return false
    }

    /** The orders [c] is written in, as the table has them: none for a character it has not. */
    fun ordersOf(c: Char): List<String> {
        val orders = ArrayList<String>()
        var i = firstOf(c)
        while (i < byChar.size && charAt(i) == c) orders += table.code(byChar[i++])
        return orders
    }

    /**
     * The characters whose strokes start with [strokes], the likeliest first, each once; none for
     * nothing typed. Made as they are gone through: the first stroke has thousands, a page shows a few.
     */
    fun find(strokes: String): Sequence<String> {
        if (strokes.isEmpty()) return emptySequence()
        val range = table.prefixRange(strokes)
        if (range.isEmpty()) return emptySequence()
        return sequence {
            val seen = HashSet<String>()
            val ranked = order.iterator()
            var left = range.last - range.first + 1
            // till the range is all gone through: the rest of the order has none of it
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

        /** The keys of the strokes, 横竖撇捺折. */
        const val KEYS = "hspnz"

        /** The strokes as they show in the preedit, a key each. */
        const val SHOWN = "一丨丿丶乛"

        fun shown(strokes: CharSequence) = strokes.map { SHOWN[KEYS.indexOf(it)] }.joinToString("")

        /**
         * A Rime dictionary of strokes (rime-stroke's `stroke.dict.yaml`): a header, `...`, then
         * `字<TAB>strokes` a line, a weight after it ignored. Only characters of the BMP are kept.
         *
         * @throws SourceException on a line that is not one
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
                        // past the BMP (Ext B on) a phone's fonts show next to none of them
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
