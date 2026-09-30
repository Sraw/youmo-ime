/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import kotlin.math.ceil

/** Scores for one group of samples (a tag, or everything). Rates are 0..1. */
data class Score(
    val group: String,
    val samples: Int,
    /** samples the run has no result for; they count as misses everywhere */
    val missing: Int,
    val top1: Double,
    /** 1 - (character edit distance of the first candidate / expected length), pooled */
    val charAccuracy: Double,
    val top3: Double,
    val top5: Double,
    val latencyP50Micros: Long?,
    val latencyP95Micros: Long?,
    val latencyP99Micros: Long?,
)

object Metrics {

    const val ALL = "all"

    /**
     * One [Score] per tag, in the order tags first appear in [samples], then [ALL]. Results are
     * matched to samples by input, in order: the second sample typed `jingli` gets the second
     * result for `jingli`, as the context set types one input after different contexts.
     */
    fun score(samples: List<Sample>, results: List<RunResult>): List<Score> {
        val byInput = results.groupBy { it.input }
        val taken = HashMap<String, Int>()
        val matched = samples.map { sample ->
            val n = taken.getOrDefault(sample.input, 0).also { taken[sample.input] = it + 1 }
            sample to byInput[sample.input]?.getOrNull(n)
        }
        val groups = matched.groupBy { it.first.tag }.toList() + (ALL to matched)
        return groups.map { (group, members) -> scoreGroup(group, members) }
    }

    private fun scoreGroup(group: String, samples: List<Pair<Sample, RunResult?>>): Score {
        var missing = 0
        var top1 = 0
        var top3 = 0
        var top5 = 0
        var charErrors = 0
        var chars = 0
        val latencies = mutableListOf<Long>()
        for ((sample, result) in samples) {
            if (result == null) missing++
            val candidates = result?.candidates.orEmpty()
            val rank = candidates.indexOf(sample.expected)
            if (rank == 0) top1++
            if (rank in 0 until 3) top3++
            if (rank in 0 until 5) top5++
            chars += sample.expected.length
            charErrors += minOf(editDistance(candidates.firstOrNull().orEmpty(), sample.expected), sample.expected.length)
            result?.let { latencies += it.keyLatenciesMicros }
        }
        val n = samples.size.toDouble()
        latencies.sort()
        return Score(
            group = group,
            samples = samples.size,
            missing = missing,
            top1 = top1 / n,
            charAccuracy = 1 - charErrors.toDouble() / chars,
            top3 = top3 / n,
            top5 = top5 / n,
            latencyP50Micros = percentile(latencies, 50),
            latencyP95Micros = percentile(latencies, 95),
            latencyP99Micros = percentile(latencies, 99),
        )
    }

    /** Nearest-rank percentile of an ascending list; null when there is nothing to rank. */
    fun percentile(sorted: List<Long>, p: Int): Long? {
        if (sorted.isEmpty()) return null
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** Levenshtein distance over UTF-16 units; every character in the sets is in the BMP. */
    fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            previous = current.also { current = previous }
        }
        return previous[b.length]
    }
}
