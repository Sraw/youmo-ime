/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import java.nio.ByteBuffer

/**
 * Maps log-probabilities to one byte each through a 256-entry codebook. Each code covers a
 * contiguous range of values and decodes to their mean. The ranges start out holding equal
 * numbers of values (precision goes where the values are, as in KenLM) and are then refined by
 * a few rounds of Lloyd's algorithm, which pulls in the wide bins at the tails.
 *
 * Equal values always share a code. ARPA files print 5 to 8 significant digits, so the same
 * probability recurs thousands of times; splitting such a run across two bins would make the
 * commonest values decode to a neighbour's mean. With no more distinct values than codes, every
 * value decodes exactly.
 *
 * Code 0 is reserved for exactly 0.0 when [build] is asked to: most backoff weights are 0 and
 * must stay 0, or every lookup that backs off through them would drift.
 */
class Quantizer private constructor(private val centers: FloatArray, private val bounds: FloatArray, private val exactZero: Boolean) {

    fun encode(value: Float): Int {
        if (exactZero && value == 0f) return 0
        // first bin whose largest member is >= value
        var lo = 0
        var hi = bounds.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (bounds[mid] < value) lo = mid + 1 else hi = mid
        }
        val bin = minOf(lo, bounds.size - 1)
        return bin + if (exactZero) 1 else 0
    }

    fun codebook(): ByteArray = LittleEndianOutput(LEVELS * 4).apply { centers.forEach(::writeFloat) }.toByteArray()

    /** Sorted distinct values and how often each occurs; bins are index ranges over these. */
    private class Histogram(sorted: FloatArray) {
        val values = FloatArray(sorted.size)
        val counts = IntArray(sorted.size)
        var size = 0
            private set

        init {
            for (v in sorted) {
                if (size > 0 && values[size - 1] == v) {
                    counts[size - 1]++
                } else {
                    values[size] = v
                    counts[size++] = 1
                }
            }
        }

        fun mean(from: Int, to: Int): Double {
            var sum = 0.0
            var n = 0L
            for (i in from until to) {
                sum += values[i].toDouble() * counts[i]
                n += counts[i]
            }
            return sum / n
        }
    }

    companion object {
        const val LEVELS = 256
        private const val LLOYD_ROUNDS = 100

        fun build(values: FloatArray, exactZero: Boolean): Quantizer {
            // not values.filter: boxing the 4.8 million bigram values alone takes over 100 MB
            val sorted = if (!exactZero) values.copyOf() else {
                val kept = FloatArray(values.count { it != 0f })
                var n = 0
                for (v in values) if (v != 0f) kept[n++] = v
                kept
            }
            sorted.sort()
            val h = Histogram(sorted)
            val bins = LEVELS - if (exactZero) 1 else 0
            // bin b holds distinct values [start[b], start[b + 1])
            val start = initialBins(h, sorted.size.toLong(), bins)
            if (h.size > bins) repeat(LLOYD_ROUNDS) { refine(h, start, bins) }

            val offset = if (exactZero) 1 else 0
            val centers = FloatArray(LEVELS)
            val bounds = FloatArray(bins)
            for (b in 0 until bins) {
                if (start[b] < start[b + 1]) {
                    centers[b + offset] = h.mean(start[b], start[b + 1]).toFloat()
                    bounds[b] = h.values[start[b + 1] - 1]
                } else {
                    // an empty bin copies its predecessor, so it is never chosen and bounds stay sorted
                    centers[b + offset] = if (b > 0) centers[b + offset - 1] else 0f
                    bounds[b] = if (b > 0) bounds[b - 1] else Float.NEGATIVE_INFINITY
                }
            }
            return Quantizer(centers, bounds, exactZero)
        }

        /** Equal counts per bin, as near as whole runs of equal values allow; never an empty bin while values remain. */
        private fun initialBins(h: Histogram, total: Long, bins: Int): IntArray {
            val start = IntArray(bins + 1)
            var i = 0
            var cumulative = 0L
            for (b in 0 until bins - 1) {
                start[b] = i
                val target = total * (b + 1) / bins
                // take at least one value, and leave one for each bin still to come; stop before a
                // run that would overshoot the target by more than leaving it out falls short, so a
                // very common value tends to get a bin of its own
                while (i < h.size - (bins - b - 1) &&
                    (i == start[b] || cumulative + h.counts[i] - target <= target - cumulative)
                ) {
                    cumulative += h.counts[i++]
                }
            }
            start[bins - 1] = i
            start[bins] = h.size
            return start
        }

        /** One Lloyd round: each value moves to the bin whose mean is nearest. */
        private fun refine(h: Histogram, start: IntArray, bins: Int) {
            val means = DoubleArray(bins)
            for (b in 0 until bins) {
                means[b] = if (start[b] < start[b + 1]) h.mean(start[b], start[b + 1]) else means[maxOf(b - 1, 0)]
            }
            for (b in 1 until bins) {
                val midpoint = (means[b - 1] + means[b]) / 2
                // first value above the midpoint; midpoints only grow, so search on from the last cut
                var i = start[b - 1]
                while (i < h.size && h.values[i] <= midpoint) i++
                start[b] = i
            }
        }

        /** Reads a codebook written by [codebook]. */
        fun decodeTable(buffer: ByteBuffer): FloatArray {
            if (buffer.capacity() != LEVELS * 4) throw DataFormatException("codebook of ${buffer.capacity()} bytes")
            // a NaN score would make candidate order meaningless without failing anything
            return FloatArray(LEVELS) { buffer.getFloat(it * 4) }.also { table ->
                ensureFormat(table.all { it.isFinite() }) { "codebook holds a non-finite value" }
            }
        }
    }
}
