/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.pinyinDataOf
import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.session.Action.Pick
import org.fcitx.fcitx5.android.engine.session.Action.Reset
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 前文 (「光标前最近的那个词」), learned after and looked up after alike: the last word as the model reads the text. */
class PinyinSessionWordBeforeTest {

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Action.Key(it)) }.last()

    // the 前文 is the last word as the model reads the text, not the longest word it ends with
    private val pieces: PinyinData by lazy {
        pinyinDataOf(
            Triple("他", "ta", -2f),
            Triple("点", "dian", -3f),
            Triple("了", "le", -2f),
            Triple("了他", "le ta", -6f),
            Triple("点了", "dian le", -3.5f),
            Triple("带来", "dai lai", -3f),
            Triple("说", "shuo", -2.5f),
            Triple("我", "wo", -2f),
            Triple("和", "he", -2f),
            Triple("你", "ni", -2f),
            Triple("我和你", "wo he ni", -7f),
            Triple("好", "hao", -2.5f),
        )
    }

    // 点了 picked twice after 他 is first after 他点了他 too, its 前文 他
    @Test
    fun theWordBeforeIsTheLastAsTheModelReadsTheText() {
        val learner = Learner(pieces)
        val session = PinyinSession(pieces, PinyinSegmenter(), user = learner.model, prediction = false, learnTo = learner.learnTo())
        fun pairs() = learner.log.state.pairs.keys.map { it.prev + "|" + it.next }
        fun dl(before: String): List<String> {
            session.apply(Reset)
            session.apply(Action.Context(before))
            return session.type("dl").candidates
        }
        assertEquals("带来", dl("他点了他").first())
        repeat(2) {
            val shown = dl("我说他")
            assertEquals("点了", session.apply(Pick(shown.indexOf("点了"))).commit)
        }
        assertEquals(listOf("他|点了"), pairs())
        assertEquals("点了", dl("他点了他").first())
        assertEquals(HabitRanker.Tier.FIRST_HERE, session.why(0)!!.tier)
        session.apply(Reset)
        session.apply(Action.Context("我和你"))
        assertEquals("好", session.type("hao").let { session.apply(Select(0)) }.commit)
        assertTrue("你|好" in pairs())
    }

    @Test
    fun aWordPickedTwiceAfterAnUnpinIsFirst() {
        val learner = Learner(pieces)
        val session = PinyinSession(pieces, PinyinSegmenter(), user = learner.model, prediction = false, learnTo = learner.learnTo())
        fun dl(): List<String> {
            session.apply(Reset)
            session.apply(Action.Context("他"))
            return session.type("dl").candidates
        }
        // 带来 used more elsewhere: 点了 is not first across the words before, only after 他
        repeat(3) {
            session.apply(Reset)
            session.apply(Action.Context("我"))
            session.type("dl")
            session.apply(Pick(0))
        }
        assertEquals("带来", dl().first())
        session.apply(Pick(session.candidates(0, 20).map { it.text }.indexOf("点了")))
        dl()
        session.apply(Action.Pin(session.candidates(0, 20).map { it.text }.indexOf("点了")))
        assertEquals("点了", session.candidates(0, 20).first().text)
        session.apply(Pick(session.candidates(0, 20).map { it.text }.indexOf("带来")))
        dl()
        session.apply(Action.Unpin(session.candidates(0, 20).map { it.text }.indexOf("点了")))
        session.apply(Pick(session.candidates(0, 20).map { it.text }.indexOf("点了")))
        assertEquals("带来", dl().first())
        session.apply(Pick(session.candidates(0, 20).map { it.text }.indexOf("点了")))
        assertEquals("点了", dl().first())
    }
}
