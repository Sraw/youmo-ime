/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class PredictLearningTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    // the model has 好 after 你, and nothing after 我: 们 comes after it only once 我们 is typed
    private val bytes = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("我", -2f, 0f)
        .unigram("们", -3f, 0f)
        .unigram("你", -2f, 0f)
        .unigram("好", -2.5f, 0f)
        .unigram("中国", -3f, 0f)
        .bigram("你", "好", -0.5f, 0f)
        .entry("我", syl("wo"))
        .entry("们", syl("men"))
        .entry("你", syl("ni"))
        .entry("好", syl("hao"))
        .entry("中国", syl("zhong", "guo"))
        .build().toByteArray()

    private val data = PinyinData.load(ByteBuffer.wrap(bytes))

    // 我们 falls in the tune half, 你好 in the held-out one
    private val samples = listOf(Sample("women", "我们", "daily"), Sample("nihao", "你好", "daily"))

    @Test
    fun aContinuationFollowsEachWordButTheLast() {
        val learning = PredictLearning(data, Predictor.USER_WEIGHT)
        // never inside a word: 中国 is one; a text of one word has none
        assertEquals(
            listOf(Continuation("中国", "你好"), Continuation("中国你", "好")),
            learning.continuations(listOf(Sample("zhongguonihao", "中国你好", "daily"), Sample("ni", "你", "daily"))),
        )
    }

    // the word before's id falls back to the model's (data.wordIndex.find), as PinyinSession's does
    // (F2696): these fixtures score the same either way, the fallback kept only by that likeness
    @Test
    fun whatIsTypedThreeTimesIsOfferedAfterItsFirstWord() {
        assertEquals(listOf(Halves.TUNE, Halves.HELD_OUT), samples.map { Halves.of(it) })
        assertEquals(
            listOf(
                "tune, nothing learned" to PredictRun.Score(1, 0, 0, 0),
                "held-out, nothing learned" to PredictRun.Score(1, 1, 1, 1),
                // 们 after 我 once: not yet (U-71)
                "tune, typed once" to PredictRun.Score(1, 0, 0, 0),
                "tune, typed three times" to PredictRun.Score(1, 1, 1, 1),
                // learning 我们 takes nothing from what the model offers after 你
                "held-out, tune learned" to PredictRun.Score(1, 1, 1, 1),
            ),
            PredictLearning(data, Predictor.USER_WEIGHT).measure(samples),
        )
        // at no weight what the user typed counts for nothing
        assertEquals(PredictRun.Score(1, 0, 0, 0), PredictLearning(data, 0f).measure(samples).toMap().getValue("tune, typed three times"))
    }

    @Test
    fun theCommandsMakeASetAndMeasureIt() {
        val dataFile = tmp.newFile("pinyin.data").apply { writeBytes(bytes) }
        val set = tmp.newFile("set.tsv").apply { writeText(samples.joinToString("") { "${it.input}\t${it.expected}\t${it.tag}\n" }) }
        val out = StringBuilder()
        assertEquals(0, runCli(arrayOf("predict-learn", dataFile.path, set.path), out, StringBuilder()))
        val rows = out.lines().filter { it.isNotBlank() }
        assertEquals(5, rows.size)
        assertTrue(rows[3], rows[3].startsWith("tune, typed three times") && "top1 100.0%" in rows[3])
        out.setLength(0)
        assertEquals(0, runCli(arrayOf("predict-learn", dataFile.path, set.path, "--user-weight", "0"), out, StringBuilder()))
        assertTrue(out.lines()[3], "top1 0.0%" in out.lines()[3])
        assertEquals(2, runCli(arrayOf("predict-learn", dataFile.path, set.path, "--user-weight", "much"), StringBuilder(), StringBuilder()))

        // after each word of the set's sentences, what follows; `predict` measures it with nothing learned
        val predictSet = tmp.newFile("predict.tsv")
        assertEquals(0, runCli(arrayOf("predict-set", dataFile.path, set.path, predictSet.path), StringBuilder(), StringBuilder()))
        assertEquals(listOf("我\t们", "你\t好"), predictSet.readLines())
        val offers = tmp.newFile("offers.tsv")
        out.setLength(0)
        assertEquals(0, runCli(arrayOf("predict", dataFile.path, predictSet.path, "--offers", offers.path, "--threads", "2"), out, StringBuilder()))
        assertEquals("samples 2  top1 50.0%  top5 50.0%  chars saved per sample 0.50", out.trim())
        assertEquals(listOf("我\t们\t", "你\t好\t好"), offers.readLines())
    }
}
