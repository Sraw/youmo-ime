/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import kotlin.math.ln
import kotlin.math.log10

/** Frequent, cohesive (pmi) runs of Han characters with varied neighbours (entropy) that the dictionary lacks, sketched in [passes] passes. */
class NewWords(
    private val known: Set<String>,
    private val minCount: Int,
    private val maxLength: Int = MAX_LENGTH,
    private val sketchBits: Int = SKETCH_BITS,
) {
    init {
        require(minCount >= 1 && maxLength in 2..MAX_LENGTH) { "min count $minCount, max length $maxLength" }
    }

    class Candidate(
        val text: String,
        val count: Int,
        /** The earliest year a page with it was dated, 0 if none. */
        val year: Int,
        /** log10 of its count over what its likeliest split's parts predict. */
        val pmi: Double,
        /** Of the character before it (nats): the more varied, the freer the word. NaN for a known word. */
        val leftEntropy: Double,
        val rightEntropy: Double,
        /** Whether the dictionary has it: counted all the same, to calibrate against the model. */
        val known: Boolean,
    )

    // the runs of each length counted exactly, keyed by their chars packed 16 bits each
    private val runs = Array(maxLength + 1) { LongIndex(if (it <= 1) 1 shl 12 else 1 shl 20) }
    private val counts = Array(maxLength + 1) { IntColumn(if (it <= 1) 1 shl 12 else 1 shl 20) }
    private val years = Array(maxLength + 1) { IntColumn(if (it <= 1) 1 shl 12 else 1 shl 20) }
    private val sketches = arrayOfNulls<Sketch>(maxLength + 1)

    var chars = 0L
        private set

    // the last pass: the runs whose neighbours are gathered, and (run, side, neighbour) counts by index
    private val kept = LongIndex(1 shl 16)
    private val strides = IntColumn(1 shl 16)
    private val seen = IntColumn(1 shl 16)
    private val neighbours = LongIndex(1 shl 20)
    private val neighbourCounts = IntColumn(1 shl 20)

    /** The pass under way, from 1: characters; n in 2..[maxLength] sketches runs of n and counts those of n - 1; then neighbours. */
    var pass = 1
        private set

    val passes: Int get() = maxLength + 2

    val done: Boolean get() = pass > passes

    fun nextPass() {
        require(!done) { "no pass after $passes" }
        if (pass == maxLength + 1) {
            for (length in 2..maxLength) {
                val index = runs[length]
                for (i in 0 until index.size) {
                    val key = index.keyAt(i)
                    val count = counts[length][i]
                    if (count >= minCount && gathers(text(key, length), count)) strides[kept.add(key)] = (count + GATHER_PER_RUN - 1) / GATHER_PER_RUN
                }
            }
        }
        pass++
        // as many cells as the corpus could have distinct runs, four times over, capped by the width given
        if (pass in 2..maxLength) sketches[pass] = Sketch(minOf(sketchBits, maxOf(MIN_SKETCH_BITS, Long.SIZE_BITS - java.lang.Long.numberOfLeadingZeros(chars * 4))))
    }

    private fun gathers(text: String, count: Int) = text !in known && (text.length == 2 || pmi(text, count) >= GATHER_PMI)

    /** Counts [text], a page or one of its lines, dated [date] (`2023-06-14...`, or empty), for the pass under way. */
    fun add(text: String, date: String) {
        require(!done) { "the passes are done" }
        val year = date.take(4).toIntOrNull()?.takeIf { it in 1990..2100 } ?: 0
        var begin = 0
        for (i in 0..text.length) {
            if (i == text.length || !TextChars.isHan(text[i])) {
                if (i > begin) run(text, begin, i, year)
                begin = i + 1
            }
        }
    }

    private fun run(text: String, begin: Int, end: Int, year: Int) {
        when {
            pass == 1 -> {
                chars += end - begin
                for (start in begin until end) count(1, key(text, start, 1), year)
            }
            pass <= maxLength -> {
                sketch(pass, text, begin, end)
                if (pass > 2) exact(pass - 1, text, begin, end, year)
            }
            pass == maxLength + 1 -> exact(maxLength, text, begin, end, year)
            else -> gather(text, begin, end)
        }
    }

    private fun sketch(length: Int, text: String, begin: Int, end: Int) {
        val sketch = sketches[length]!!
        val parts = sketches[length - 1]
        for (start in begin..end - length) {
            if (parts != null && (parts.count(key(text, start, length - 1)) < minCount ||
                    parts.count(key(text, start + 1, length - 1)) < minCount)
            ) continue
            sketch.add(key(text, start, length))
        }
    }

    private fun exact(length: Int, text: String, begin: Int, end: Int, year: Int) {
        val sketch = sketches[length]!!
        for (start in begin..end - length) {
            val key = key(text, start, length)
            if (sketch.count(key) >= minCount) count(length, key, year)
        }
    }

    private fun count(length: Int, key: Long, year: Int) {
        val at = runs[length].add(key)
        counts[length].add(at, 1)
        if (year > 0 && (years[length][at] == 0 || year < years[length][at])) years[length][at] = year
    }

    private fun countOf(length: Int, key: Long): Int {
        val at = runs[length].indexOf(key)
        return if (at >= 0) counts[length][at] else sketches[length]?.count(key) ?: 0
    }

    private fun gather(text: String, begin: Int, end: Int) {
        for (length in 2..maxLength) {
            for (start in begin..end - length) {
                val run = kept.indexOf(key(text, start, length))
                if (run < 0 || !sampled(run)) continue
                val left = if (start > begin) text[start - 1] else NONE
                val right = if (start + length < end) text[start + length] else NONE
                neighbourCounts.add(neighbours.add(neighbourKey(run, LEFT, left)), 1)
                neighbourCounts.add(neighbours.add(neighbourKey(run, RIGHT, right)), 1)
            }
        }
    }

    // counts an occurrence of [run]: whether it is its first or every strides[run]-th after
    private fun sampled(run: Int): Boolean {
        val nth = seen[run]
        seen[run] = nth + 1
        return nth % strides[run] == 0
    }

    /** The runs that reached [minCount], each to [sink] in no order (one at a time: a list would not fit beside the counts). */
    fun candidates(sink: (Candidate) -> Unit) {
        require(done) { "the passes are not done" }
        // entropy with Miller-Madow's correction, as the plain estimate runs low; each boundary counts as a new neighbour
        val totals = IntArray(kept.size * 2)
        val distinct = IntArray(kept.size * 2)
        val sums = DoubleArray(kept.size * 2)
        for (i in 0 until neighbours.size) {
            val key = neighbours.keyAt(i)
            val slot = (key ushr 16).toInt()
            val n = neighbourCounts[i]
            totals[slot] += n
            if ((key and 0xffff).toInt().toChar() == NONE) {
                distinct[slot] += n
            } else {
                distinct[slot]++
                sums[slot] += n * ln(n.toDouble())
            }
        }
        fun entropy(run: Int, side: Int): Double {
            val slot = run * 2 + side
            val n = totals[slot]
            // none gathered (only other text in the last pass): not measured
            return if (n == 0) Double.NaN else ln(n.toDouble()) - sums[slot] / n + (distinct[slot] - 1) / (2.0 * n)
        }
        for (i in 0 until kept.size) {
            val key = kept.keyAt(i)
            val length = lengthOf(key)
            val count = countOf(length, key)
            val text = text(key, length)
            val year = years[length][runs[length].indexOf(key)]
            sink(Candidate(text, count, year, pmi(text, count), entropy(i, 0), entropy(i, 1), known = false))
        }
        for (length in 2..maxLength) {
            val index = runs[length]
            for (i in 0 until index.size) {
                val key = index.keyAt(i)
                val count = counts[length][i]
                if (count < minCount || kept.indexOf(key) >= 0) continue
                val text = text(key, length)
                sink(Candidate(text, count, years[length][i], pmi(text, count), Double.NaN, Double.NaN, text in known))
            }
        }
    }

    private fun pmi(text: String, count: Int): Double {
        val length = text.length
        var expected = 0.0
        for (split in 1 until length) {
            val left = countOf(split, key(text, 0, split)).coerceAtLeast(1)
            val right = countOf(length - split, key(text, split, length - split)).coerceAtLeast(1)
            expected = maxOf(expected, left.toDouble() * right / chars)
        }
        return log10(count / expected)
    }

    /** A count-min sketch of two rows in one array: never under a run's count, seldom much over. */
    class Sketch(bits: Int) {
        private val cells = IntArray(1 shl bits)
        private val shift = Long.SIZE_BITS - bits

        fun add(key: Long) {
            val a = slot(key)
            val b = slot(key * MIX + 1)
            if (cells[a] < Int.MAX_VALUE) cells[a]++
            if (cells[b] < Int.MAX_VALUE) cells[b]++
        }

        fun count(key: Long): Int = minOf(cells[slot(key)], cells[slot(key * MIX + 1)])

        private fun slot(key: Long) = ((key * GOLDEN) ushr shift).toInt()

        private companion object {
            const val MIX = 0x2545F4914F6CDD1DL
        }
    }

    companion object {
        const val MAX_LENGTH = 4

        /** 2^27 cells per length: a billion characters' runs collide little. */
        const val SKETCH_BITS = 27
        const val MIN_SKETCH_BITS = 12

        /** Neighbours are gathered only for runs this much above chance (log10); not for runs of two, whose characters are mostly other words'. */
        const val GATHER_PMI = 0.5

        const val GATHER_PER_RUN = 40
        private const val LEFT = 0
        private const val RIGHT = 1
        private const val NONE = '\u0000'

        // counted from just below the first Han char: four then fit a non-negative key, and none is 0
        private const val FIRST = '㏿'

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

        fun lengthOf(key: Long): Int {
            var length = 1
            while (length < MAX_LENGTH && (key ushr (16 * length)) != 0L) length++
            return length
        }

        private fun neighbourKey(run: Int, side: Int, neighbour: Char): Long =
            ((run.toLong() * 2 + side) shl 16) or neighbour.code.toLong()
    }
}
