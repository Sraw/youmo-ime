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
 * The corpus is read in [passes] passes, each over every page in turn ([add] takes a page's
 * text and date; [nextPass] ends a pass). Counting every run exactly would not fit: billions
 * of characters have hundreds of millions of different runs of three. So pass n (from 2)
 * sketches the runs of n characters in a count-min sketch of up to [sketchBits] bits' width, only
 * those whose two parts one shorter the sketch found frequent enough, which every frequent
 * run's are; the pass after counts exactly the runs the sketch admitted, which a sketch only
 * overestimates, so none frequent is missed. The last pass gathers the neighbours of the runs
 * that reached [minCount], are not [known] (the dictionary's words need no telling apart; their
 * entropies come out NaN) and hold together at all ([GATHER_PMI]); when those are many, it
 * looks at every n-th page only, as the pairs of run and neighbour would not fit either, and an
 * entropy from a sample of a frequent run's neighbours is near enough.
 */
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
        /** The earliest year a page with it was dated, 0 if none was. */
        val year: Int,
        /** log10 of its count over what its likeliest split's parts predict: how much more it occurs than by chance. */
        val pmi: Double,
        /** Of the character before it (nats); the more different ones, the freer the word stands. NaN for a known word. */
        val leftEntropy: Double,
        val rightEntropy: Double,
        /** Whether the dictionary has it: counted all the same, to calibrate the rest against the model. */
        val known: Boolean,
    )

    // the runs of each length counted exactly, keyed by their chars packed 16 bits each, with
    // count and first year: single chars all, longer ones those the sketch admitted
    private val runs = Array(maxLength + 1) { LongIndex(if (it <= 1) 1 shl 12 else 1 shl 20) }
    private val counts = Array(maxLength + 1) { IntColumn(if (it <= 1) 1 shl 12 else 1 shl 20) }
    private val years = Array(maxLength + 1) { IntColumn(if (it <= 1) 1 shl 12 else 1 shl 20) }
    private val sketches = arrayOfNulls<Sketch>(maxLength + 1)

    /** The Han characters counted, after the first pass. */
    var chars = 0L
        private set

    // the last pass: the runs whose neighbours are gathered, (run, side, neighbour) counts by
    // their index, and every how many-th page is looked at
    private val kept = LongIndex(1 shl 16)
    private val neighbours = LongIndex(1 shl 20)
    private val neighbourCounts = IntColumn(1 shl 20)
    private var stride = 1
    private var pages = 0L

    /**
     * The pass under way, from 1: the first counts the characters, pass n from 2 sketches the runs
     * of n and counts those of n - 1 the sketch admitted, [maxLength] + 1 counts those of
     * [maxLength], [passes] gathers neighbours.
     */
    var pass = 1
        private set

    val passes: Int get() = maxLength + 2

    /** Whether every pass is done: [candidates] can be asked for. */
    val done: Boolean get() = pass > passes

    /** Ends the pass under way. */
    fun nextPass() {
        require(!done) { "no pass after $passes" }
        if (pass == maxLength + 1) {
            for (length in 2..maxLength) {
                val index = runs[length]
                for (i in 0 until index.size) {
                    val key = index.keyAt(i)
                    if (counts[length][i] < minCount) continue
                    val text = text(key, length)
                    if (text !in known && pmi(text, counts[length][i]) >= GATHER_PMI) kept.add(key)
                }
            }
            stride = maxOf(1, kept.size / GATHER_RUNS)
        }
        pass++
        // as many cells as the corpus could have different runs, four times over, up to the width given
        if (pass in 2..maxLength) sketches[pass] = Sketch(minOf(sketchBits, maxOf(MIN_SKETCH_BITS, Long.SIZE_BITS - java.lang.Long.numberOfLeadingZeros(chars * 4))))
    }

    /** Counts [text], a page dated [date] (`2023-06-14...`, or empty), for the pass under way. */
    fun add(text: String, date: String) {
        require(!done) { "the passes are done" }
        if (pass == passes && pages++ % stride != 0L) return
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
            // both parts one shorter must have been found frequent enough: a frequent run's were
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

    /** The exact count of a run, or the sketch's for one the sketch did not admit. */
    private fun countOf(length: Int, key: Long): Int {
        val at = runs[length].indexOf(key)
        return if (at >= 0) counts[length][at] else sketches[length]?.count(key) ?: 0
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
        for (i in 0 until kept.size) {
            val key = kept.keyAt(i)
            val length = lengthOf(key)
            val count = countOf(length, key)
            val text = text(key, length)
            val year = years[length][runs[length].indexOf(key)]
            out += Candidate(text, count, year, pmi(text, count), entropy(i, 0), entropy(i, 1), known = false)
        }
        // the known words and the runs that do not hold together, with their counts for calibration
        for (length in 2..maxLength) {
            val index = runs[length]
            for (i in 0 until index.size) {
                val key = index.keyAt(i)
                val count = counts[length][i]
                if (count < minCount || kept.indexOf(key) >= 0) continue
                val text = text(key, length)
                out += Candidate(text, count, years[length][i], pmi(text, count), Double.NaN, Double.NaN, text in known)
            }
        }
        out.sortWith(compareByDescending<Candidate> { it.count }.thenBy { it.text })
        return out
    }

    /** log10 of [count] over what [text]'s likeliest split's parts predict by chance. */
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

    /** A count-min sketch of two rows in one array: counts a run never under, seldom much over. */
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
            const val GOLDEN = -0x61c8864680b583ebL // 2^64 / φ
            const val MIX = 0x2545F4914F6CDD1DL
        }
    }

    companion object {
        const val MAX_LENGTH = 4

        /** 2^27 cells, half a gigabyte a length: a billion characters' runs collide little. */
        const val SKETCH_BITS = 27
        const val MIN_SKETCH_BITS = 12

        /** Neighbours are gathered for runs this much above chance (log10) only: threshold enough for any caller. */
        const val GATHER_PMI = 0.5

        /** Past this many runs to gather for, pages are sampled. */
        const val GATHER_RUNS = 500_000
        private const val LEFT = 0
        private const val RIGHT = 1
        private const val NONE = '\u0000'

        // the chars are counted from just below the first Han one: four then fit a LongIndex key,
        // which is never negative, and none is 0, so a key's top char tells its length
        private const val FIRST = '㏿'

        fun isHan(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿'

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

        /** How many chars [key] packs. */
        fun lengthOf(key: Long): Int {
            var length = 1
            while (length < MAX_LENGTH && (key ushr (16 * length)) != 0L) length++
            return length
        }

        private fun neighbourKey(run: Int, side: Int, neighbour: Char): Long =
            ((run.toLong() * 2 + side) shl 16) or neighbour.code.toLong()
    }
}
