/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.session.Action.Forget
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Reset
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.user.KeyHabits
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PinyinSessionHabitTest {

    private val data = sessionTestData()
    private val habits = KeyHabits()
    private val scope = habits.scope("pinyin")

    private fun session(user: UserModel? = null, phrases: String = "") = PinyinSession(
        data, PinyinSegmenter(), pageSize = 20, prediction = false, user = user, habits = scope,
        phraseBook = PhraseBook(CustomPhrases.parse(phrases)),
    )

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    private fun Session.pick(keys: String, text: String): String {
        val s = type(keys)
        val i = s.candidates.indexOf(text)
        assertTrue("$text in ${s.candidates}", i >= 0)
        return apply(Select(i)).commit.also { apply(Reset) }
    }

    // 拟 for ni, then 好: 拟好, put together from pieces
    private fun Session.putTogether(): String {
        val s = type("nihao")
        val piece = apply(Select(s.candidates.indexOf("拟")))
        return apply(Select(piece.candidates.indexOf("好"))).commit.also { apply(Reset) }
    }

    @Test
    fun whatWasPutTogetherFromPiecesIsFirstWhenTheKeysAreTypedAgain() {
        val session = session()
        val before = session.type("nihao").candidates
        session.apply(Reset)
        assertEquals("你好", before.first())
        assertEquals("拟好", session.putTogether())
        val again = session.type("nihao").candidates
        assertEquals("拟好", again.first())
        // the rest as they were
        assertEquals(before.filter { it != "拟好" }, again.drop(1))
    }

    @Test
    fun aCandidatePickedAloneIsTheUserModelsToLearnNotAHabit() {
        val session = session()
        session.pick("xian", "西安")
        session.pick("ni", "拟")
        assertEquals(0, habits.size)
        assertEquals("先", session.type("xian").candidates.first())
    }

    @Test
    fun aHabitTheEngineDoesNotReadIsOfferedAsText() {
        val session = session()
        scope.learn("nihao", "你号", own = "你好")
        assertEquals(listOf("你号", "你好"), session.type("nihao").candidates.take(2))
        // forgotten from a long press, though it is no words
        assertTrue(Offer.FORGET in session.offers(0))
        session.apply(Forget(0))
        session.apply(Reset)
        assertEquals("你好", session.type("nihao").candidates.first())
        assertNull(scope.habit("nihao"))
    }

    @Test
    fun theHabitPickedCountsPassedOverItGoes() {
        val session = session()
        session.putTogether()
        session.pick("nihao", "拟好")
        assertEquals("拟好", scope.habit("nihao"))
        // twice counted, twice passed over
        session.pick("nihao", "你好")
        assertEquals("拟好", session.type("nihao").candidates.first())
        session.apply(Reset)
        session.pick("nihao", "你好")
        assertEquals("你好", session.type("nihao").candidates.first())
    }

    @Test
    fun nothingIsLearnedWhereNothingIsKept() {
        val session = session()
        session.learning = false
        session.putTogether()
        assertEquals(0, habits.size)
        // nor offered
        session.learning = true
        session.putTogether()
        session.learning = false
        assertEquals("你好", session.type("nihao").candidates.first())
    }

    @Test
    fun aWordForgottenTakesItsHabit() {
        val session = session(UserModel(data.dictionary, data.vocabulary))
        session.putTogether()
        val s = session.type("nihao")
        assertEquals("拟好", s.candidates.first())
        session.apply(Forget(0))
        assertNull(scope.habit("nihao"))
    }

    @Test
    fun keysTypedAfterAPieceAreReadWholeForTheEnginesFirst() {
        val session = session()
        // nih, 你 picked, then ao and 好: 你好, the engine's own first for nihao, needs no habit
        val start = session.type("nih")
        session.apply(Select(start.candidates.indexOf("你")))
        val rest = session.type("ao")
        assertEquals("你好", session.apply(Select(rest.candidates.indexOf("好"))).commit)
        session.apply(Reset)
        assertEquals(0, habits.size)
        // so too after a Backspace with the piece kept
        val typed = session.type("nih")
        session.apply(Select(typed.candidates.indexOf("你")))
        session.type("aoo")
        val back = session.apply(Action.Backspace)
        assertEquals("你好", session.apply(Select(back.candidates.indexOf("好"))).commit)
        assertEquals(0, habits.size)
    }

    @Test
    fun aHabitPinnedFirstStaysFirstAndOneAfterAPinnedPhraseFollowsIt() {
        scope.learn("nihao", "拟好", own = "你好")
        // pinned first itself
        assertEquals("拟好", session(phrases = "nihao,1=拟好\nnihao,2=你号").type("nihao").candidates.first())
        // another pinned first: the habit second
        assertEquals(listOf("你号", "拟好"), session(phrases = "nihao,1=你号").type("nihao").candidates.take(2))
    }

    @Test
    fun aKeyNotReadByThePinyinLeavesTheHabitFirst() {
        val session = session()
        session.putTogether()
        session.type("nihao")
        // a comma: the first candidate committed, then the comma
        val left = session.apply(Key(','))
        assertEquals("拟好", left.commit)
        assertEquals("拟好", scope.habit("nihao"))
    }

    @Test
    fun aHabitOfTheRestIsOfferedAfterAPiece() {
        scope.learn("hao", "号", own = "好")
        val session = session()
        val s = session.type("nihao")
        val after = session.apply(Select(s.candidates.indexOf("拟")))
        assertEquals("号", after.candidates.first())
    }

    @Test
    fun theLayersLearnAgainstWhatTheDecoderPutFirstNotTheHabit() {
        // as aPickPastTheFirstChoiceLiftsItsLayerUntilItComesFirst: 拟 of another layer under 你
        val layered = PinyinData.load(
            ByteBuffer.wrap(
                PinyinDataBuilder()
                    .layers(listOf("base", "new"))
                    .unigram("<unk>", -7f, 0f)
                    .unigram("你", -2f, 0f)
                    .unigram("拟", -4.5f, 0f)
                    .entry("你", syl("ni"))
                    .entry("拟", syl("ni"), layer = 1)
                    .build().toByteArray(),
            ),
        )
        val prior = LayerPrior(layered.layers, step = 1f, bound = 4f)
        val session = PinyinSession(layered, PinyinSegmenter(), prior = prior, prediction = false, habits = scope)
        scope.learn("ni", "拟", own = "你")
        // the decoder's 你 passed over for the habit: no word of the habit's, so nothing
        assertEquals(listOf("拟", "你"), session.type("ni").candidates)
        session.apply(Select(1))
        assertEquals(0f, prior[1], 0f)
        // which halved the habit: learned again
        scope.learn("ni", "拟", own = "你")
        // the habit taken over the decoder's first: its layer lifted, till the decoder has it first
        repeat(3) {
            session.type("ni")
            assertEquals("拟", session.apply(Select(0)).commit)
        }
        assertEquals(3f, prior[1], 0f)
        // the decoder's own first now, the habit too: taking it teaches nothing more
        session.type("ni")
        session.apply(Select(0))
        assertEquals(3f, prior[1], 0f)
    }
}
