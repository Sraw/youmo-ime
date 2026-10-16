/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.handwriting.handwritingDataBytes
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.table.TableLanguage
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
        // what the next key does after a word is found out once too: the same again types nothing
        val again = run.type("你工")
        assertEquals(1, again.spared)
        assertEquals(0, again.actions)
    }

    @Test
    fun aWordOutOfReachIsFoundOutOnce() {
        // 乙 is the twelfth of a's candidates, one to a page: past the pages turned to find it
        val crowded = "一二三四五六七八九十丁".fold(CodeTable.Builder().header("键码", "a").header("码长", "4")) { b, c -> b.entry("a", c.toString()) }
            .entry("a", "乙").build().toByteArray().let { CodeTable.load(ByteBuffer.wrap(it)) }
        val run = TableRun(crowded, TableOptions(pageSize = 1))
        val once = run.type("乙")
        assertEquals(1, once.missing)
        assertTrue(once.actions > 0)
        // not typed again, at the next cut or in the next text
        val twice = run.type("乙乙")
        assertEquals(2, twice.missing)
        assertEquals(0, twice.actions)
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

    // 人 and 山 share abcd, a 重码; 高 commits itself; 天's abc is its one entry, abcd longer
    private val homographEntries = CodeTable.Builder()
        .header("键码", "abcdefghijklmnopqrstuvwxy")
        .header("码长", "4")
        .entry("g", "高").entry("abcd", "人").entry("abcd", "山").entry("abc", "天")
        .build().toByteArray()

    // the engine's handwriting test words: 人 likelier alone, 山 after 高
    private val pinyin = handwritingDataBytes

    /** Picks [word] on the last of its [slices], before then not done: a large model taking more than the default's pause. */
    private class Late(override val slices: Int, private val word: String) : SentenceRefiner {
        var calls = 0

        override fun refine(context: String, readings: List<String>, scores: List<Float>, budget: Int): Int? =
            if (++calls < slices) null else readings.indexOf(word)
    }

    @Test
    fun aWordIsCountedWhereItsCodeHasAnotherEntryAsLong() {
        val run = TableRun(CodeTable.load(ByteBuffer.wrap(homographEntries)), TableOptions.WUBI)
        val words = PinyinData.load(ByteBuffer.wrap(pinyin)).let { TextWords(it.model, it.wordIndex) }
        val late = Late(SentenceRefiner.SLICES + 8, "人")
        val (n, first) = run.homographs("高人天", listOf(null, TableLanguage(words), TableLanguage(words, refiner = late)))
        // 人 alone: 高 commits itself, and 天 comes first before the longer abcd's in every row
        assertEquals(1, n)
        // the table's order, 山 by the word model after 高, 人 again once the refiner is given its slices
        assertEquals(listOf(1, 0, 1), first.toList())
        // given at least its slices: how often the session asks past them is the session's business
        assertTrue(late.calls >= late.slices)
    }

    @Test
    fun aCodeKeptInTheTablesOrderIsNoHomograph() {
        // 上 and 下 share e, a 简码 no longer than 五笔's noSortInputLength: no ranking reorders them
        val table = CodeTable.Builder().header("键码", "abcdefghijklmnopqrstuvwxy").header("码长", "4")
            .entry("e", "上").entry("e", "下").build().toByteArray().let { CodeTable.load(ByteBuffer.wrap(it)) }
        assertEquals(0, TableRun(table, TableOptions.WUBI).homographs("上", listOf(null)).first)
        // where codes of any length are ranked, it is one
        assertEquals(1, TableRun(table, TableOptions()).homographs("上", listOf(null)).first)
    }

    @Test
    fun theCommandReportsHowOftenEachRankingPutsTheWordFirst() {
        val data = File(tmp.root, "t.data").also { it.writeBytes(homographEntries) }
        val words = File(tmp.root, "pinyin.data").also { it.writeBytes(pinyin) }
        val set = File(tmp.root, "s.tsv").also { it.writeText("gaorentian\t高人天\tx\n") }
        val out = StringBuilder()
        assertEquals(0, runCli(arrayOf("table", data.path, set.path, "--preset", "wubi", "--pinyin", words.path), out, StringBuilder()))
        val lines = out.lines()
        assertTrue(out.toString(), "重码 words: 1" in lines)
        assertTrue(out.toString(), lines.any { it.startsWith("  table's order") && it.endsWith("first 100.0%") })
        assertTrue(out.toString(), lines.any { it.startsWith("  word model (low)") && it.endsWith("first 0.0%") })
    }
}
