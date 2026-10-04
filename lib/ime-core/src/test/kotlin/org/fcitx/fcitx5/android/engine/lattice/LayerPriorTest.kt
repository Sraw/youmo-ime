/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class LayerPriorTest {

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .layers(listOf("base", "wanxiang"))
                .unigram("<unk>", -7f, 0f)
                .unigram("打字", -3f, 0f)
                .entry("打字", intArrayOf(Syllables.id("da"), Syllables.id("zi")))
                .entry("搭子", intArrayOf(Syllables.id("da"), Syllables.id("zi")), layer = 1)
                .build().toByteArray()
        )
    )
    private val dazi = 1
    private val dazi2 = 2

    @Test
    fun theLayersWordsAreLiftedAsOneAndHeldToZero() {
        val base = WordScorer.of(data.model)
        val prior = LayerPrior.parse(data.layers, "wanxiang=2.5")
        assertEquals(2.5f, prior[1], 0f)
        assertEquals(0f, prior.of(dazi), 0f)
        assertEquals(2.5f, prior.of(dazi2), 0f)
        val scorer = prior.scorer(base)
        assertEquals(base.score(-1, -1, dazi), scorer.score(-1, -1, dazi), 1e-6f)
        assertEquals(base.score(-1, -1, dazi2) + 2.5f, scorer.score(-1, -1, dazi2), 1e-6f)
        assertEquals(base.scoreAfter(scorer.context(-1, -1), dazi2) + 2.5f, scorer.scoreAfter(scorer.context(-1, -1), dazi2), 1e-6f)
        // never above 0
        assertEquals(0f, LayerPrior.parse(data.layers, "wanxiang=20").scorer(base).score(-1, -1, dazi2), 0f)
    }

    @Test
    fun picksMoveALayerSlowlyWithinBoundsAndTheBaseNever() {
        val prior = LayerPrior(data.layers, step = 0.25f, bound = 0.6f)
        val changes = ArrayList<String>()
        prior.journal = LayerPrior.Journal { name, value -> changes += "$name=$value" }
        // went past 打字 to 搭子: up
        assertTrue(prior.learn(intArrayOf(dazi2), intArrayOf(dazi)))
        assertEquals(0.25f, prior[1], 0f)
        assertEquals(0f, prior[0], 0f)
        // took what was offered, or both have the layer: nothing
        assertFalse(prior.learn(intArrayOf(dazi2), intArrayOf(dazi2)))
        assertFalse(prior.learn(intArrayOf(dazi), intArrayOf(dazi)))
        // left 搭子 for 打字: down
        assertTrue(prior.learn(intArrayOf(dazi), intArrayOf(dazi2)))
        assertEquals(0f, prior[1], 0f)
        // bounded, and a step that changes nothing says so
        repeat(3) { prior.learn(intArrayOf(dazi), intArrayOf(dazi2)) }
        assertEquals(-0.6f, prior[1], 1e-6f)
        assertFalse(prior.learn(intArrayOf(dazi), intArrayOf(dazi2)))
        assertEquals(listOf("wanxiang=0.25", "wanxiang=0.0", "wanxiang=-0.25", "wanxiang=-0.5", "wanxiang=-0.6"), changes)
        assertEquals("wanxiang=-0.6", prior.toString())
        // words outside the dictionary (the user's own, NO_WORD) are of the base
        assertEquals(0f, prior.of(-1), 0f)
        assertEquals(0f, prior.of(data.vocabulary.size + 5), 0f)
    }

    @Test
    fun aValueIsTakenBackByNameWithinBoundsAndNotForTheBase() {
        val prior = LayerPrior(data.layers, bound = 0.5f)
        assertTrue(prior.restore("wanxiang", 0.3f))
        assertEquals(0.3f, prior[1], 0f)
        assertTrue(prior.restore("wanxiang", -2f))
        assertEquals(-0.5f, prior[1], 0f)
        assertFalse(prior.restore("base", 0.3f))
        assertFalse(prior.restore("wanxiang", Float.NaN))
        // a name of no layer of this build is kept all the same: a pack may bring it
        assertTrue(prior.restore("new2031", 0.3f))
        assertEquals(3, prior.count)
        val seen = ArrayList<String>()
        prior.forEach { name, value -> seen += "$name=$value" }
        assertEquals(listOf("wanxiang=-0.5", "new2031=0.3"), seen)
    }

    @Test
    fun aPacksLayerIsNamedLaterAndItsWordsToldApartByTheCaller() {
        val layerOf = HashMap<Int, Int>()
        val prior = LayerPrior(data.layers, step = 0.5f, extra = { layerOf[it] ?: 0 })
        // a value kept before the pack is read waits under its name
        assertTrue(prior.restore("2026q1", 0.25f))
        val q1 = prior.layer("2026q1")
        assertEquals(2, q1)
        assertEquals(q1, prior.layer("2026q1"))
        assertEquals(0.25f, prior[q1], 0f)
        val packWord = data.vocabulary.size + 3
        layerOf[packWord] = q1
        assertEquals(0.25f, prior.of(packWord), 0f)
        assertEquals(0f, prior.of(data.vocabulary.size + 4), 0f)
        assertTrue(prior.learn(intArrayOf(packWord), intArrayOf(dazi)))
        assertEquals(0.75f, prior[q1], 0f)
        assertEquals("2026q1=0.75", prior.toString())
        assertFalse(prior.restore("base", 1f))
    }

    @Test
    fun aSpecNamesLayersAndNothingElse() {
        assertEquals(0f, LayerPrior.parse(data.layers, "")[1], 0f)
        assertEquals(-1f, LayerPrior.parse(data.layers, "base=-1,wanxiang=0.5")[0], 0f)
        assertThrows(IllegalArgumentException::class.java) { LayerPrior.parse(data.layers, "new=1") }
        // a pack's layer once the pack is named, after the data's; its words found through extra
        val packed = LayerPrior.parse(data.layers, "new=1.5", listOf("new")) { 2 }
        assertEquals(2, packed.layer("new"))
        assertEquals(1.5f, packed.of(0), 0f)
        assertThrows(IllegalArgumentException::class.java) { LayerPrior.parse(data.layers, "wanxiang=x") }
        assertThrows(IllegalArgumentException::class.java) { LayerPrior(data.layers, floatArrayOf(0f)) }
        assertThrows(IllegalArgumentException::class.java) { LayerPrior(data.layers, floatArrayOf(0f, Float.NaN)) }
    }
}
