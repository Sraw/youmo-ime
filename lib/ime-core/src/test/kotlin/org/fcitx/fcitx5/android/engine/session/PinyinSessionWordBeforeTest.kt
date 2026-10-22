/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.session.Action.Pick
import org.fcitx.fcitx5.android.engine.session.Action.Reset
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** The 前文 (U-02 「光标前最近的那个词」), learned after and looked up after alike: the last word as the model reads the text. */
class PinyinSessionWordBeforeTest {

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Action.Key(it)) }.last()

    // 了他 and 我和你 words of the model's, each less likely than its pieces: the 前文 is the last
    // word as the model reads the text (「光标前最近的那个词」), not the longest word it ends with
    private val pieces: PinyinData by lazy {
        PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .unigram("他", -2f, 0f).unigram("点", -3f, 0f).unigram("了", -2f, 0f).unigram("了他", -6f, 0f)
                    .unigram("点了", -3.5f, 0f).unigram("带来", -3f, 0f).unigram("说", -2.5f, 0f)
                    .unigram("我", -2f, 0f).unigram("和", -2f, 0f).unigram("你", -2f, 0f).unigram("我和你", -7f, 0f)
                    .unigram("好", -2.5f, 0f)
                    .entry("他", syl("ta")).entry("点", syl("dian")).entry("了", syl("le")).entry("了他", syl("le", "ta"))
                    .entry("点了", syl("dian", "le")).entry("带来", syl("dai", "lai")).entry("说", syl("shuo"))
                    .entry("我", syl("wo")).entry("和", syl("he")).entry("你", syl("ni")).entry("我和你", syl("wo", "he", "ni"))
                    .entry("好", syl("hao"))
                    .build().toByteArray(),
            ),
        )
    }

    // uat-triage-pinyin T9-54: 点了 picked twice after 他 is first after 他点了他 too, its 前文 他
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
        // first there for the 前文 他, not across every word before (U-02 rule 2)
        assertEquals(HabitRanker.Tier.FIRST_HERE, session.why(0)!!.tier)
        session.apply(Reset)
        // what follows a phrase of the model's: after its last word
        session.apply(Action.Context("我和你"))
        assertEquals("好", session.type("hao").let { session.apply(Select(0)) }.commit)
        assertTrue("你|好" in pairs())
    }

    // UAT PY-80 (F2739): 点了 pinned, 带来 the decoder's first picked over it, unpinned, 点了 picked
    // twice running over 带来: first after 他, the first pick after the unpin a correction too
    @Test
    fun aWordPickedTwiceAfterAnUnpinIsFirst() {
        val learner = Learner(pieces)
        val session = PinyinSession(pieces, PinyinSegmenter(), user = learner.model, prediction = false, learnTo = learner.learnTo())
        fun dl(): List<String> {
            session.apply(Reset)
            session.apply(Action.Context("他"))
            return session.type("dl").candidates
        }
        // 带来 used more elsewhere: 点了 is not first across the words before (U-02 rule 3), only after 他
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
