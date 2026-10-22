/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.corrected
import org.fcitx.fcitx5.android.engine.learn.row
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** 小穴 came first for xiaoxue after 妈妈的, its fourth at a lower score still too high. */
class CoarseCandidatesTest {

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                // the model's likeliest of the reading, as the web taught it
                .unigram("小穴", -1f, 0f)
                .unigram("小学", -3f, 0f)
                .unigram("小雪", -3.5f, 0f)
                .unigram("小", -2f, 0f)
                .unigram("学", -3f, 0f)
                .unigram("穴", -1f, 0f)
                .entry("小穴", syl("xiao", "xue"))
                .entry("小学", syl("xiao", "xue"))
                .entry("小雪", syl("xiao", "xue"))
                .entry("小", syl("xiao"))
                .entry("学", syl("xue"))
                .entry("穴", syl("xue"))
                .build().toByteArray(),
        ),
    )

    private fun session(user: UserModel? = null) = PinyinSession(data, PinyinSegmenter(), pageSize = 2, user = user, prediction = false)

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    @Test
    fun aCandidateWithACoarseWordWaitsPastTheFirstPage() {
        val s = session()
        val first = s.type("xiaoxue")
        assertEquals(listOf("小学", "小雪"), first.candidates)
        assertEquals("小穴", s.apply(Action.NextPage).candidates.first())
    }

    @Test
    fun theEmojiAreNotThoseOfAReadingHeldPastTheFirstPage() {
        val emoji = mapOf("小穴" to listOf("🍑"), "小学" to listOf("🏫"))
        val s = PinyinSession(data, PinyinSegmenter(), pageSize = 2, prediction = false, emoji = { emoji[it].orEmpty() })
        val listed = s.type("xiaoxue").candidates + s.apply(Action.NextPage).candidates
        assertTrue("🏫" in listed)
        assertTrue("🍑" !in listed)
    }

    @Test
    fun soDoesOneSpeltByWordsInTurn() {
        // 小 then 穴, each likelier than the word: as many characters, the same text
        val s = session().type("xiaoxue")
        assertTrue(s.candidates.none { it == "小穴" })
    }

    @Test
    fun wordsInTurnTheUserTypedAreTheirsToo() {
        // 小 and 穴 each far likelier than the word: read as the two, whatever the user typed
        val split = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .unigram("<unk>", -7f, 0f)
                    .unigram("小穴", -9f, 0f)
                    .unigram("小学", -3f, 0f)
                    .unigram("小雪", -3.5f, 0f)
                    .unigram("小", -0.1f, 0f)
                    .unigram("穴", -0.1f, 0f)
                    .entry("小穴", syl("xiao", "xue"))
                    .entry("小学", syl("xiao", "xue"))
                    .entry("小雪", syl("xiao", "xue"))
                    .entry("小", syl("xiao"))
                    .entry("穴", syl("xue"))
                    .build().toByteArray(),
            ),
        )
        fun first(user: UserModel?) = PinyinSession(split, PinyinSegmenter(), pageSize = 2, user = user, prediction = false)
            .type("xiaoxue").candidates.take(2)
        assertTrue("小穴" !in first(null))
        val user = UserModel(split.dictionary, split.vocabulary)
        user.learn(null, listOf(UserModel.Entry("小穴", syl("xiao", "xue"))))
        assertEquals("小穴", first(user).first())
    }

    @Test
    fun onceTheUserTypedItItIsTheirs() {
        val user = UserModel(data.dictionary, data.vocabulary)
        user.learn(null, listOf(UserModel.Entry("小穴", syl("xiao", "xue"))))
        assertTrue("小穴" in session(user).type("xiaoxue").candidates)
    }

    @Test
    fun aWordTheUserAddedIsTheirs() {
        // added by hand, it is listed with no count: never typed, yet asked for
        val s = PinyinSession(data, PinyinSegmenter(), pageSize = 2, prediction = false, addedWords = { setOf("小穴") })
        assertTrue("小穴" in s.type("xiaoxue").candidates)
    }

    /**
     * Only the first page waits: one found among the words read as the list was shown further put
     * itself at the second page's start, every candidate listed since one place on, so a tap on
     * one the keyboard had fetched picked its neighbour; one lower down moved up to there too.
     */
    @Test
    fun aCoarseWordPastTheFirstPageKeepsItsPlaceAndMovesNothing() {
        // 小穴 41st of the words read at first, 骚穴 last, read only as the list is shown that far
        val words = (0 until 100).map { (0x4E00 + it).toChar().toString().repeat(2) }
        val many = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder().unigram("<unk>", -7f, 0f)
                    .apply { words.forEachIndexed { i, w -> unigram(w, -1f - i / 8f, 0f).entry(w, syl("xiao", "xue")) } }
                    .unigram("小穴", -2.06f, 0f).entry("小穴", syl("xiao", "xue"))
                    .unigram("骚穴", -9.5f, 0f).entry("骚穴", syl("xiao", "xue"))
                    .build().toByteArray(),
            ),
        )
        val session = PinyinSession(many, PinyinSegmenter(), pageSize = 10, prediction = false)
        session.type("xiaoxue")
        val shown = session.candidates(0, 40).map { it.text }
        val all = session.candidates(0, 200).map { it.text }
        assertEquals(shown, all.take(40))
        assertTrue(all.take(10).none { it == "小穴" || it == "骚穴" })
        assertTrue(all.indexOf("小穴") > 10)
        assertTrue(all.indexOf("骚穴") >= PinyinDecoder.DEFAULT_WORDS)
    }

    // design-1b S7: a coarse word pinned is the user's choice, never held
    @Test
    fun aCoarseWordPinnedIsFirst() {
        val learner = Learner(data)
        learner.habits.pin(Code(Kinds.FULL, "xiaoxue"), "小穴", "xiao xue", on = true)
        val s = PinyinSession(data, PinyinSegmenter(), pageSize = 2, user = learner.model, prediction = false, learnTo = learner.learnTo())
        assertEquals("小穴", s.type("xiaoxue").candidates.first())
    }

    // review-1b N2: one of the user's own, unused for 30 typing days, is theirs still: not held
    @Test
    fun aCoarseWordOfTheirOwnUnusedIsNotHeld() {
        val learner = Learner(data)
        val s = PinyinSession(data, PinyinSegmenter(), pageSize = 2, user = learner.model, prediction = false, learnTo = learner.learnTo())
        repeat(2) { learner.habits.record(listOf(corrected("xiaoxue", "小穴", "xiao xue", origin = Row.NONE))) }
        assertTrue(learner.log.state.mine.containsKey("小穴"))
        learner.clock.days(1)
        repeat(31) {
            learner.clock.days(1)
            learner.habits.record(listOf(row("xiao", "小", "xiao")))
        }
        assertTrue("小穴" in s.type("xiaoxue").candidates)
    }
}
