/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import java.nio.ByteBuffer
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.g14
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.PinyinSession
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

    // the 14 keys (design §5.2): each letter its group's code, no 连按
    @Test
    fun onTheFourteenKeysEachLetterIsItsKeysCode() {
        val samples = listOf(Sample("nihao", "你好", "daily"), Sample("lv", "绿", "daily"))
        assertEquals(listOf(g14("bn ui gh as op"), g14("l cv")), T9Set.convert(samples, keys = NineKeyLetters.G14).map { it.input })
        val set = tmp.newFile("set14.tsv").apply { writeText("nihao\t你好\tdaily\n") }
        val converted = tmp.newFile("g14.tsv")
        val out = StringBuilder()
        assertEquals(0, runCli(arrayOf("t9", set.path, converted.path, "--keys", "14"), out, StringBuilder()))
        assertEquals("1 of 1 samples typed on the 14 keys", out.trim())
        assertEquals(g14("bn ui gh as op"), converted.useLines { EvalSet.parse(it) }.single().input)
        // the samples kept, as they were, for full pinyin on the same ones
        val two = tmp.newFile("two.tsv").apply { writeText("nihao\t你好\tdaily\nnh\t你好\tabbrev\n") }
        val same = tmp.newFile("same.tsv")
        assertEquals(0, runCli(arrayOf("t9", two.path, converted.path, "--keys", "14", "--same", same.path), StringBuilder(), StringBuilder()))
        assertEquals(listOf("nihao"), same.useLines { EvalSet.parse(it) }.map { it.input })
        val err = StringBuilder()
        assertEquals(2, runCli(arrayOf("t9", set.path, converted.path, "--keys", "14", "--presses", "letters"), StringBuilder(), err))
        assertTrue(err.toString(), "t9: --presses letters is the nine keys' alone, not --keys 14" in err)
        assertEquals(2, runCli(arrayOf("t9", set.path, converted.path, "--keys", "13"), StringBuilder(), StringBuilder()))
    }

    @Test
    fun theG14SchemesReadTheFourteenKeys() {
        val data = tmp.newFile("pinyin14.data").apply {
            writeBytes(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .unigram("你好", -3f, 0f)
                    .entry("你好", intArrayOf(Syllables.id("ni"), Syllables.id("hao")))
                    .build().toByteArray(),
            )
        }
        val set = tmp.newFile("g14set.tsv").apply { writeText("${g14("bn")}${g14("ui")}${g14("gh")}${g14("as")}${g14("op")}\t你好\tdaily\n") }
        val result = tmp.newFile("g14result.tsv")
        for (scheme in listOf("g14", "g14-strict")) {
            assertEquals(0, runCli(arrayOf("pinyin", data.path, set.path, result.path, "--scheme", scheme), StringBuilder(), StringBuilder()))
            assertEquals(scheme, "你好", result.useLines { RunResultFormat.parse(it) }.single().candidates.first())
        }
        // glides and English have no 14 keys
        assertEquals(2, runCli(arrayOf("english", data.path, set.path, "--keys", "14"), StringBuilder(), StringBuilder()))
        assertEquals(2, runCli(arrayOf("pinyin-glide", data.path, set.path, "--keys", "14"), StringBuilder(), StringBuilder()))
    }

    // `pinyin --taps` (k14-tap-position): where the hand tapped decides between the letters of a key
    @Test
    fun whereTheTapsLandDecidesBetweenAKeysLetters() {
        val data = tmp.newFile("pinyintaps.data").apply {
            writeBytes(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .unigram("你好", -3f, 0f)
                    .unigram("不好", -2.5f, 0f)
                    .entry("你好", intArrayOf(Syllables.id("ni"), Syllables.id("hao")))
                    .entry("不好", intArrayOf(Syllables.id("bu"), Syllables.id("hao")))
                    .build().toByteArray(),
            )
        }
        val set = tmp.newFile("tapset.tsv").apply { writeText("nihao\t你好\tdaily\n") }
        val result = tmp.newFile("tapresult.tsv")
        fun first(vararg options: String): String {
            assertEquals(0, runCli(arrayOf("pinyin", data.path, set.path, result.path, "--scheme", "g14-strict", *options), StringBuilder(), StringBuilder()))
            return result.useLines { RunResultFormat.parse(it) }.single().candidates.first()
        }
        // on each letter's centre n and i outweigh 不好's better model score; tapped mid-key, nothing does
        assertEquals("你好", first("--taps", "perfect:1"))
        assertEquals("不好", first("--taps", "middle:1"))
        // the taps type the 14 keys only, and a spec that does not parse is a usage error
        for (bad in listOf(arrayOf("--scheme", "t9-strict", "--taps", "perfect:1"), arrayOf("--scheme", "g14-strict", "--taps", "perfect:x"), arrayOf("--scheme", "g14-strict", "--taps", "perfect:-1"))) {
            assertEquals(bad.joinToString(" "), 2, runCli(arrayOf("pinyin", data.path, set.path, result.path, *bad), StringBuilder(), StringBuilder()))
        }
    }
}
