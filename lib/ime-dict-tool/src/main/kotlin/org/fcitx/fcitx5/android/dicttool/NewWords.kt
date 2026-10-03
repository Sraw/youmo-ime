/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import kotlin.math.ln
import kotlin.math.log10

/**
 * The words a corpus uses that the dictionary lacks: runs of two to [maxLength] Han characters
 * that occur at least [minCount] times, are not [known], hold together (their count against
 * their likeliest split's, [Candidate.pmi]) and are used freely (many different characters on
 * either side, [Candidate.leftEntropy] and [Candidate.rightEntropy]), with the earliest page
 * date they were seen on. The classic unsupervised recipe; the thresholds are the caller's, as
 * what is wanted of them differs (dev/TRAINING-PLAN.md section 11.7).
 *
 * The corpus is read in [passes] + 1 passes, each over every page in turn ([add] takes a page's
 * text and date; [nextPass] ends a pass). Counting every run of four at once would not fit:
 * pass n counts the runs of n characters, from the second only those whose two parts one
 * shorter were counted enough, which every frequent run's were. The last pass gathers the
 * neighbours of the runs that reached [minCount].
 */
class NewWords(private val known: Set<String>, private val minCount: Int, private val maxLength: Int = MAX_LENGTH) {
    init {
        require(minCount >= 1 && maxLength in 2..MAX_LENGTH) { "min count $minCount, max length $maxLength" }
    }

    class Candidate(
        val text: String,
        val count: Int,
        /** The earliest year a page with it was dated, 0 if none was. */
        val year: Int,
        /** log10 of its count over what its likeliest split's parts predict: how much more it occurs than by chance. */
        val pmi: Double,
        /** Of the character before it (nats); the more different ones, the freer the word stands. */
        val leftEntropy: Double,
        val rightEntropy: Double,
        /** Whether the dictionary has it: counted all the same, to calibrate the rest against the model. */
        val known: Boolean,
    )

    // the runs of each length, keyed by their chars packed 16 bits each, with count and first year
    private val runs = Array(maxLength + 1) { LongIndex(if (it <= 1) 1 shl 12 else 1 shl 20) }
    private val counts = Array(maxLength + 1) { IntColumn(if (it <= 1) 1 shl 12 else 1 shl 20) }
    private val years = Array(maxLength + 1) { IntColumn(if (it <= 1) 1 shl 12 else 1 shl 20) }
    private var chars = 0L

    // the last pass: the runs that reached minCount, and (run, side, neighbour) counts by their index
    private val kept = LongIndex(1 shl 16)
    private val neighbours = LongIndex(1 shl 20)
    private val neighbourCounts = IntColumn(1 shl 20)

    /** The pass under way, from 1: the length counted, or [passes] + 1 gathering neighbours. */
    var pass = 1
        private set

    val passes: Int get() = maxLength

    /** Whether every pass is done: [candidates] can be asked for. */
    val done: Boolean get() = pass > passes + 1

    /** Ends the pass under way. */
    fun nextPass() {
        require(!done) { "no pass after ${passes + 1}" }
        if (pass == passes) {
            for (length in 2..maxLength) {
                val index = runs[length]
                for (i in 0 until index.size) if (counts[length][i] >= minCount) kept.add(index.keyAt(i))
            }
        }
        pass++
    }

    /** Counts [text], a page dated [date] (`2023-06-14...`, or empty), for the pass under way. */
    fun add(text: String, date: String) {
        val year = date.take(4).toIntOrNull()?.takeIf { it in 1990..2100 } ?: 0
        var begin = 0
        for (i in 0..text.length) {
            if (i == text.length || !isHan(text[i])) {
                if (i > begin) run(text, begin, i, year)
                begin = i + 1
            }
        }
    }

    private fun run(text: String, begin: Int, end: Int, year: Int) {
        require(!done) { "the passes are done" }
        val length = pass
        if (length > maxLength) return gather(text, begin, end)
        if (length == 1) chars += end - begin
        for (start in begin..end - length) {
            // both parts one shorter must have been counted enough: a frequent run's were
            if (length > 2 && (countOf(length - 1, key(text, start, length - 1)) < minCount ||
                    countOf(length - 1, key(text, start + 1, length - 1)) < minCount)
            ) continue
            val at = runs[length].add(key(text, start, length))
            counts[length].add(at, 1)
            if (year > 0 && (years[length][at] == 0 || year < years[length][at])) years[length][at] = year
        }
    }

    private fun countOf(length: Int, key: Long): Int {
        val at = runs[length].indexOf(key)
        return if (at < 0) 0 else counts[length][at]
    }

    private fun gather(text: String, begin: Int, end: Int) {
        for (length in 2..maxLength) {
            for (start in begin..end - length) {
                val run = kept.indexOf(key(text, start, length))
                if (run < 0) continue
                val left = if (start > begin) text[start - 1] else NONE
                val right = if (start + length < end) text[start + length] else NONE
                neighbourCounts.add(neighbours.add(neighbourKey(run, LEFT, left)), 1)
                neighbourCounts.add(neighbours.add(neighbourKey(run, RIGHT, right)), 1)
            }
        }
    }

    /** The runs kept, most frequent first; only after the last pass. */
    fun candidates(): List<Candidate> {
        require(done) { "the passes are not done" }
        // H = ln(total) - sum(n ln n) / total, summed over the neighbours of each run and side
        val totals = IntArray(kept.size * 2)
        val sums = DoubleArray(kept.size * 2)
        for (i in 0 until neighbours.size) {
            val slot = (neighbours.keyAt(i) ushr 16).toInt()
            val n = neighbourCounts[i]
            totals[slot] += n
            sums[slot] += n * ln(n.toDouble())
        }
        fun entropy(run: Int, side: Int): Double {
            val slot = run * 2 + side
            return if (totals[slot] == 0) 0.0 else ln(totals[slot].toDouble()) - sums[slot] / totals[slot]
        }
        val out = ArrayList<Candidate>()
        val total = chars.toDouble()
        for (length in 2..maxLength) {
            val index = runs[length]
            for (i in 0 until index.size) {
                val count = counts[length][i]
                if (count < minCount) continue
                val key = index.keyAt(i)
                val text = text(key, length)
                // the split whose parts predict it best: log10(count / (left × right / total))
                var expected = 0.0
                for (split in 1 until length) {
                    val left = countOf(split, key(text, 0, split)).coerceAtLeast(1)
                    val right = countOf(length - split, key(text, split, length - split)).coerceAtLeast(1)
                    expected = maxOf(expected, left.toDouble() * right / total)
                }
                val run = kept.indexOf(key)
                out += Candidate(text, count, years[length][i], log10(count / expected), entropy(run, 0), entropy(run, 1), text in known)
            }
        }
        out.sortWith(compareByDescending<Candidate> { it.count }.thenBy { it.text })
        return out
    }

    companion object {
        const val MAX_LENGTH = 4
        private const val LEFT = 0
        private const val RIGHT = 1
        private const val NONE = '\u0000'

        fun isHan(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿'

        // the chars are counted from the first Han one: four then fit a LongIndex key, which is never negative
        private const val FIRST = '\u3400'

        /** [length] Han chars of [text] from [start], 16 bits each, the first highest. */
        fun key(text: CharSequence, start: Int, length: Int): Long {
            var key = 0L
            for (i in 0 until length) key = (key shl 16) or (text[start + i] - FIRST).toLong()
            return key
        }

        fun text(key: Long, length: Int): String {
            val out = CharArray(length)
            for (i in 0 until length) out[length - 1 - i] = FIRST + ((key ushr (16 * i)) and 0xffff).toInt()
            return String(out)
        }

        private fun neighbourKey(run: Int, side: Int, neighbour: Char): Long =
            ((run.toLong() * 2 + side) shl 16) or neighbour.code.toLong()
    }
}
