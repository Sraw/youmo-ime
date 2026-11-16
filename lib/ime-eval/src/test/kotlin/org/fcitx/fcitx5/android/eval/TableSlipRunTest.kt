/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.pinyin.Near
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.table.TableSlips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class TableSlipRunTest {

    // 仓颉's way: an empty code stays, a space drops it
    private val options = TableOptions(noMatchAutoSelectLength = 0)

    private val table = CodeTable.Builder()
        .header("键码", "abcdefghijklmnopqrstuvwxyz")
        .header("码长", "4")
        .entry("ab", "丙").entry("abr", "甲").entry("abrr", "甲甲").entry("abw", "乙")
        .build().toByteArray().let { CodeTable.load(ByteBuffer.wrap(it)) }

    private val run = TableSlipRun(table, options, TableSlips.SLIPS)

    private fun plan(code: String, word: String) = TableRun.Planned(word, code, TableRun.Typed(code.length + 1, first = true, auto = false), null)

    private fun mid(c: Char) = Touch(0f, 0f, 36f, 50f, listOf(Near(c, 1f, 0f)))

    // e tapped at its right edge, toward r
    private val eTowardR = Touch(0.45f, 0f, 36f, 50f, listOf(Near('w', -1f, 0f), Near('r', 1f, 0f)))

    @Test
    fun aSlipIntoAnEmptyCodeIsCountedAsRecovered() {
        val clean = TableSlipRun.Tapped(plan("abr", "甲"), "abr", listOf(mid('b'), mid('a'), mid('e')), null)
        val slipped = TableSlipRun.Tapped(plan("abr", "甲"), "abe", listOf(mid('b'), mid('a'), eTowardR), null)
        // without the slips the empty code is dropped by the space; with them it is abr
        assertEquals("", run.type(slipped, reading = false))
        assertEquals("甲", run.type(slipped, reading = true))
        val o = run.measure(listOf(clean), listOf(slipped))
        assertEquals(0, o.cleanChanged)
        assertEquals(1, o.slipEmpty)
        assertEquals(1, o.recovered)
        assertEquals(0, o.broken)
        assertEquals(mapOf((TableSlipRun.Result.NONE to TableSlipRun.Result.RIGHT) to 1), o.moves)
        assertTrue(TableSlipRun.report("t", o).contains("none->right 1"))
    }

    @Test
    fun theTouchedSetHasEachKeysTouchAndTheSpace() {
        val u = TableSlipRun.Tapped(plan("abr", "甲"), "abe", listOf(null, null, eTowardR), null)
        val lines = run.touchedSet(listOf(u, u), perSample = 4)
        // abe and its space: four keys, a line
        assertEquals(2, lines.size)
        val fields = lines[0].split('\t')
        assertEquals("abe ", fields[0])
        assertEquals(listOf("-", "-", eTowardR.text(), "-"), fields[4].split(' '))
    }

    @Test
    fun thePrefixPlanTypesTheShortestPartThatShowsAWordFirst() {
        val withDing = CodeTable.Builder()
            .header("键码", "abcdefghijklmnopqrstuvwxyz")
            .header("码长", "4")
            .entry("ab", "丙").entry("abr", "甲").entry("cd", "丁").entry("cde", "丁丁")
            .build().toByteArray().let { CodeTable.load(ByteBuffer.wrap(it)) }
        val run = TableSlipRun(withDing, options, TableSlips.SLIPS)
        // 丁 shows first at c already: typed so, no whole code of it; 甲 is first only at abr, where it commits
        // itself (its one candidate): left out, the whole-code plan's
        val parts = run.units(listOf("丁甲"), "s0.2", slip = false, seed = 1, prefix = true)
        assertEquals(listOf("c"), parts.map { it.planned.code })
        assertEquals(1, run.partial(parts))
        val whole = run.units(listOf("丁甲"), "s0.2", slip = false, seed = 1)
        assertEquals(listOf("cd", "abr"), whole.map { it.planned.code })
        assertEquals(0, run.partial(whole))
    }
}
