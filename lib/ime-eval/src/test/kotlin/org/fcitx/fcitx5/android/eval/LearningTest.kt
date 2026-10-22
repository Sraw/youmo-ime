/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.user.WordPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class LearningTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("好", -2.5f, 0f)
                .unigram("你好", -3f, 0f)
                .unigram("泥", -3f, 0f)
                .entry("你", syl("ni"))
                .entry("好", syl("hao"))
                .entry("你好", syl("ni", "hao"))
                .entry("泥", syl("ni"))
                .build().toByteArray(),
        ),
    )

    // the rows of the sample's half, by what they measure, and whether it came first in each
    private fun firsts(learning: Learning, sample: Sample): Map<String, Boolean> =
        learning.measure(listOf(sample)).filter { !it.second.isNullOrEmpty() }
            .associate { (label, outcomes) -> label.substringAfter(", ") to outcomes!!.single().first }

    @Test
    fun eachSessionHasRerankersOfItsOwnAsTheAppGivesThem() {
        // 泥好 is the second reading of nihao, after 你好
        val sample = Sample("nihao", "泥好", "daily")
        assertFalse(firsts(Learning(data, PinyinSegmenter()), sample).getValue("learning nothing"))
        var made = 0
        val picker = { made++; SentencePicker { _, readings, _ -> readings.indexOf("泥好").coerceAtLeast(0) } }
        val picked = firsts(Learning(data, PinyinSegmenter(), reranker = picker), sample)
        assertEquals(3, picked.size)
        assertTrue(picked.toString(), picked.values.all { it })
        // one for each engine: the two learning, and the one learning nothing
        assertEquals(3, made)
        val refiner = { SentenceRefiner { _, readings, _, _ -> readings.indexOf("泥好").takeIf { it >= 0 } ?: SentenceRefiner.NONE } }
        val refined = firsts(Learning(data, PinyinSegmenter(), refiner = refiner), sample)
        assertTrue(refined.toString(), refined.values.all { it })
    }

    @Test
    fun aPackIsTheUsersInEverySession() {
        // 尼, which the data lacks, scored above 你 (-2)
        val pack = WordPack.parse(sequenceOf(WordPack.HEADER, "# layer: test", "尼\tni\t-1.5"))
        val sample = Sample("ni", "尼", "daily")
        assertFalse(firsts(Learning(data, PinyinSegmenter()), sample).getValue("learning nothing"))
        val packed = firsts(Learning(data, PinyinSegmenter(), pack = pack), sample)
        assertEquals(4, packed.size)
        assertTrue(packed.toString(), packed.values.all { it })
    }

    @Test
    fun withAPackTheEngineLearningNothingLearnsNothingFromOneSampleForTheNext() {
        val pack = WordPack.parse(sequenceOf(WordPack.HEADER, "# layer: test", "尼\tni\t-1.5"))
        // the same text three times, so in the same half: 泥好 picked twice would come first the third time (U-02)
        val sample = Sample("nihao", "泥好", "daily")
        val rows = Learning(data, PinyinSegmenter(), pack = pack).measure(listOf(sample, sample, sample))
        val alone = rows.single { (label, outcomes) -> label.endsWith("learning nothing") && !outcomes.isNullOrEmpty() }.second!!
        assertEquals(3, alone.size)
        assertFalse(alone.toString(), alone.any { it.first })
        // that premise, with the same pack: an engine that learns has 泥好 first the third time
        assertEquals(Halves.HELD_OUT, Halves.of(sample))
        val learning = rows.toMap().getValue("held-out, learning")!!
        assertEquals(learning.toString(), listOf(false, false, true), learning.map { it.first })
    }
}
