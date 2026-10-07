/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShuangpinSetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun typed(input: String, expected: String) =
        ShuangpinSet.convert(listOf(Sample(input, expected, "daily")), ShuangpinScheme.XIAOHE).map { it.input }

    @Test
    fun oneSyllableIsTypedForEachCharacter() {
        assertEquals(listOf("nihc"), typed("nihao", "你好"))
        assertEquals(listOf("xm"), typed("xian", "先"))
        assertEquals(listOf("xian"), typed("xian", "西安"))
        assertEquals(listOf("xian"), typed("xi'an", "西安"))
        assertEquals(listOf("AGgu"), typed("AGgu", "AG股"))
    }

    @Test
    fun samplesWithoutOneReadingAreLeftOut() {
        // fang'an or fan'gan, whichever the text; an abbreviation; unfinished; a typo; no cut of that many syllables
        for ((input, expected) in listOf("fangan" to "反感", "nh" to "你好", "zho" to "中", "zhagn" to "张", "ni" to "你好")) {
            assertEquals(input, emptyList<String>(), typed(input, expected))
        }
    }

    @Test
    fun theCommandWritesAnEvaluationSet() {
        val set = tmp.newFile("set.tsv").apply { writeText("nihao\t你好\tdaily\tAh，\nnh\t你好\tabbrev\n") }
        val converted = tmp.newFile("sp.tsv")
        val out = StringBuilder()
        assertEquals(0, runCli(arrayOf("shuangpin", "xiaohe", set.path, converted.path), out, StringBuilder()))
        assertEquals("1 of 2 samples typed in xiaohe", out.trim())
        assertEquals(listOf(Sample("nihc", "你好", "daily", "Ah，")), converted.useLines { EvalSet.parse(it) })
        assertEquals(2, runCli(arrayOf("shuangpin", "nope", set.path, converted.path), out, StringBuilder()))
    }
}
