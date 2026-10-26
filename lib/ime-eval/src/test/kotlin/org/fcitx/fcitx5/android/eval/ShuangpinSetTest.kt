/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.nineSp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    // design §1.4: on the 18 keys and the nine keys each key its group's code; no Latin letters there
    @Test
    fun onAGroupedKeyboardEachKeyIsItsGroupsCode() {
        val samples = listOf(Sample("nihao", "你好", "daily"), Sample("AGgu", "AG股", "daily"))
        val g18 = NineKeyLetters.G18
        assertEquals(listOf("nihc".map(g18::key).joinToString("")), ShuangpinSet.convert(samples, ShuangpinScheme.XIAOHE, g18).map { it.input })
        assertEquals(listOf(nineSp("6442")), ShuangpinSet.convert(samples, ShuangpinScheme.XIAOHE, NineKeyLetters.NINE_SP).map { it.input })
        val set = tmp.newFile("set18.tsv").apply { writeText("nihao\t你好\tdaily\nnh\t你好\tabbrev\n") }
        val converted = tmp.newFile("sp18.tsv")
        val same = tmp.newFile("same18.tsv")
        val out = StringBuilder()
        assertEquals(0, runCli(arrayOf("shuangpin", "xiaohe", set.path, converted.path, "--keys", "nine", "--same", same.path), out, StringBuilder()))
        assertEquals("1 of 2 samples typed in xiaohe on the nine keys", out.trim())
        assertEquals(listOf("nihao"), same.useLines { EvalSet.parse(it) }.map { it.input })
        assertEquals(2, runCli(arrayOf("shuangpin", "xiaohe", set.path, converted.path, "--keys", "14"), StringBuilder(), StringBuilder()))
        assertEquals((ShuangpinSet.SCHEMES.size * 2), ShuangpinSet.GROUPED.size)
        assertEquals(ShuangpinScheme.XIAOHE to g18, ShuangpinSet.GROUPED["xiaohe-18"])
    }

    @Test
    fun theGroupedSchemesReadTheirKeys() {
        val data = tmp.newFile("pinyin18.data").apply {
            writeBytes(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .unigram("你好", -3f, 0f)
                    .entry("你好", intArrayOf(Syllables.id("ni"), Syllables.id("hao")))
                    .build().toByteArray(),
            )
        }
        val result = tmp.newFile("sp18result.tsv")
        for ((scheme, keys) in listOf("xiaohe-18" to NineKeyLetters.G18, "xiaohe-nine" to NineKeyLetters.NINE_SP)) {
            val set = tmp.newFile("$scheme.tsv").apply { writeText("${"nihc".map(keys::key).joinToString("")}\t你好\tdaily\n") }
            assertEquals(0, runCli(arrayOf("pinyin", data.path, set.path, result.path, "--scheme", scheme), StringBuilder(), StringBuilder()))
            assertEquals(scheme, "你好", result.useLines { RunResultFormat.parse(it) }.single().candidates.first())
        }
        // T9Set's names alone are its own (review N7)
        assertThrows(IllegalArgumentException::class.java) { T9Set.lettersOf("xiaohe-18") }
        assertEquals(NineKeyLetters.G14, T9Set.lettersOf("g14-strict"))
    }
}
