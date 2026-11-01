/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class StrokeRunTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pinyinBytes =
        PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("号", -2f, 0f)
            .unigram("好", -2.5f, 0f)
            .unigram("灏", -5f, 0f)
            .entry("号", intArrayOf(Syllables.id("hao")))
            .entry("好", intArrayOf(Syllables.id("hao")))
            .entry("灏", intArrayOf(Syllables.id("hao")))
            .build().toByteArray()

    private val data = PinyinData.load(ByteBuffer.wrap(pinyinBytes))

    private val strokeBytes = Strokes.read("...\n号\tszhhz\n好\tzphzsh\n灏\tnnhszhh\n".reader().buffered(), "stroke").build().toByteArray()

    private val strokes = Strokes(CodeTable.load(ByteBuffer.wrap(strokeBytes))) { 0f }

    private fun run() = StrokeRun(PinyinSession(data, PinyinSegmenter(), prediction = false), strokes)

    @Test
    fun eachStrokeOfTheFirstCharacterIsTimedAndWhenItCameFirst() {
        val r = run()
        val hao = r.type(Sample("hao", "灏", "t"))!!
        assertEquals(StrokeRun.MAX_STROKES, hao.strokeMicros.size)
        assertEquals(1, hao.firstAfter)
        assertEquals(0, r.type(Sample("hao", "号", "t"))!!.firstAfter)
        // 好 is 2nd until its first stroke, 乛, which 号 does not start with
        assertEquals(1, r.type(Sample("hao", "好", "t"))!!.firstAfter)
    }

    @Test
    fun aFirstCharacterWithNoStrokesIsLeftOut() {
        val result = run().run(listOf(Sample("hao", "灏", "t"), Sample("hao", "A", "t")))
        assertEquals(1, result.samples)
        assertEquals(1, result.skipped)
        val rows = result.rows()
        assertTrue(rows.toString(), rows.any { it.startsWith("a stroke") && "n     4" in it })
        assertTrue(rows.toString(), rows.any { it.startsWith("a refine") && "n     1" in it })
        assertTrue(rows.toString(), "first after 1 strokes: 100.0%" in rows)
    }

    // --first-char: the first character as its sample reads it (shuo of 说话, not 说's first reading
    // shui), in the scheme's keys; a sample with no exact reading left out
    @Test
    fun theFirstCharacterIsTypedAsItsSampleReadsItInTheSchemesKeys() {
        val set = listOf(Sample("shuohua", "说话", "t"), Sample("sh", "说话", "t"))
        fun first(scheme: String?) = firstChars(set, scheme).map { it.input to it.expected }
        fun on(keys: NineKeyLetters, letters: String) = letters.map(keys::key).joinToString("")
        class Row(val scheme: String?, val input: String, val why: String)
        assertRows(
            listOf(
                Row(null, "shuo", "full pinyin: the reading itself"),
                Row("t9", "7486", "the nine keys: its digits"),
                Row("g14", T9Set.press("shuo", T9Set.Presses.ONCE, NineKeyLetters.G14)!!, "the 14 keys: their groups"),
                Row("g18-strict", T9Set.press("shuo", T9Set.Presses.ONCE, NineKeyLetters.G18)!!, "the 18 keys: their groups"),
                Row("xiaohe", "uo", "双拼 types it in its scheme, not as full pinyin"),
                Row("xiaohe-18", on(NineKeyLetters.G18, "uo"), "双拼 on the 18 keys: its scheme's keys grouped"),
            ),
            { "${it.scheme}: ${it.why}" },
        ) { row -> assertEquals(listOf(row.input to "说"), first(row.scheme)) }
    }

    @Test
    fun theCommandRunsTheFirstCharactersOnTheNineKeys() {
        val dataFile = tmp.newFile("pinyin.data").apply { writeBytes(pinyinBytes) }
        val strokeFile = tmp.newFile("stroke.data").apply { writeBytes(strokeBytes) }
        val set = tmp.newFile("set.tsv").apply { writeText("haoren\t灏人\tt\nhr\t好人\tt\n") }
        val out = StringBuilder()
        assertEquals(0, runCli(arrayOf("strokes", dataFile.path, strokeFile.path, set.path, "--first-char", "--scheme", "t9"), out, StringBuilder()))
        // hr has no exact reading: not a sample; 灏 typed 426, first after its first stroke
        assertTrue(out.toString(), out.lines().first().startsWith("samples 1 "))
        assertTrue(out.toString(), "first after 1 strokes: 100.0%" in out)
        assertEquals(2, runCli(arrayOf("strokes", dataFile.path, strokeFile.path, set.path, "--look-ahead", "0"), StringBuilder(), StringBuilder()))
    }
}
