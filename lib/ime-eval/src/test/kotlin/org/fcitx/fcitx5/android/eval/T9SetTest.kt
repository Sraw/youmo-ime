/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class T9SetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun typed(input: String, expected: String, presses: T9Set.Presses = T9Set.Presses.ONCE) =
        T9Set.convert(listOf(Sample(input, expected, "daily")), presses).map { it.input }

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
    fun withMultiTapEachLetterPastTheFirstOnItsKeyIsTheLetterTypedAsItself() {
        // s, h and i are each past the first on their key; a is 2's first
        assertEquals(listOf("SHI"), typed("shi", "是", T9Set.Presses.LETTERS))
        assertEquals(listOf("H2O"), typed("hao", "好", T9Set.Presses.LETTERS))
        assertEquals(listOf("NIH2O"), typed("nihao", "你好", T9Set.Presses.LETTERS))
        // ü as v
        assertEquals(listOf("LV"), typed("lv", "绿", T9Set.Presses.LETTERS))
    }

    /** What the set writes for LETTERS is what the session makes of those presses, as the keyboard sends them. */
    @Test
    fun lettersAreTheInputTheSessionMakesOfThePresses() {
        val data = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder().unigram("<unk>", -7f, 0f)
                    .unigram("你好", -2.5f, 0f).entry("你好", intArrayOf(Syllables.id("ni"), Syllables.id("hao")))
                    .unigram("是", -2f, 0f).entry("是", intArrayOf(Syllables.id("shi")))
                    .build().toByteArray(),
            ),
        )
        fun session() = PinyinSession(data, T9Segmenter(), spell = true, prediction = false)
        val keys = NineKeyLetters.STANDARD
        for ((letters, word) in listOf("nihao" to "你好", "shi" to "是", "lv" to "绿", "haonan" to "好男")) {
            // pressed: a pause before each letter, then its key quickly again till it comes to it
            val pressed = session()
            var byPresses = pressed.apply(Action.Key(keys.keysOf(letters[0]).first()))
            letters.forEachIndexed { n, c ->
                val key = keys.keysOf(c).first()
                if (n > 0) byPresses = pressed.apply(Action.Key(key))
                for (p in 1..keys.letters(key).indexOf(c)) byPresses = pressed.apply(Action.Letter(keys.letters(key)[p], again = true))
            }
            // the set's input: a digit a key, a capital a letter typed as itself
            val written = session()
            val input = typed(letters, word, T9Set.Presses.LETTERS).single()
            val bySet = input.map { if (it in T9Segmenter.FIXED) written.apply(Action.Letter(it.lowercaseChar())) else written.apply(Action.Key(it)) }.last()
            assertEquals(letters, bySet.preedit, byPresses.preedit)
            assertEquals(letters, bySet.cursor, byPresses.cursor)
            assertEquals(letters, bySet.candidates, byPresses.candidates)
        }
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
        // fast modelled the hybrid 连按 gone 2026-10-19
        assertEquals(2, runCli(arrayOf("t9", set.path, converted.path, "--presses", "fast"), StringBuilder(), StringBuilder()))
        assertEquals(0, runCli(arrayOf("t9", set.path, converted.path, "--presses", "letters"), StringBuilder(), StringBuilder()))
        assertEquals("NIH2O", converted.useLines { EvalSet.parse(it) }.single().input)
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
