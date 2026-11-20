/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import java.util.BitSet

/** CommonCrawl's pages less what FineWeb's filters would drop; lines counted in a count-min sketch over two passes. */
class PageCleaner(sketchBits: Int = NewWords.SKETCH_BITS, private val minRepeats: Int = MIN_REPEATS) {

    private val seen = NewWords.Sketch(sketchBits)

    fun count(text: String) = lines(text) { seen.add(hash(it)) }

    /** [text] with the lines that stay, or null for a page that does not. */
    fun clean(text: String): String? {
        val kept = StringBuilder()
        lines(text) { line ->
            val prose = line.any { it in END } || line.count(TextChars::isHan) >= MIN_LINE_HAN
            if (prose && seen.count(hash(line)) < minRepeats) {
                if (kept.isNotEmpty()) kept.append('\n')
                kept.append(line)
            }
        }
        // a String's indexOf is the JVM's own search, a StringBuilder's a generic loop
        val page = kept.toString()
        var han = 0
        val chars = BitSet()
        for (c in page) {
            if (TextChars.isHan(c)) han++
            chars.set(c.code)
        }
        return when {
            han < MIN_PAGE_HAN -> null
            // lower-cased once (av and AV are one): an indexOf that ignores case is a generic loop
            spam(page.lowercase()) * PER_MILLE > han * MAX_SPAM -> null
            chars.cardinality() * MIN_VARIETY < minOf(han, VARIETY_HAN) -> null
            else -> page
        }
    }

    private inline fun lines(text: String, line: (String) -> Unit) {
        for (raw in text.split('\n')) {
            val trimmed = raw.trim()
            if (trimmed.isNotEmpty()) line(trimmed)
        }
    }

    /** How often the spam words occur in [page], or 0 when none of [SELLING] and few of [EVERYDAY] do. */
    private fun spam(page: String): Int {
        var selling = 0
        var everyday = 0
        var total = 0
        for (word in SELLING) {
            val n = page.occurrences(word)
            if (n > 0) selling++
            total += n
        }
        for (spellings in EVERYDAY) {
            val n = spellings.sumOf { page.occurrences(it) }
            if (n > 0) everyday++
            total += n
        }
        return if (selling > 0 || everyday >= MIN_EVERYDAY) total else 0
    }

    private fun String.occurrences(word: String): Int {
        var n = 0
        var at = indexOf(word)
        while (at >= 0) {
            if (whole(word, at)) n++
            at = indexOf(word, at + word.length)
        }
        return n
    }

    // a Latin word only whole: not the av of have or Java
    private fun String.whole(word: String, at: Int): Boolean {
        val joinedBefore = latin(word.first()) && latin(getOrNull(at - 1))
        val joinedAfter = latin(word.last()) && latin(getOrNull(at + word.length))
        return !joinedBefore && !joinedAfter
    }

    private fun latin(c: Char?) = c != null && (c in 'a'..'z' || c in 'A'..'Z')

    companion object {
        const val MIN_REPEATS = 5
        const val MIN_LINE_HAN = 20
        const val MIN_PAGE_HAN = 200

        /** FNV-1a over the chars. */
        fun hash(line: String): Long {
            var h = FNV_OFFSET
            for (c in line) h = (h xor c.code.toLong()) * FNV_PRIME
            return h
        }

        // no ASCII ';', unlike Examples.ENDS: a line of code or CSS ends in one and is no prose
        private const val END = "。！？!?；…"

        // a word of SELLING says a page sells alone; one of EVERYDAY (亚洲杯, 黄色的花) only beside another
        private val SELLING = listOf(
            "精品国产", "一区二区", "无码", "约炮", "约啪", "国产精品", "人妻", "博彩", "娱乐城", "百家乐", "老虎机", "买球",
        )
        private val EVERYDAY = listOf(
            listOf("久久"), listOf("日韩", "欧美", "亚洲"), listOf("成人"), listOf("色情"), listOf("黄色"), listOf("av"),
            listOf("自拍"), listOf("偷拍"), listOf("在线观看"), listOf("免费观看"), listOf("赌场"), listOf("彩票"),
            listOf("开云"), listOf("真人"), listOf("下注"), listOf("体育app"),
        )
        private const val PER_MILLE = 1000
        private const val MAX_SPAM = 3
        private const val MIN_EVERYDAY = 2

        // fewer than one different character in ten Han ones, or 200 on a long page: the same few over and over
        private const val MIN_VARIETY = 10
        private const val VARIETY_HAN = 2000
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L
    }
}
