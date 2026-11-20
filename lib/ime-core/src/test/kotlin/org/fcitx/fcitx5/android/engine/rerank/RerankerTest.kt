/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

class RerankerTest {

    // 32 leaves room for the context; reading it costs a position, so these are not held to BUDGET
    private val model = TinyModel(window = 32).load()

    // the least fluent first, as though the decoder got it wrong
    private val readings = listOf("我好", "你好", "天的", "我你", "好天").let { r ->
        val s = model.Scorer().score("的", r)
        r.indices.sortedBy { s[it] }.map { r[it] }
    }
    private val lm = model.Scorer().score("的", readings)
    private val even = List(readings.size) { -5f }

    @Test
    fun whereTheDecoderIsUnsureTheModelDecides() {
        assertEquals(1, Reranker(model, budget = Int.MAX_VALUE).pick("的", readings, even))
        assertEquals(readings.lastIndex, Reranker(model, limit = readings.size, budget = Int.MAX_VALUE).pick("的", readings, even))
    }

    @Test
    fun whereItIsSureItsOrderHolds() {
        // what the model gains weighed against what the decoder loses, both in the decoder's log10
        val gain = Reranker.WEIGHT * (lm[1] - lm[0]) / ln(10f)
        assertEquals(0, Reranker(model, budget = Int.MAX_VALUE).pick("的", readings, listOf(-5f, -5f - gain - 0.01f)))
        assertEquals(1, Reranker(model, budget = Int.MAX_VALUE).pick("的", readings, listOf(-5f, -5f - gain + 0.01f)))
        assertEquals(0, Reranker(model, weight = 0f, budget = Int.MAX_VALUE).pick("的", readings, listOf(-5f, -5.01f)))
    }

    /** Whether the model puts the second of [pair] first after [context]; null if the two are near even. */
    private fun prefersSecond(context: String, pair: List<String>): Boolean? {
        val s = model.Scorer().score(context, pair)
        return if (abs(s[1] - s[0]) < 0.01f) null else s[1] > s[0]
    }

    @Test
    fun whatWasWrittenBeforeCanChangeWhichGoesFirst() {
        // the weights are random: a pair of readings two contexts disagree on is looked for
        val texts = listOf("我", "你", "好", "的", "天")
        val contexts = listOf("") + texts + texts.flatMap { a -> texts.map { a + it } }
        val pairs = readings.indices.flatMap { i -> (i + 1 until readings.size).map { listOf(readings[i], readings[it]) } }
        val (pair, toSecond, toFirst) = pairs.firstNotNullOf { candidate ->
            val by = contexts.groupBy { prefersSecond(it, candidate) }
            by[true]?.first()?.let { second -> by[false]?.first()?.let { first -> Triple(candidate, second, first) } }
        }
        assertEquals(1, Reranker(model, budget = Int.MAX_VALUE).pick(toSecond, pair, even.take(2)))
        assertEquals(0, Reranker(model, budget = Int.MAX_VALUE).pick(toFirst, pair, even.take(2)))
    }

    @Test
    fun withNoTimeTheDecoderKeepsItsOrder() {
        assertEquals(0, Reranker(model, budget = 0).pick("的", readings, even))
    }

    @Test
    fun workSpreadOverCallsGoesOnFromWhereItStopped() {
        val refiner = Reranker(model, limit = readings.size)
        var calls = 1
        while (refiner.refine("的", readings, even, 1) == null) assertTrue("never done", ++calls < 100)
        assertTrue(calls > 1)
        assertEquals(readings.lastIndex, refiner.refine("的", readings, even, 0))
    }

    @Test
    fun oneReadingIsLeftAlone() {
        assertEquals(0, Reranker(model).pick("的", readings.take(1), even))
        assertEquals(0, Reranker(model).pick("的", emptyList(), emptyList()))
    }
}
