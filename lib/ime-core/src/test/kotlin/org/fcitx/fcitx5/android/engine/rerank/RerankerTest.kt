/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.rerank

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class RerankerTest {

    private val model = TinyModel().load()

    // the least fluent first, as though the decoder got it wrong
    private val readings = listOf("我好", "你好", "天的", "我你", "好天").let { r ->
        val s = model.Scorer().score("的", r)
        r.indices.sortedBy { s[it] }.map { r[it] }
    }
    private val lm = model.Scorer().score("的", readings)
    private val even = List(readings.size) { -5f }

    @Test
    fun whereTheDecoderIsUnsureTheModelDecides() {
        assertEquals(1, Reranker(model).pick("的", readings, even))
        assertEquals(readings.lastIndex, Reranker(model, limit = readings.size, budget = Int.MAX_VALUE).pick("的", readings, even))
    }

    @Test
    fun whereItIsSureItsOrderHolds() {
        // what the model gains weighed against what the decoder loses, both in nats
        val gain = Reranker.WEIGHT * (lm[1] - lm[0]) / ln(10f)
        assertEquals(0, Reranker(model).pick("的", readings, listOf(-5f, -5f - gain - 0.01f)))
        assertEquals(1, Reranker(model).pick("的", readings, listOf(-5f, -5f - gain + 0.01f)))
        assertEquals(0, Reranker(model, weight = 0f).pick("的", readings, listOf(-5f, -5.01f)))
    }

    @Test
    fun withNoTimeTheDecoderKeepsItsOrder() {
        assertEquals(0, Reranker(model, budget = 0).pick("的", readings, even))
    }

    @Test
    fun oneReadingIsLeftAlone() {
        assertEquals(0, Reranker(model).pick("的", readings.take(1), even))
        assertEquals(0, Reranker(model).pick("的", emptyList(), emptyList()))
    }
}
