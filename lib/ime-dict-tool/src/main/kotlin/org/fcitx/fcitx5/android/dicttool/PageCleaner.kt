/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

/**
 * CommonCrawl's pages ([CommonCrawl]) without what FineWeb's filters would have taken out of them
 * (dev/TRAINING-PLAN.md 11.7d). Out goes a line that turns up [minRepeats] times or more over all
 * the pages (menus, footers, SEO lists), and one with no sentence end in it and under
 * [MIN_LINE_HAN] Han characters (a menu's, a list's, a title's); then a page left with under
 * [MIN_PAGE_HAN] Han characters, thick with adult or gambling spam, or a few characters over and
 * over. Of 2.3 billion Han characters a crawl's 1510 files hold, a third is left.
 *
 * Two passes: [count] every page, then [clean] each. Lines are counted by a 64-bit hash in a
 * count-min sketch: a collision may take out a rare line now and then, never leaves a common one in.
 * Over 1500 files' 190 million lines that is 1 in 200 rare lines, 1% of the text kept, as simulated;
 * twice the lines would make it some 20%, so a bigger crawl wants more [sketchBits].
 * The Python script measured first (dev/TRAINING-PLAN.md 11.7d) told lines apart by an ASCII trim
 * only: here a full-width indent goes too, so a line indented on one page and not on another is one.
 */
class PageCleaner(sketchBits: Int = NewWords.SKETCH_BITS, private val minRepeats: Int = MIN_REPEATS) {

    private val seen = NewWords.Sketch(sketchBits)

    fun count(text: String) = lines(text) { seen.add(hash(it)) }

    /** [text] with the lines that stay, or null for a page that does not. */
    fun clean(text: String): String? {
        val kept = StringBuilder()
        lines(text) { line ->
            val prose = line.any { it in END } || line.count { it in HAN } >= MIN_LINE_HAN
            if (prose && seen.count(hash(line)) < minRepeats) {
                if (kept.isNotEmpty()) kept.append('\n')
                kept.append(line)
            }
        }
        val han = kept.count { it in HAN }
        return when {
            han < MIN_PAGE_HAN -> null
            SPAM.sumOf { kept.occurrences(it) } * PER_MILLE > han * MAX_SPAM -> null
            kept.toSet().size * MIN_VARIETY < han -> null
            else -> kept.toString()
        }
    }

    private inline fun lines(text: String, line: (String) -> Unit) {
        for (raw in text.split('\n')) {
            val trimmed = raw.trim()
            if (trimmed.isNotEmpty()) line(trimmed)
        }
    }

    private fun CharSequence.occurrences(word: String): Int {
        var n = 0
        var at = indexOf(word)
        while (at >= 0) {
            n++
            at = indexOf(word, at + word.length)
        }
        return n
    }

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

        private val HAN = '一'..'鿿'
        private const val END = "。！？!?；…"

        // over 3 a thousand Han characters, the page is selling one of these
        private val SPAM = listOf(
            "久久", "精品国产", "一区二区", "日韩", "欧美", "无码", "成人", "约炮", "约啪", "色情", "黄色", "av", "AV",
            "国产精品", "亚洲", "人妻", "自拍", "偷拍", "在线观看", "免费观看", "博彩", "赌场", "娱乐城", "彩票", "开云",
            "百家乐", "老虎机", "真人", "下注", "体育app", "买球",
        )
        private const val PER_MILLE = 1000
        private const val MAX_SPAM = 3

        // fewer than one different character in ten Han ones: the same few over and over
        private const val MIN_VARIETY = 10
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L
    }
}
