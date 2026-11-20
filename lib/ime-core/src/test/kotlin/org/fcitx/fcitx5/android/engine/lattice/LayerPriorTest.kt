/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class LayerPriorTest {

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .layers(listOf("base", "places"))
                .unigram("<unk>", -7f, 0f)
                .unigram("打字", -3f, 0f)
                .entry("打字", intArrayOf(Syllables.id("da"), Syllables.id("zi")))
                .entry("搭子", intArrayOf(Syllables.id("da"), Syllables.id("zi")), layer = 1)
                .build().toByteArray()
        )
    )

    private fun id(word: String) = (0 until data.vocabulary.size).first { data.vocabulary.word(it) == word }

    private val dazi = id("打字")
    private val daziPal = id("搭子")

    @Test
    fun theLayersWordsAreLiftedAsOneAndHeldToZero() {
        val base = WordScorer.of(data.model)
        val prior = LayerPrior.parse(data.layers, "places=2.5")
        assertEquals(2.5f, prior[1], 0f)
        assertEquals(0f, prior.of(dazi), 0f)
        assertEquals(2.5f, prior.of(daziPal), 0f)
        val scorer = prior.scorer(base)
        assertEquals(base.score(-1, -1, dazi), scorer.score(-1, -1, dazi), 1e-6f)
        assertEquals(base.score(-1, -1, daziPal) + 2.5f, scorer.score(-1, -1, daziPal), 1e-6f)
        assertEquals(base.scoreAfter(scorer.context(-1, -1), daziPal) + 2.5f, scorer.scoreAfter(scorer.context(-1, -1), daziPal), 1e-6f)
        assertEquals(0f, LayerPrior.parse(data.layers, "places=20").scorer(base).score(-1, -1, daziPal), 0f)
    }

    @Test
    fun aPacksLayerIsNamedLaterAndItsWordsToldApartByTheCaller() {
        val layerOf = HashMap<Int, Int>()
        val prior = LayerPrior.parse(data.layers, "2026q1=0.25", listOf("2026q1"), extra = { layerOf[it] ?: -1 })
        val q1 = prior.layer("2026q1")
        assertEquals(2, q1)
        assertEquals(3, prior.layer("2026q2"))
        assertEquals(4, prior.count)
        assertEquals(0.25f, prior[q1], 0f)
        val packWord = data.vocabulary.size + 3
        layerOf[packWord] = q1
        assertEquals(0.25f, prior.of(packWord), 0f)
        assertEquals(0f, prior.of(data.vocabulary.size + 4), 0f)
        assertEquals("2026q1=0.25", prior.toString())
        // words outside the dictionary (the user's own, NO_WORD) are of the base
        assertEquals(0f, prior.of(-1), 0f)
        // a pack that puts a word of the dictionary's places in the base: the base's, as the pack scores it
        assertEquals(0f, LayerPrior.parse(data.layers, "places=2.5", extra = { if (it == daziPal) 0 else -1 }).of(daziPal), 0f)
    }

    @Test
    fun aWordOfTheUsersMappedToTheModelsTakesThatWordsLayer() {
        val d = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .layers(listOf("base", "places"))
                    .unigram("<unk>", -7f, 0f)
                    .unigram("搭子", -3f, 0f)
                    .entry("搭子", intArrayOf(Syllables.id("da"), Syllables.id("zi")), layer = 1)
                    .build().toByteArray()
            )
        )
        val user = UserModel(d.dictionary, d.vocabulary, index = d.wordIndex)
        val prior = LayerPrior.parse(d.layers, "places=2.5", extra = user)
        // added by hand at a reading the dictionary lacks: scored as the model's 搭子, so of its layer
        val byHand = user.listByHand(UserModel.Entry("搭子", intArrayOf(Syllables.id("da"), Syllables.id("zhi"))))
        assertTrue(byHand >= d.vocabulary.size)
        assertEquals(2.5f, prior.of(byHand), 0f)
        val imported = user.list(UserModel.Entry("搭子", intArrayOf(Syllables.id("ta"), Syllables.id("zi"))))
        assertEquals(0f, prior.of(imported), 0f)
    }

    @Test
    fun aPacksWordTheUserChoseAtANewReadingTakesTheModelWordsLayer() {
        val d = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .layers(listOf("base", "places"))
                    .unigram("<unk>", -7f, 0f)
                    .unigram("搭子", -3f, 0f)
                    .entry("搭子", intArrayOf(Syllables.id("da"), Syllables.id("zi")), layer = 1)
                    .build().toByteArray()
            )
        )
        val user = UserModel(d.dictionary, d.vocabulary, index = d.wordIndex)
        val prior = LayerPrior.parse(d.layers, "places=2.5,new=1", listOf("new"), extra = user)
        val new = prior.layer("new")
        val entry = UserModel.Entry("搭子", intArrayOf(Syllables.id("da"), Syllables.id("zhi")))
        val packed = user.list(entry, -5f, new, d.model.vocabularySize)
        assertEquals(1f, prior.of(packed), 0f)
        user.own(entry)
        assertEquals(2.5f, prior.of(packed), 0f)
    }

    @Test
    fun aSpecNamesLayersAndNothingElse() {
        assertEquals(0f, LayerPrior.parse(data.layers, "")[1], 0f)
        assertEquals(-1f, LayerPrior.parse(data.layers, "base=-1,places=0.5")[0], 0f)
        assertThrows(IllegalArgumentException::class.java) { LayerPrior.parse(data.layers, "new=1") }
        // a pack's layer once the pack is named, after the data's; its words found through extra
        val packed = LayerPrior.parse(data.layers, "new=1.5", listOf("new")) { 2 }
        assertEquals(2, packed.layer("new"))
        assertEquals(1.5f, packed.of(0), 0f)
        assertThrows(IllegalArgumentException::class.java) { LayerPrior.parse(data.layers, "places=x") }
        assertThrows(IllegalArgumentException::class.java) { LayerPrior.parse(data.layers, "places=NaN") }
        assertThrows(IllegalArgumentException::class.java) { LayerPrior.parse(data.layers, "places=-Infinity") }
        assertThrows(IllegalArgumentException::class.java) { LayerPrior(data.layers, floatArrayOf(0f)) }
        assertThrows(IllegalArgumentException::class.java) { LayerPrior(data.layers, floatArrayOf(0f, Float.NaN)) }
    }
}
