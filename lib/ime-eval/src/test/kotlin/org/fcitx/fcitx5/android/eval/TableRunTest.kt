/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

class TableRunTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun builder() = CodeTable.Builder()
        .header("键码", "abcdefghijklmnopqrstuvwxy")
        .header("码长", "4")
        .header("拼音", "@")
        .entry("a", "工").entry("a", "戈").entry("aaaa", "工").entry("aaaa", "恭")
        .entry("wqiy", "你").entry("wq", "你").entry("wqvb", "你好").entry("vbg", "好")
        .entry("kkkk", "叫").entry("kkkk", "员")
        .entry("@ni", "你")

    private val table = builder().build().toByteArray().let { CodeTable.load(ByteBuffer.wrap(it)) }

    private val run = TableRun(table, TableOptions.WUBI)

    @Test
    fun aWordIsTypedByItsCheapestCode() {
        // wq and a pick: 3 keys, where wqiy commits itself in 4
        assertEquals(TableRun.Typed(3, first = true, auto = false), run.typeCode("wq", "你"))
        assertEquals(TableRun.Typed(4, first = true, auto = true), run.typeCode("wqiy", "你"))
        // second on the page: still one pick
        assertEquals(TableRun.Typed(5, first = false, auto = false), run.typeCode("kkkk", "员"))
        // another candidate commits itself first
        assertNull(run.typeCode("vbg", "你"))
        val o = run.type("你好员")
        assertEquals(3, o.chars)
        assertEquals(0, o.missing)
        // 你好 as one word, whose wqvb commits itself, then 员 picked second
        assertEquals(2, o.words)
        assertEquals(4 + 5, o.keys)
        assertEquals(1, o.first)
        assertEquals(1, o.auto)
        assertTrue(o.actions > 0)
    }

    @Test
    fun aFirstCandidateIsCommittedByTheNextWordsFirstKey() {
        // wq leads on to nothing by a: 你 goes without a pick
        val o = run.type("你工")
        assertEquals(2, o.words)
        assertEquals(2 + 2, o.keys)
        assertEquals(1, o.spared)
        // not after a word that commits itself, nor the last word
        assertEquals(0, run.type("你好工").spared)
    }

    @Test
    fun theTextIsCutToTakeTheFewestKeys() {
        // 工工 is kkkk and a pick of the third (5 keys); 工 twice is a and a pick, twice (4)
        val table = builder().entry("kkkk", "工工").build().toByteArray().let { CodeTable.load(ByteBuffer.wrap(it)) }
        val o = TableRun(table, TableOptions.WUBI).type("工工")
        assertEquals(2, o.words)
        assertEquals(2 + 2, o.keys)
    }

    @Test
    fun whatTheTableLacksIsCountedAndSkipped() {
        val o = run.type("你他")
        assertEquals(1, o.missing)
        assertEquals(1, o.words)
        assertEquals(3, o.keys)
        // one character, though two UTF-16 units
        assertEquals(1, run.type("\uD840\uDC00").chars)
        // and a table with nothing to type
        val empty = CodeTable.Builder().header("键码", "a").header("码长", "4").build().toByteArray()
        assertEquals(2, TableRun(CodeTable.load(ByteBuffer.wrap(empty)), TableOptions()).type("你好").missing)
        assertEquals(TableRun.Outcome.ZERO.copy(chars = 1, missing = 1), run.type("他").copy(keyNanos = 0, actions = 0, slowestKeyNanos = 0))
    }

    @Test
    fun theTablesOwnEntriesAreTypedByTheirOwnCodes() {
        val e = run.entries(1)
        // the 拼音 entry is not a code to type
        assertEquals(10, e.entries)
        // 戈, 恭, 员 come second; none is out of reach
        assertEquals(7, e.first)
        assertEquals(10, e.firstPage)
        assertEquals(0, e.unreachable)
        val report = TableRun.report(run.type("你好"), e)
        assertTrue(report, "first      100.0% of words" in report)
        assertTrue(report, "first 70.0%, on page one 100.0%, unreachable 0.0%" in report)
    }

    @Test
    fun theCommandReportsOnTheSetsText() {
        val data = File(tmp.root, "t.data").also { it.writeBytes(builder().build().toByteArray()) }
        val set = File(tmp.root, "s.tsv").also { it.writeText("nihao\t你好\tx\nnihao\t你好\ty\n") }
        val out = StringBuilder()
        assertEquals(0, runCli(arrayOf("table", data.path, set.path, "--preset", "wubi"), out, StringBuilder()))
        // the text once, however many samples share it
        assertTrue(out.toString(), out.startsWith("text: 2 characters, 0 not in the table, 1 words"))
    }
}
