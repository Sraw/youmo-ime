/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class MainTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun exit(vararg args: String): Int = runCli(arrayOf(*args), StringBuilder(), StringBuilder())

    @Test
    fun optionsAreCheckedBeforeAnythingRuns() {
        // every one a usage error, caught before a file is opened
        val usageErrors = listOf(
            arrayOf("pinyin", "d", "s", "r", "--half"), // no value
            arrayOf("pinyin", "d", "s", "r", "--half", "tune", "--half", "tune"), // twice
            arrayOf("pinyin", "d", "s", "r", "--half", "most"),
            arrayOf("pinyin", "d", "s", "r", "--fuzzy", "l_n,x_y"),
            arrayOf("pinyin", "d", "s", "r", "--fuzzy", ""),
            arrayOf("pinyin", "d", "s", "r", "--scheme", "nope"),
            arrayOf("pinyin", "d", "s", "r", "--color", "red"),
            arrayOf("pinyin", "d", "s", "r", "--neighbours", "maybe"),
            arrayOf("pinyin", "d", "s", "r", "--scheme", "ms", "--neighbours", "on"), // 双拼 reads none
            arrayOf("lm", "m", "c"), // nothing to score
            arrayOf("score", "s", "r", "--scheme", "xiaohe"), // not score's
            arrayOf("shuangpin", "xiaohe", "s", "o", "--half", "tune"),
            arrayOf("shuangpin", "nope", "s", "o"),
            arrayOf("slips", "s"),
            arrayOf("tune", "d", "s"),
            arrayOf("ksc"),
            arrayOf("table", "d", "s", "--preset", "dvorak"),
            arrayOf("table", "d", "s", "--half", "tune"),
            arrayOf("libime", "user", "f"),
            arrayOf("libime", "table", "f", "--preset", "plain"),
            arrayOf(),
        )
        for (args in usageErrors) assertEquals(args.joinToString(" "), 2, exit(*args))
    }

    @Test
    fun theUsageNamesEveryCommand() {
        val err = StringBuilder()
        assertEquals(2, runCli(arrayOf("help"), StringBuilder(), err))
        for (command in listOf("score", "pinyin", "shuangpin", "slips", "tune", "table", "libime")) assertTrue(command, "$command <" in err || "$command pinyin" in err)
    }

    @Test
    fun scoreTakesAHalf() {
        val set = tmp.newFile("set.tsv").apply { writeText("zhongguo\t中国\tdaily\nxi'an\t西安\tdaily\n") }
        val result = tmp.newFile("result.tsv").apply { writeText("zhongguo\t1\t中国\nxi'an\t1\t西安\n") }
        fun samples(vararg half: String): String {
            val out = StringBuilder()
            assertEquals(0, runCli(arrayOf("score", set.path, result.path, *half), out, StringBuilder()))
            return out.lines().first { it.startsWith(Metrics.ALL) }.split(Regex(" +"))[1]
        }
        assertEquals("2", samples())
        // the two texts fall in different halves
        assertEquals(setOf("1"), Halves.NAMES.map { samples("--half", it) }.toSet())
    }

    @Test
    fun libimesHistoryAsText() {
        // libime's HistoryBigram, version 1: two pools, a sentence of a word in the first
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeInt(0x000fc315)
            writeInt(1)
            writeInt(1)
            writeInt(1)
            val word = "你好".toByteArray()
            writeInt(word.size)
            write(word)
            writeInt(0)
        }
        val file = tmp.newFile("user.history").apply { writeBytes(bytes.toByteArray()) }
        val out = StringBuilder()
        assertEquals(0, runCli(arrayOf("libime", "history", file.path), out, StringBuilder()))
        assertEquals("你好\n", out.toString())
        // not libime's, or not there: said, not thrown
        val err = StringBuilder()
        assertEquals(1, runCli(arrayOf("libime", "pinyin", file.path), StringBuilder(), err))
        assertEquals(1, runCli(arrayOf("libime", "table", file.path + ".gone"), StringBuilder(), err))
        assertEquals(2, err.lines().count { it.startsWith(file.path) })
    }
}
