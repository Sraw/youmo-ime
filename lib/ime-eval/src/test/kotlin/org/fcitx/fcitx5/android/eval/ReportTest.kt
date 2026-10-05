/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReportTest {

    private fun score(group: String, top1: Double, p95: Long?) =
        Score(group, 10, 0, top1, 0.9, 0.95, 1.0, p95?.div(2), p95, p95)

    /** Cells are separated by at least two spaces; no cell holds two in a row. */
    private fun column(table: String, row: Int, name: String): String {
        val cells = table.lines().map { it.trim().split(Regex("\\s{2,}")) }
        return cells[row][cells[0].indexOf(name)]
    }

    @Test
    fun ratesArePercentagesAndMissingLatencyIsADash() {
        val table = Report.table(listOf(score("daily", 0.785, null)))
        assertEquals("78.5%", column(table, 1, "top1"))
        assertEquals("-", column(table, 1, "p95 ms"))
    }

    @Test
    fun aBaselineAddsSignedPercentagePointDeltas() {
        val table = Report.table(
            listOf(score("daily", 0.80, 12_300), score("new", 0.5, 1000)),
            baseline = listOf(score("daily", 0.785, 30_000))
        )
        assertEquals("80.0% (+1.5)", column(table, 1, "top1"))
        assertEquals("12.3", column(table, 1, "p95 ms"))
        // a group the baseline lacks gets no delta rather than a made-up one
        assertEquals("50.0%", column(table, 2, "top1"))
        val worse = Report.table(listOf(score("a", 0.7, null)), listOf(score("a", 0.75, null)))
        assertEquals("70.0% (-5.0)", column(worse, 1, "top1"))
    }

    @Test
    fun columnsLineUp() {
        val table = Report.table(listOf(score("daily", 0.8, 1000), score("ambiguous", 1.0, 100_000)))
        assertEquals(1, table.lines().map { it.length }.toSet().size)
    }

    @Test
    fun theCliRejectsAnUnknownCommandWithUsage() {
        val out = StringBuilder()
        val err = StringBuilder()
        assertEquals(2, runCli(arrayOf("scroe", "a", "b"), out, err))
        assertEquals(USAGE, err.trim())
        assertTrue(out.isEmpty())
    }

    @Test
    fun theCliScoresTheCommittedBaseline() {
        val out = StringBuilder()
        val code = runCli(arrayOf("score", "data/pinyin.tsv", "baseline/pinyin-libime.tsv"), out, StringBuilder())
        assertEquals(0, code)
        val all = out.lines().first { it.startsWith(Metrics.ALL) }
        // every sample of the set has a result in the baseline
        assertTrue(all, Regex("""^all\s+${File("data/pinyin.tsv").useLines { EvalSet.parse(it) }.size}\s+0\s""").containsMatchIn(all))
    }

    @Test
    fun flipsShowEachWayAndTheirChance() {
        val table = Report.flips(listOf(Flips("chat", 1064, 30, 10), Flips(Metrics.ALL, 1064, 30, 10)))
        assertEquals("30", column(table, 1, "won"))
        assertEquals("10", column(table, 1, "lost"))
        assertEquals("0.0022", column(table, 1, "p"))
    }
}
