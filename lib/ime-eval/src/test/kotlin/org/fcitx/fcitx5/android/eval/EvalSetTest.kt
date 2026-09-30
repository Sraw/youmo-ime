/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EvalSetTest {

    @Test
    fun commentsAndBlankLinesAreSkipped() {
        val set = EvalSet.parse(sequenceOf("# header", "", "nihao\t你好\tdaily"))
        assertEquals(listOf(Sample("nihao", "你好", "daily")), set)
    }

    @Test
    fun aFourthFieldIsTheContext() {
        val set = EvalSet.parse(sequenceOf("jingli\t经理\tpair\t公司新来的"))
        assertEquals(listOf(Sample("jingli", "经理", "pair", "公司新来的")), set)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aLineWithoutATagIsRejected() {
        EvalSet.parse(sequenceOf("nihao\t你好"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anEmptyFieldIsRejected() {
        EvalSet.parse(sequenceOf("nihao\t\tdaily"))
    }

    @Test
    fun runResultsSurviveARoundTrip() {
        val results = listOf(
            RunResult("nihao", listOf("你好", "拟好"), listOf(1200, 800)),
            RunResult("xi'an", emptyList(), listOf(5)),
            RunResult("a", emptyList(), emptyList()),
        )
        val parsed = RunResultFormat.parse(results.asSequence().map(RunResultFormat::format))
        assertEquals(results, parsed)
    }

    @Test
    fun separatorsInsideACandidateCannotSplitTheLine() {
        val line = RunResultFormat.format(RunResult("dz", listOf("地址\t北京\n海淀", "地址"), listOf(1)))
        assertEquals(listOf(RunResult("dz", listOf("地址 北京 海淀", "地址"), listOf(1))), RunResultFormat.parse(sequenceOf(line)))
    }

    /** The set is hand-written; keep typos in its structure from reaching a scoring run. */
    @Test
    fun thePinyinSetIsWellFormed() {
        val samples = File("data/pinyin.tsv").useLines { EvalSet.parse(it) }
        val tags = setOf("daily", "written", "abbrev", "partial", "ambiguous")
        assertTrue(samples.size >= 500)
        samples.forEach { s ->
            assertTrue("unknown tag in $s", s.tag in tags)
            assertTrue("input must be what a keyboard types: $s", s.input.all { it in 'a'..'z' || it == '\'' })
            assertTrue("expected must be Han characters: $s", s.expected.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN })
        }
        assertEquals("duplicate inputs", samples.size, samples.map { it.input }.toSet().size)
    }

    /** The derived sets are made by commands from data/pinyin.tsv; a change to it must remake them. */
    @Test
    fun theDerivedSetsAreUpToDate() {
        val samples = File("data/pinyin.tsv").useLines { EvalSet.parse(it) }
        fun committed(name: String) = File("data/$name").useLines { EvalSet.parse(it) }
        assertEquals("rerun `slips`", SlipSet.generate(samples), committed("pinyin-slips.tsv"))
        assertEquals("rerun `shuangpin xiaohe`", ShuangpinSet.convert(samples, ShuangpinSet.SCHEMES.getValue("xiaohe")), committed("shuangpin-xiaohe.tsv"))
    }
}
