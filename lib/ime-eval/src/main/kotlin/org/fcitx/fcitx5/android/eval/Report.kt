/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import java.util.Locale

/** Renders scores as a fixed-width table, optionally against a baseline run of the same set. */
object Report {

    private val header = listOf("group", "n", "miss", "top1", "char", "top3", "top5", "p50 ms", "p95 ms", "p99 ms")

    fun table(scores: List<Score>, baseline: List<Score>? = null): String {
        val before = baseline?.associateBy { it.group }.orEmpty()
        val rows = scores.map { s ->
            val b = before[s.group]
            listOf(
                s.group,
                s.samples.toString(),
                s.missing.toString(),
                rate(s.top1, b?.top1),
                rate(s.charAccuracy, b?.charAccuracy),
                rate(s.top3, b?.top3),
                rate(s.top5, b?.top5),
                millis(s.latencyP50Micros),
                millis(s.latencyP95Micros),
                millis(s.latencyP99Micros),
            )
        }
        val all = listOf(header) + rows
        val widths = header.indices.map { col -> all.maxOf { it[col].length } }
        return all.joinToString("\n") { row ->
            row.mapIndexed { col, cell -> if (col == 0) cell.padEnd(widths[col]) else cell.padStart(widths[col]) }
                .joinToString("  ")
                .trimEnd()
        }
    }

    /** `62.5%`, or `62.5% (+3.1)` in percentage points when there is a baseline to compare to. */
    private fun rate(value: Double, baseline: Double?): String {
        val pct = String.format(Locale.ROOT, "%.1f%%", value * 100)
        if (baseline == null) return pct
        return pct + String.format(Locale.ROOT, " (%+.1f)", (value - baseline) * 100)
    }

    private fun millis(micros: Long?): String =
        micros?.let { String.format(Locale.ROOT, "%.1f", it / 1000.0) } ?: "-"
}
