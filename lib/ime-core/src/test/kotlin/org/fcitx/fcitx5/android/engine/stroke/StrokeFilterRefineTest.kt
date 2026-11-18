/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.stroke

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.sessionTestData
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/** [StrokeFilter] over a [PinyinSession] whose sentence models reorder its list while strokes are drawn. */
class StrokeFilterRefineTest {

    // a 0.6.1 user drew 折 under gj and tapped 好看: 估计 was committed. The words the user's habits rank filled the
    // first page, so the refiner's pick went in after them, past the page the filter compared; a pin does so here
    @Uat("PY-157")
    @Test
    fun aRefineThatReordersPastTheFirstPageLeavesATapTheCandidateTapped() {
        val data = sessionTestData()
        val learner = Learner(data)
        var weighed = false
        val refiner = SentenceRefiner { _, readings, _, _ -> if (weighed) readings.lastIndex else null }
        val session = PinyinSession(data, PinyinSegmenter(), pageSize = 1, prediction = false, user = learner.model, learnTo = learner.learnTo(), refiner = refiner)
        val drawn = "...\n你\thh\n拟\tss"
        val filter = StrokeFilter(session, { Strokes(CodeTable.load(ByteBuffer.wrap(Strokes.read(drawn.reader().buffered(), "stroke").build().toByteArray()))) { 0f } }, pageSize = 1)
        fun type(keys: String) = keys.map { filter.apply(Action.Key(it)) }.last()
        assertEquals("你在", type("nizai").candidates.single())
        filter.apply(Action.Pin(0))
        filter.apply(Action.Reset)
        type("nizai")
        assertEquals(listOf("你在", "你再", "拟再", "拟在", "你", "拟"), session.candidates(0, 10).map { it.text })
        filter.apply(Action.Stroke('h'))
        assertEquals(listOf("你在", "你", "你再"), filter.candidates(0, 10).map { it.text })
        weighed = true
        // the pin stays first, the refiner's pick after it: the page the session shows is the same
        assertEquals(listOf("你在"), filter.apply(Action.Refine).candidates)
        assertEquals(listOf("你在", "拟在", "你再", "拟再", "你", "拟"), session.candidates(0, 10).map { it.text })
        assertEquals(listOf("你在", "你", "你再"), filter.candidates(0, 10).map { it.text })
        assertEquals("你再", filter.apply(Action.Pick(2)).commit)
    }
}
