/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.HabitRanker.Tier
import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.Words
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** [PredictionOrder]: 联想 ranked by the user's picks after the word before (U-70). */
class PredictionOrderTest {

    // after 你: 好 吗 呀 啊 你们, most probable first; 们 after nothing
    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("我", -2f, 0f)
                .unigram("好", -2.5f, 0f)
                .unigram("吗", -3f, 0f)
                .unigram("呀", -3f, 0f)
                .unigram("啊", -3f, 0f)
                .unigram("们", -3f, 0f)
                .unigram("你们", -3f, 0f)
                .bigram("你", "好", -0.5f, 0f)
                .bigram("你", "吗", -1f, 0f)
                .bigram("你", "呀", -1.5f, 0f)
                .bigram("你", "啊", -2f, 0f)
                .bigram("你", "你们", -2.5f, 0f)
                .entry("你", syl("ni"))
                .entry("我", syl("wo"))
                .entry("好", syl("hao"))
                .entry("吗", syl("ma"))
                .entry("呀", syl("ya"))
                .entry("啊", syl("a"))
                .entry("们", syl("men"))
                .entry("你们", syl("ni", "men"))
                .build().toByteArray(),
        ),
    )

    private val learner = Learner(data)
    private val user = learner.model
    private val predictor = Predictor(data.model, data.vocabulary, data.dictionary, user)
    private val order = PredictionOrder(predictor) { user.idsOfText(it).firstOrNull() ?: NO_WORD }

    private fun id(word: String) = (0 until data.vocabulary.size).first { data.vocabulary.word(it) == word }

    private fun offer(prev: String = "你", prev2: Int = NO_WORD, habits: Habits = learner.habits) = order.offer(habits, prev2, id(prev))

    private fun texts(prev: String = "你", prev2: Int = NO_WORD) = offer(prev, prev2).candidates.map { it.text }

    // [text] picked after [after] with [first] shown first, as a session files it (CommitRecorder.prediction, Predictions.pick)
    private fun picked(text: String, after: String, first: String, flags: Int = 0): Commit? =
        learner.habits.record(listOf(PredictionOrder.row(text, after, first)), flags = flags)

    // a pick of [text] among those shown
    private fun pick(text: String, after: String = "你", flags: Int = 0): Commit? {
        val shown = texts(after)
        assertTrue("$text among $shown", text in shown)
        return picked(text, after, shown.first(), flags)
    }

    @Test
    fun nothingPickedIsTheModelsOrder() {
        assertEquals(listOf("好", "吗", "呀", "啊", "你们"), texts())
        val o = offer()
        assertEquals("你", o.after)
        assertEquals(listOf(Tier.DICTIONARY), o.candidates.map { o.ranks.getValue(it).tier }.distinct())
        assertEquals(PredictionOrder.Offer.NONE, order.offer(learner.habits, NO_WORD, NO_WORD))
    }

    @Test
    fun pickedOnceGoesBeforeThoseNeverPickedTwiceRunningFirst() {
        pick("啊")
        assertEquals(listOf("好", "啊", "吗", "呀", "你们"), texts())
        val o = offer()
        assertEquals(Tier.USED, o.ranks.getValue(o.candidates[1]).tier)
        pick("啊")
        assertEquals(listOf("啊", "好", "吗", "呀", "你们"), texts())
        val first = offer()
        assertEquals(Tier.FIRST_HERE, first.ranks.getValue(first.candidates[0]).tier)
    }

    // U-91: a 联想 picked in a search field (or a URL's) ranks nothing, and taken back takes nothing
    // back of a pick elsewhere
    @Test
    fun aPickInASearchFieldRanksNothing() {
        for (flags in listOf(Commit.NO_PREDICT, Commit.NO_PAIRS)) repeat(2) { pick("啊", flags = flags) }
        assertEquals(listOf("好", "吗", "呀", "啊", "你们"), texts())
        pick("啊")
        val search = checkNotNull(pick("啊", flags = Commit.NO_PREDICT))
        learner.log.undo(search.seq)
        assertEquals(listOf("好", "啊", "吗", "呀", "你们"), texts())
    }

    @Test
    fun theFirstPickedBetweenStartsTheRunAgain() {
        pick("啊")
        pick("好")
        pick("啊")
        assertEquals(listOf("好", "啊", "吗", "呀", "你们"), texts())
    }

    @Test
    fun usedMoreGoesFirstAmongThePicked() {
        pick("呀")
        pick("好")
        pick("啊")
        pick("好")
        pick("啊")
        pick("好")
        // 啊 twice, 呀 once: by uses, after the first
        assertEquals(listOf("好", "啊", "呀", "吗", "你们"), texts())
    }

    // U-70: 联想 has no 前文 beyond the word it follows
    @Test
    fun theWordBeforeTheWordFollowedChangesNothing() {
        pick("啊")
        pick("啊")
        assertEquals(listOf("啊", "好", "吗", "呀", "你们"), texts(prev2 = id("我")))
        // after another word, nothing of it
        assertTrue("啊" !in texts("我"))
    }

    @Test
    fun aWordPickedThatTheBestNoLongerHoldIsPutBackAmongThem() {
        // 们 picked after 你 once (offered then by what the user typed, say)
        picked("们", "你", "好")
        val o = offer()
        assertEquals(listOf("好", "们", "吗", "呀", "啊", "你们"), o.candidates.map { it.text })
        assertEquals(Tier.USED, o.ranks.getValue(o.candidates[1]).tier)
        // one blocked since is not, nor one turned off there (U-72)
        user.predictionsOff = { prev, next -> prev == "你" && next == "们" }
        assertEquals(listOf("好", "吗", "呀", "啊", "你们"), texts())
        user.predictionsOff = { _, _ -> false }
        user.block(Entry("们", syl("men")))
        assertEquals(listOf("好", "吗", "呀", "啊", "你们"), texts())
    }

    // 原 PY-71: offered after the word as put in (你们, read 你 们): a word put back is turned off after it too, not after the decoder's piece
    @Test
    fun aWordPutBackAfterTheWholeWordIsTurnedOffAfterIt() {
        picked("们", "你们", "好")
        fun offerAfterWhole() = order.offer(learner.habits, NO_WORD, id("你"), learnedAfter = id("你们"))
        assertEquals("们" to "你们", offerAfterWhole().let { o -> o.candidates.map { it.text }.single { it == "们" } to o.after })
        user.predictionsOff = { prev, next -> prev == "你们" && next == "们" }
        val o = offerAfterWhole()
        assertTrue(o.candidates.map { it.text }.toString(), o.candidates.none { it.text == "们" })
        assertEquals("你们", o.after)
        assertEquals("你们", predictor.after)
    }

    @Test
    fun aWordOfTheUsersGoesBeforeTheModelsNeverPicked() {
        // 你们, put in twice where it was no word of the dictionary's: the user's (U-06), after the first, before the rest (U-02)
        repeat(2) { learner.habits.record(listOf(Row(Row.PICK or Row.WHOLE, Code(Kinds.FULL, "nimen"), "", Words.of("你们", "ni men"), 'A', Row.NONE, "你们"))) }
        assertTrue(learner.habits.mineOrder("你们") >= 0)
        assertEquals(listOf("好", "你们", "吗", "呀", "啊"), texts())
    }

    // U-71: what was put in after a word, any way, three times
    @Test
    fun aWordPutInAfterTheWordThreeTimesIsOfferedThoughTheModelLacksIt() {
        fun typed() = learner.habits.record(listOf(Row(Row.PICK or Row.WHOLE, Code(Kinds.FULL, "men"), "你", Words.of("们", "men"), 'A', Row.DICTIONARY, "们")))
        repeat(Predictor.PAIRS - 1) { typed() }
        assertTrue("们" !in texts())
        typed()
        assertTrue("们" in texts())
        // a table's or handwriting's word, of no reading, as well
        repeat(Predictor.PAIRS) {
            learner.habits.record(listOf(Row(Row.PICK or Row.WHOLE, Code(Kinds.HANDWRITING, "们"), "我", Words.of("们"), 'A', Row.DICTIONARY, "们")))
        }
        assertEquals(listOf("们"), texts("我"))
    }

    @Test
    fun nothingLearnedRanksNothing() {
        pick("啊")
        pick("啊")
        assertEquals(listOf("好", "吗", "呀", "啊", "你们"), offer(habits = Habits.OFF).candidates.map { it.text })
    }

    // more than a page of the predictor's own: one picked from the second page is ranked too
    @Test
    fun moreAreRankedThanThePredictorOffersAlone() {
        assertTrue(PredictionOrder.RANKED > Predictor.DEFAULT_LIMIT)
    }
}
