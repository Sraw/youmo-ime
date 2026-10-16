/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class T9SetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun typed(input: String, expected: String, presses: T9Set.Presses = T9Set.Presses.ONCE) =
        T9Set.convert(listOf(Sample(input, expected, "daily")), presses).map { it.input }

    private fun again(digit: Char) = T9Segmenter.again(digit)

    @Test
    fun eachSyllableIsTypedInDigits() {
        assertEquals(listOf("64426"), typed("nihao", "你好"))
        // no separator: on the nine keys xi'an and xian are the same keys
        assertEquals(listOf("9426"), typed("xi'an", "西安"))
        assertEquals(listOf("9426"), typed("xian", "先"))
        // ü as v, on 8
        assertEquals(listOf("58"), typed("lv", "绿"))
    }

    @Test
    fun withMultiTapOnAPressOfTheKeyJustPressedIsOneAgain() {
        // shi: 7, 4, then 4 again
        assertEquals(listOf("74${again('4')}"), typed("shi", "是", T9Set.Presses.FAST))
        // across syllables too: hao ends on 6, and nan's n is on 6
        assertEquals(listOf("426${again('6')}26"), typed("haonan", "好男", T9Set.Presses.FAST))
    }

    @Test
    fun withMultiTapEachLetterIsItsPlaceOnTheKeyAndALetterOnTheSameKeyComesAfterAPause() {
        // s is 7777, h 44, i 444 after a pause: 4 and twice 4 again
        val s = "7${again('7')}${again('7')}${again('7')}"
        assertEquals(listOf(s + "4${again('4')}" + "4${again('4')}${again('4')}"), typed("shi", "是", T9Set.Presses.LETTERS))
        // ü as v: 888
        assertEquals(listOf("5${again('5')}${again('5')}8${again('8')}${again('8')}"), typed("lv", "绿", T9Set.Presses.LETTERS))
    }

    @Test
    fun samplesWithALatinLetterOrWithoutOneReadingAreLeftOut() {
        // typed in another mode on the nine keys; an abbreviation; fang'an or fan'gan
        for ((input, expected) in listOf("AGgu" to "AG股", "nh" to "你好", "fangan" to "反感")) {
            assertEquals(input, emptyList<String>(), typed(input, expected))
        }
    }

    @Test
    fun theCommandWritesTheSetWithItsContext() {
        val set = tmp.newFile("set.tsv").apply { writeText("nihao\t你好\tdaily\tAh，\nAGgu\tAG股\tdaily\n") }
        val converted = tmp.newFile("t9.tsv")
        val out = StringBuilder()
        assertEquals(0, runCli(arrayOf("t9", set.path, converted.path), out, StringBuilder()))
        assertEquals("1 of 2 samples typed on the nine keys", out.trim())
        assertEquals(listOf(Sample("64426", "你好", "daily", "Ah，")), converted.useLines { EvalSet.parse(it) })
        assertEquals(2, runCli(arrayOf("t9", set.path), StringBuilder(), StringBuilder()))
        assertEquals(2, runCli(arrayOf("t9", set.path, converted.path, "--presses", "twice"), StringBuilder(), StringBuilder()))
        assertEquals(0, runCli(arrayOf("t9", set.path, converted.path, "--presses", "fast"), StringBuilder(), StringBuilder()))
        assertEquals("64${again('4')}26", converted.useLines { EvalSet.parse(it) }.single().input)
    }

    @Test
    fun onlyT9ReadsAnInitialBesideASyllableAsLong() {
        val data = tmp.newFile("pinyin.data").apply {
            writeBytes(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .unigram("时间", -3f, 0f)
                    .entry("时间", intArrayOf(Syllables.id("shi"), Syllables.id("jian")))
                    .build().toByteArray(),
            )
        }
        // 74 is si as well as sh: 时间 as 简拼 in `t9` only
        val set = tmp.newFile("set.tsv").apply { writeText("745\t时间\tabbrev\n") }
        val result = tmp.newFile("result.tsv")
        fun candidates(scheme: String): List<String> {
            assertEquals(0, runCli(arrayOf("pinyin", data.path, set.path, result.path, "--scheme", scheme), StringBuilder(), StringBuilder()))
            return result.useLines { RunResultFormat.parse(it) }.single().candidates
        }
        assertTrue("时间" in candidates("t9"))
        assertFalse("时间" in candidates("t9-strict"))
    }
}
