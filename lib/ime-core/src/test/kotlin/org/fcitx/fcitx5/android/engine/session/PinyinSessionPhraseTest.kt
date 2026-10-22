/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.phrase.PhraseBook
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
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

    private fun phrased(text: String, user: UserModel? = null, saved: MutableList<String> = ArrayList(), learner: Learner? = null) = PinyinSession(
        data, PinyinSegmenter(), pageSize = 20, user = learner?.model ?: user,
        phraseBook = PhraseBook(CustomPhrases.parse(text)) { saved += it.all.joinToString(" ") },
        now = { GregorianCalendar(2026, Calendar.SEPTEMBER, 30) },
        learnTo = learner?.learnTo() ?: LearnTo(),
    )

    @Test
    fun aCandidatePinnedGoesInFrontTillUnpinnedThePhrasesUntouched() {
        val saved = ArrayList<String>()
        val session = phrased("nizai,2=你在哪儿", saved = saved, learner = Learner(data))
        val s = session.type("nizai")
        val text = s.candidates[2]
        val pinned = session.apply(Pin(2))
        // in front, the rest as they were (impl/design-1b.md M4), the pin the learning record's
        assertEquals(listOf(text) + s.candidates.filter { it != text }, pinned.candidates)
        assertEquals(s.preedit, pinned.preedit)
        assertEquals("", pinned.commit)
        assertTrue(saved.isEmpty())
        assertTrue(Offer.UNPIN in session.offers(0))
        // a phrase is the settings': neither pinned nor unpinned here
        val phrase = pinned.candidates.indexOf("你在哪儿")
        assertFalse(Offer.PIN in session.offers(phrase) || Offer.UNPIN in session.offers(phrase))
        assertTrue(Offer.PIN in session.offers(pinned.candidates.indexOf(s.candidates[0])))
        val unpinned = session.apply(Unpin(0))
        assertEquals(s.candidates, unpinned.candidates)
        // not pinned, or past the candidates: nothing to do
        assertEquals(unpinned, session.apply(Unpin(0)))
        assertEquals(unpinned, session.apply(Pin(99)))
        assertTrue(saved.isEmpty())
    }

    @Test
    fun aPhraseIsNotPinnedHere() {
        val saved = ArrayList<String>()
        val session = phrased("zai,1=再\nzai,2=#${'$'}{day}日", saved = saved, learner = Learner(data))
        val s = session.type("nizai")
        val rest = session.apply(Select(s.candidates.indexOf("拟")))
        assertEquals(listOf("再", "30日"), rest.candidates.take(2))
        assertFalse(Offer.PIN in session.offers(1))
        assertEquals(rest.copy(commit = ""), session.apply(Pin(1)))
        assertTrue(saved.isEmpty())
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
        val session = phrased("", saved = saved)
        val shown = session.type("wo").candidates
        val predicted = session.apply(Select(shown.indexOf("我")))
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
    fun aPhraseIsNotLearnedThoughTheDecoderHasItsTextButTheWordAfterIs() {
        val learner = Learner(data)
        val session = phrased("nizai,1=你在哪儿\nni,1=拟", learner = learner)
        assertEquals("你在哪儿", session.type("nizai").candidates.first())
        session.apply(Select(0))
        // 拟 put first by a phrase is the phrase's pick, no word picked (PLAN §2.7): the record has neither
        assertEquals("拟", session.type("ni").candidates.first())
        session.apply(Select(0))
        assertEquals(emptySet<String>(), learner.used())
        session.type("zai")
        val after = session.apply(Select(0)).commit
        // the word after learned, the phrase's 拟 no word of it
        assertTrue(learner.used().toString(), after in learner.used())
        assertFalse("拟" in learner.used())
    }

    // U-66, impl/PLAN.md §2.7: the phrase a candidate of its own, not learned, where its order says; the
    // decoder's of the same text not moved there: ranked after it, the phrase stands for it; ranked
    // before (learned elsewhere), it stays a word there, picked and learned, the phrase not listed again
    @Test
    fun aPhraseIsItsOwnCandidateAndNoTextIsListedTwice() {
        val learner = Learner(data)
        val session = phrased("ni,2=拟", learner = learner)
        val shown = session.type("ni").candidates
        assertEquals(listOf("你", "拟"), shown.take(2))
        assertEquals(1, shown.count { it == "拟" })
        assertFalse(Offer.PIN in session.offers(1))
        session.apply(Action.Reset)
        // 拟 picked twice where no phrase is: the user's first under ni
        val plain = PinyinSession(data, PinyinSegmenter(), pageSize = 20, user = learner.model, learnTo = learner.learnTo())
        repeat(2) {
            plain.type("ni")
            plain.apply(Select(plain.candidates(0, 20).indexOfFirst { it.text == "拟" }))
            plain.apply(Action.Reset)
        }
        val learned = session.type("ni").candidates
        assertEquals("拟", learned[0])
        assertEquals(1, learned.count { it == "拟" })
        // the word, not the phrase: pinned like any word
        assertTrue(Offer.PIN in session.offers(0))
    }

    @Test
    fun theBuiltInPhrasesAreFullPinyinsAndNotShuangpins() {
        val book = { PhraseBook(builtIn = CustomPhrases.BUILT_IN) }
        val now = { GregorianCalendar(2026, Calendar.OCTOBER, 17, 9, 5) }
        assertTrue("09:05" in PinyinSession(data, PinyinSegmenter(), pageSize = 20, phraseBook = book(), now = now).type("sj").candidates)
        // sj is san in 自然码
        val shuangpin = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), spell = true, pageSize = 20, phraseBook = book(), now = now)
        assertFalse("09:05" in shuangpin.type("sj").candidates)
        // the user's own are offered there still
        val own = PhraseBook(CustomPhrases.parse("sj,1=三家"), CustomPhrases.BUILT_IN)
        val mine = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), spell = true, pageSize = 20, phraseBook = own, now = now)
        assertEquals("三家", mine.type("sj").candidates[0])
    }

    @Test
    fun theBuiltInPhrasesTurnedOffAreNotOfferedButTheUsersAre() {
        val now = { GregorianCalendar(2026, Calendar.OCTOBER, 17, 9, 5) }
        val book = PhraseBook(CustomPhrases.parse("rq,1=日前"), CustomPhrases.BUILT_IN)
        fun session(on: Boolean) = PinyinSession(data, PinyinSegmenter(), pageSize = 20, phraseBook = book, builtInPhrases = on, now = now)
        assertTrue("09:05" in session(true).type("sj").candidates)
        assertFalse("09:05" in session(false).type("sj").candidates)
        assertTrue("星期六" in session(true).type("xq").candidates)
        assertFalse("星期六" in session(false).type("xq").candidates)
        // the user's own stays
        assertEquals("日前", session(false).type("rq").candidates[0])
    }

    @Test
    fun theWordsOfTheBuiltInPhrasesBringThemInEveryPinyinOnceAndAsTheSwitchHasIt() {
        val now = { GregorianCalendar(2026, Calendar.OCTOBER, 17, 9, 5) }
        fun session(spell: Boolean, on: Boolean = true, book: PhraseBook = PhraseBook(builtIn = CustomPhrases.BUILT_IN)) = PinyinSession(
            data, if (spell) ShuangpinSegmenter(ShuangpinScheme.ZIRANMA) else PinyinSegmenter(), spell = spell, pageSize = 20,
            phraseBook = book, builtInPhrases = on, now = now,
        )
        val dates = listOf("2026年10月17日", "2026-10-17", "二〇二六年十月十七日")
        // 日期 read first, the whole input: its dates after the first two, as its emoji would be
        val sp = session(spell = true).type("riqi").candidates
        assertEquals("日期", sp[0])
        assertEquals(dates, sp.filter { it in dates })
        assertEquals(minOf(2, sp.size - dates.size), sp.indexOf(dates[0]))
        val full = session(spell = false).type("riqi").candidates
        assertEquals("日期", full[0])
        assertEquals(dates, full.filter { it in dates })
        assertEquals(minOf(2, full.size - dates.size), full.indexOf(dates[0]))
        // rq in full pinyin: its own, not twice
        assertEquals(1, session(spell = false).type("rq").candidates.count { it == "2026-10-17" })
        // the switch off, or the one deleted: none
        assertFalse("2026-10-17" in session(spell = true, on = false).type("riqi").candidates)
        val book = PhraseBook(builtIn = CustomPhrases.BUILT_IN)
        book.remove("rq", "#\$year-\$month_mm-\$day_dd")
        val left = session(spell = true, book = book).type("riqi").candidates
        assertFalse("2026-10-17" in left)
        assertTrue("2026年10月17日" in left)
    }

    // UAT PY-49: v0.3.0's 从自定义词组中删除 on a built-in date, lost with the learning menu
    @Test
    fun aBuiltInDateIsDeletedFromTheCustomPhrasesByItsLongPress() {
        val saved = ArrayList<String>()
        val book = PhraseBook(builtIn = CustomPhrases.BUILT_IN) { saved += it.all.joinToString(" ") }
        val session = PinyinSession(
            data, PinyinSegmenter(), pageSize = 20, phraseBook = book,
            now = { GregorianCalendar(2026, Calendar.OCTOBER, 17, 9, 5) },
        )
        val s = session.type("rq")
        val date = s.candidates.indexOf("2026-10-17")
        assertEquals(setOf(Offer.DELETE_PHRASE), session.offers(date))
        assertFalse(Offer.DELETE_PHRASE in session.offers(0))
        val left = session.apply(Unpin(date)).candidates
        assertFalse("2026-10-17" in left)
        assertTrue("2026年10月17日" in left)
        assertEquals(1, saved.size)
    }

    // a phrase of the user's own deleted by its long press too, saved without it (no undo: the settings' list)
    @Test
    fun aPhraseOfTheUsersIsDeletedByItsLongPress() {
        val saved = ArrayList<String>()
        val session = phrased("nizai,2=你在哪儿", saved = saved)
        val at = session.type("nizai").candidates.indexOf("你在哪儿")
        assertTrue(at >= 0)
        assertTrue(Offer.DELETE_PHRASE in session.offers(at))
        assertFalse("你在哪儿" in session.apply(Unpin(at)).candidates)
        assertEquals(1, saved.size)
        assertFalse(saved.single(), "你在哪儿" in saved.single())
    }
}
