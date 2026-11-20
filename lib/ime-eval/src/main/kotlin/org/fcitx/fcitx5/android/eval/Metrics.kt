/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln

/** Of a group's [samples], how many a run has first that its baseline had not ([won]), and the reverse. */
data class Flips(val group: String, val samples: Int, val won: Int, val lost: Int) {
    /** Two-sided exact sign test over the samples that changed: how likely a split this uneven is by chance. */
    val p: Double
        get() {
            val n = won + lost
            if (n == 0) return 1.0
            // log of n choose k, summed over the tail, halves each
            var logChoose = 0.0
            var tail = 0.0
            for (k in 0..minOf(won, lost)) {
                if (k > 0) logChoose += ln((n - k + 1).toDouble()) - ln(k.toDouble())
                tail += exp(logChoose - n * ln(2.0))
            }
            return minOf(1.0, 2 * tail)
        }
}

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

    /** One [Score] per tag of the samples of [half], then [ALL]; results matched to samples by input, in order. */
    fun score(samples: List<Sample>, results: List<RunResult>, half: String? = null): List<Score> {
        val matched = matched(samples, results, half)
        val groups = matched.groupBy { it.first.tag }.toList() + (ALL to matched)
        return groups.map { (group, members) -> scoreGroup(group, members) }
    }

    /** The samples of [half] with their results: a run over all is matched over all before the half is taken. */
    private fun matched(samples: List<Sample>, results: List<RunResult>, half: String?): List<Pair<Sample, RunResult?>> {
        val selected = Halves.select(samples, half)
        if (half == null || !overAll(samples, selected, results)) return selected.zip(match(selected, results))
        return samples.zip(match(samples, results)).filter { Halves.of(it.first) == half }
    }

    /** Whether [results] are of a run over all [samples]: for some input, more results than [half] has samples typing it. */
    private fun overAll(samples: List<Sample>, half: List<Sample>, results: List<RunResult>): Boolean {
        val inputs = samples.mapTo(HashSet()) { it.input }
        val inHalf = half.groupingBy { it.input }.eachCount()
        return results.groupingBy { it.input }.eachCount().any { (input, n) -> input in inputs && n > inHalf.getOrDefault(input, 0) }
    }

    private fun match(samples: List<Sample>, results: List<RunResult>): List<RunResult?> {
        val byInput = results.groupBy { it.input }
        val taken = HashMap<String, Int>()
        return samples.map { sample ->
            val n = taken.getOrDefault(sample.input, 0).also { taken[sample.input] = it + 1 }
            byInput[sample.input]?.getOrNull(n)
        }
    }

    /** Two runs compared sample by sample (McNemar's test), a [Flips] per group: on a few hundred samples a point is often chance. */
    fun flips(samples: List<Sample>, results: List<RunResult>, baseline: List<RunResult>, half: String? = null): List<Flips> {
        fun first(result: RunResult, sample: Sample) = result.candidates.firstOrNull() == sample.expected
        // a sample one run lacks is no flip either way, or it could hide a real loss
        val changed = matched(samples, results, half).zip(matched(samples, baseline, half)) { (sample, now), (_, before) ->
            if (now == null || before == null) null else Triple(sample.tag, first(now, sample), first(before, sample))
        }.filterNotNull()
        val groups = changed.groupBy { it.first }.toList() + (ALL to changed)
        return groups.map { (group, members) ->
            Flips(group, members.size, members.count { it.second && !it.third }, members.count { !it.second && it.third })
        }
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
