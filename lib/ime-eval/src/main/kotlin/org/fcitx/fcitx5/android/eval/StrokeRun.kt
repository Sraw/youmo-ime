/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.stroke.StrokeFilter
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import java.util.Locale

/**
 * What 笔画筛选 costs and finds (design i2-stroke-filter §5.5): each sample typed into [session],
 * then up to [MAX_STROKES] strokes of its first character drawn (its first order), timed as each
 * stroke's snapshot is made (the page the bar shows), and after the first stroke the rest looked
 * through to [lookAhead] (what a candidate list scrolled to the end asks). Whether that character starts the first
 * candidate, by strokes drawn, is what they find.
 */
class StrokeRun(session: Session, private val strokes: Strokes, private val lookAhead: Int = StrokeFilter.LOOK_AHEAD) {

    private val filter = StrokeFilter(session, { strokes }, lookAhead = lookAhead)

    /**
     * A sample's: microseconds of each stroke drawn, of the pause's [Action.Refine] after the first
     * (the host sends it while the user pauses), of looking through to the end, and the strokes
     * after which its first character was first (0 already, -1 never).
     */
    data class Outcome(val strokeMicros: List<Long>, val refineMicros: Long, val scanMicros: Long, val firstAfter: Int)

    data class Result(
        val samples: Int,
        val skipped: Int,
        val strokeMicros: List<Long>,
        val refineMicros: List<Long>,
        val scanMicros: List<Long>,
        val firstAfter: List<Int>,
    ) {
        fun rows(): List<String> = listOf(
            "samples %d (left out: %d, a first character with no strokes)".format(Locale.ROOT, samples, skipped),
            row("a stroke", strokeMicros),
            row("a refine", refineMicros),
            row("to the look-ahead", scanMicros),
        ) + (0..MAX_STROKES).map { k ->
            "first after %d strokes: %5.1f%%".format(Locale.ROOT, k, 100.0 * firstAfter.count { it in 0..k } / firstAfter.size.coerceAtLeast(1))
        }

        private fun row(label: String, micros: List<Long>): String {
            val sorted = micros.sorted()
            fun ms(p: Int) = (Metrics.percentile(sorted, p) ?: 0) / MICROS_PER_MS
            return "%-18s n %5d  p50 %6.2f ms  p95 %6.2f ms  max %6.2f ms".format(Locale.ROOT, label, sorted.size, ms(50), ms(95), (sorted.lastOrNull() ?: 0) / MICROS_PER_MS)
        }
    }

    fun type(sample: Sample): Outcome? {
        val c = sample.expected.firstOrNull() ?: return null
        val order = strokes.ordersOf(c).firstOrNull() ?: return null
        filter.apply(Action.Reset)
        sample.input.forEach { filter.apply(Action.Key(it)) }
        var firstAfter = if (filter.candidates(0, 1).firstOrNull()?.text?.firstOrNull() == c) 0 else -1
        val times = ArrayList<Long>()
        var scan = 0L
        var refine = 0L
        for ((k, s) in order.take(MAX_STROKES).withIndex()) {
            val start = System.nanoTime()
            val shown = filter.apply(Action.Stroke(s))
            times += (System.nanoTime() - start) / NANOS_PER_MICRO
            if (firstAfter < 0 && shown.candidates.firstOrNull()?.firstOrNull() == c) firstAfter = k + 1
            // after the first, the most left to look through: those kept of all to the end
            if (k == 0) {
                val paused = System.nanoTime()
                filter.apply(Action.Refine)
                refine = (System.nanoTime() - paused) / NANOS_PER_MICRO
                // what the pause put first counts as first after the one stroke, as the user sees it then
                if (firstAfter < 0 && filter.candidates(0, 1).firstOrNull()?.text?.firstOrNull() == c) firstAfter = 1
                val from = System.nanoTime()
                filter.candidates(0, lookAhead)
                scan = (System.nanoTime() - from) / NANOS_PER_MICRO
            }
        }
        return Outcome(times, refine, scan, firstAfter)
    }

    fun run(samples: List<Sample>): Result {
        // the first sample with strokes typed once untimed first: the classes loaded are no sample's cost
        samples.firstNotNullOfOrNull(::type)
        val outcomes = samples.map(::type)
        val kept = outcomes.filterNotNull()
        return Result(kept.size, outcomes.size - kept.size, kept.flatMap { it.strokeMicros }, kept.map { it.refineMicros }, kept.map { it.scanMicros }, kept.map { it.firstAfter })
    }

    companion object {
        const val MAX_STROKES = 4
        private const val NANOS_PER_MICRO = 1000L
        private const val MICROS_PER_MS = 1000.0
    }
}
