/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Pin
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.session.Action.Unpin
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

/** A pinyin session's custom phrases: offered, and pinned or deleted from a candidate. */
class PinyinSessionPhraseTest {

    private val data = sessionTestData()

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    private fun phrased(text: String, user: UserModel? = null, saved: MutableList<String> = ArrayList()) = PinyinSession(
        data, PinyinSegmenter(), pageSize = 20, user = user,
        phraseBook = PhraseBook(CustomPhrases.parse(text)) { saved += it.all.joinToString(" ") },
        now = { GregorianCalendar(2026, Calendar.SEPTEMBER, 30) },
    )

    @Test
    fun aCandidatePinnedIsTheFirstPhraseOfTheInputTillDeleted() {
        val saved = ArrayList<String>()
        val session = phrased("nizai,2=你在哪儿", saved = saved)
        val s = session.type("nizai")
        val text = s.candidates[2]
        val pinned = session.apply(Pin(2))
        assertEquals(text, pinned.candidates[0])
        assertEquals(s.preedit, pinned.preedit)
        assertEquals("", pinned.commit)
        assertEquals(listOf("nizai,1=$text nizai,2=你在哪儿"), saved)
        assertEquals(setOf(Offer.UNPIN), session.offers(0))
        // a phrase not first yet: pinned again, or deleted
        val second = pinned.candidates.indexOf("你在哪儿")
        assertEquals(setOf(Offer.PIN, Offer.UNPIN), session.offers(second))
        assertEquals(setOf(Offer.PIN), session.offers(pinned.candidates.indexOf(s.candidates[0])))
        val unpinned = session.apply(Unpin(0))
        assertEquals(s.candidates, unpinned.candidates)
        assertEquals("nizai,2=你在哪儿", saved.last())
        // not a phrase, or past the candidates: nothing to do
        assertEquals(unpinned, session.apply(Unpin(0)))
        assertEquals(unpinned, session.apply(Pin(99)))
        assertEquals(2, saved.size)
    }

    @Test
    fun aPhraseIsPinnedAsWrittenUnderWhatIsLeftOfTheInput() {
        val saved = ArrayList<String>()
        val session = phrased("zai,1=再\nzai,2=#${'$'}{day}日", saved = saved)
        val s = session.type("nizai")
        val rest = session.apply(Select(s.candidates.indexOf("拟")))
        assertEquals(listOf("再", "30日"), rest.candidates.take(2))
        session.apply(Pin(1))
        // filled in again each time it is offered, not the day it was pinned
        assertEquals("zai,1=#${'$'}{day}日 zai,1=再", saved.single())
        assertEquals("30日", session.candidates(0, 1).single().text)
    }

    @Test
    fun onlyACandidateReadingAllTheInputIsPinnedAndNotWhereNothingIsKept() {
        val saved = ArrayList<String>()
        val session = phrased("", saved = saved)
        val s = session.type("nihao")
        // 你 reads ni: pinned to nihao, it would drop hao each time
        val short = s.candidates.indexOf("你")
        assertTrue(short > 0)
        assertEquals(emptySet<Offer>(), session.offers(short))
        assertEquals(s, session.apply(Pin(short)))
        session.learning = false
        assertEquals(emptySet<Offer>(), session.offers(0))
        assertEquals(s, session.apply(Pin(0)))
        assertTrue(saved.isEmpty())
    }

    @Test
    fun whatIsTypedWithASeparatorIsNoKeyToPinTo() {
        val saved = ArrayList<String>()
        val session = phrased("", saved = saved)
        val s = session.type("xi'an")
        assertEquals(emptySet<Offer>(), session.offers(0))
        assertEquals(s, session.apply(Pin(0)))
        assertTrue(saved.isEmpty())
    }

    @Test
    fun aPredictionIsNeitherPinnedNorDeleted() {
        val saved = ArrayList<String>()
        val session = phrased("wo,1=我", saved = saved)
        session.type("wo")
        val predicted = session.apply(Select(0))
        assertTrue(predicted.predicting)
        assertEquals(emptySet<Offer>(), session.offers(0))
        assertEquals(predicted.copy(commit = ""), session.apply(Pin(0)))
        assertEquals(predicted.copy(commit = ""), session.apply(Unpin(0)))
        assertTrue(saved.isEmpty())
    }

    @Test
    fun aPhraseIsOfferedWhereItsOrderSaysForItsKey() {
        val session = phrased("nizai,2=你在哪儿\nzai,1=#${'$'}{month}月${'$'}{day}日\nnizai,-1=不要")
        val s = session.type("nizai")
        assertEquals("你在", s.candidates[0])
        assertEquals("你在哪儿", s.candidates[1])
        assertFalse("不要" in s.candidates)
        assertEquals("你在哪儿", session.apply(Select(1)).commit)
        // what is left of the input, after a piece picked; filled in with the time
        val t = session.type("nizai")
        assertFalse("9月30日" in t.candidates)
        val rest = session.apply(Select(t.candidates.indexOf("拟")))
        assertEquals("9月30日", rest.candidates.first())
        assertEquals("拟9月30日", session.apply(Select(0)).commit)
    }

    @Test
    fun aCandidateOfAPhrasesTextThatReadsLessGivesWayToIt() {
        // 你 reads ni only: the phrase is what nihao means, and takes it all
        val session = phrased("nihao,1=你")
        val s = session.type("nihao")
        assertEquals("你", s.candidates[0])
        assertEquals(1, s.candidates.count { it == "你" })
        assertEquals("你", session.apply(Select(0)).commit)
    }

    @Test
    fun aPhraseIsNotLearnedButACandidateItMovedIs() {
        val user = UserModel(data.dictionary, data.vocabulary)
        val session = phrased("nizai,1=你在哪儿\nni,1=拟", user)
        assertEquals("你在哪儿", session.type("nizai").candidates.first())
        session.apply(Select(0))
        assertEquals(0f, user.total)
        // 拟 moved first by a phrase is still the decoder's word, not its best
        assertEquals("拟", session.type("ni").candidates.first())
        session.apply(Select(0))
        assertEquals(1f, user.total)
    }
}
