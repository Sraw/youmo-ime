/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/** [Penalties.tap] on the graph's [against][org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.against] (design k14-tap-position §2.3). */
class PinyinDecoderTapsTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    // 不好 0.7 above 你好, as the model has them (−4.45 to −5.15)
    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("不好", -3f, 0f)
                .unigram("你好", -3.75f, 0f)
                .entry("不好", syl("bu", "hao"))
                .entry("你好", syl("ni", "hao"))
                .build().toByteArray(),
        ),
    )

    private val segmenter = T9Segmenter(abbreviations = false, letters = NineKeyLetters.G14)
    private val decoder = PinyinDecoder(data.dictionary, data.vocabulary, WordScorer.of(data.model), Penalties(), PinyinDecoder.DEFAULT_BEAM)
    private val input = NineKeyLetters.G14.typings("nihao").single()

    private fun first(taps: FloatArray?) = decoder.decode(segmenter.segment(input, taps)).sentences.first()

    @Test
    fun tapsOnTheLettersHalvesTurnTheFirstReading() {
        assertEquals("不好", first(null).text)
        // n, i, h right; a, o left
        assertEquals("你好", first(floatArrayOf(0.75f, 0.75f, 0.75f, 0.25f, 0.25f)).text)
        assertEquals("不好", first(floatArrayOf(0.25f, 0.25f, 0.75f, 0.25f, 0.25f)).text)
    }

    @Test
    fun tapsAtTheCentreScoreAsNoneAndThePlaceCostsItsLean() {
        val plain = decoder.decode(segmenter.segment(input))
        val centre = decoder.decode(segmenter.segment(input, FloatArray(5) { 0.5f }))
        assertEquals(plain.sentences.map { it.text to it.score }, centre.sentences.map { it.text to it.score })
        // b at BN's right edge: 1 of the key's lean, Penalties.tap a whole
        val edge = decoder.decode(segmenter.segment(input, floatArrayOf(1f, 0.5f, 0.5f, 0.5f, 0.5f)))
        val bu = { d: Decoding -> d.sentences.first { it.text == "不好" }.score }
        assertEquals(bu(plain) + Penalties().tap, bu(edge), 1e-4f)
    }

    @Test
    fun aPlaceChangedBeforeTheEndIsSearchedAgain() {
        // the same decoder, its last lattice kept for the next key: the taps differ on the first key
        val left = floatArrayOf(0.25f, 0.25f, 0.75f, 0.25f, 0.25f)
        val right = floatArrayOf(0.75f, 0.75f, 0.75f, 0.25f, 0.25f)
        assertEquals("不好", first(left).text)
        assertEquals("你好", first(right).text)
        assertEquals("不好", first(left).text)
    }
}
