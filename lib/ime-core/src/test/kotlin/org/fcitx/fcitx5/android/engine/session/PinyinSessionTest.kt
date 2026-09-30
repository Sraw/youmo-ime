/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Fuzzy
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.CommitRaw
import org.fcitx.fcitx5.android.engine.session.Action.Forget
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.NextPage
import org.fcitx.fcitx5.android.engine.session.Action.Pick
import org.fcitx.fcitx5.android.engine.phrase.CustomPhrases
import org.fcitx.fcitx5.android.engine.session.Action.PreviousPage
import org.fcitx.fcitx5.android.engine.session.Action.Reset
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserModelTest.Companion.inTrie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.nio.ByteBuffer

class PinyinSessionTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("好", -2.5f, 0f)
                .unigram("你好", -3f, 0f)
                .unigram("吗", -3.5f, 0f)
                .unigram("拟", -5f, 0f)
                .unigram("在", -2f, 0f)
                .unigram("再", -2.5f, 0f)
                .unigram("见", -3f, 0f)
                .unigram("我", -2f, -1f)
                .unigram("西安", -4f, 0f)
                .unigram("先", -3f, 0f)
                .unigram("中", -3.5f, 0f)
                .bigram("你", "好", -0.75f, 0f)
                .bigram("你好", "吗", -0.5f, 0f)
                .bigram("再", "见", -0.5f, 0f)
                .bigram("我", "再", -0.5f, 0f)
                .bigram("拟", "再", -0.1f, 0f)
                .entry("你", syl("ni"))
                .entry("拟", syl("ni"))
                .entry("好", syl("hao"))
                .entry("你好", syl("ni", "hao"))
                .entry("吗", syl("ma"))
                .entry("在", syl("zai"))
                .entry("再", syl("zai"))
                .entry("见", syl("jian"))
                .entry("我", syl("wo"))
                .entry("西安", syl("xi", "an"))
                .entry("先", syl("xian"))
                .entry("中", syl("zhong"))
                .build().toByteArray(),
        ),
    )

    private fun session(pageSize: Int = PinyinSession.DEFAULT_PAGE_SIZE) = PinyinSession(data, PinyinSegmenter(), pageSize = pageSize)

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    @Test
    fun theWholeInputIsReadFirst() {
        val s = session().type("nihaoma")
        assertEquals("你好吗", s.candidates.first())
        assertEquals("ni hao ma", s.preedit)
        assertEquals("", s.commit)
        assertTrue(s.handled)
        assertFalse(s.predicting)
    }

    @Test
    fun withPredictionOffNothingIsOfferedAfterACommit() {
        val session = PinyinSession(data, PinyinSegmenter(), prediction = false)
        session.type("wo")
        val committed = session.apply(Select(0))
        assertEquals("我", committed.commit)
        assertFalse(committed.predicting)
        assertTrue(committed.candidates.isEmpty())
    }

    @Test
    fun pickingEverythingCommitsItAndPredictsWhatFollows() {
        val session = session()
        session.type("wo")
        val committed = session.apply(Select(0))
        assertEquals("我", committed.commit)
        assertEquals("", committed.preedit)
        // what the model saw after 我
        assertTrue(committed.predicting)
        assertEquals(listOf("再"), committed.candidates)
        val next = session.apply(Select(0))
        assertEquals("再", next.commit)
        assertEquals(listOf("见"), next.candidates)
    }

    @Test
    fun whatWasCommittedIsTheContextOfWhatIsTypedNext() {
        val session = session()
        assertEquals("在", session.type("zai").candidates.first())
        session.apply(Reset)
        session.type("wo")
        session.apply(Select(0))
        // a key ends the prediction; after 我, 再 is the likelier
        val typed = session.type("zai")
        assertFalse(typed.predicting)
        assertEquals("再", typed.candidates.first())
        // until reset
        session.apply(Reset)
        assertEquals("在", session.type("zai").candidates.first())
    }

    @Test
    fun theRerankerPutsItsPickFirstAfterWhatWasCommitted() {
        val contexts = ArrayList<String>()
        // the decoder's last reading, whatever it is
        val session = PinyinSession(data, PinyinSegmenter(), pageSize = 20) { context, readings, scores ->
            assertEquals(readings.size, scores.size)
            contexts += context
            readings.lastIndex
        }
        val plain = session(pageSize = 20).type("nizai").candidates
        val readings = plain.takeWhile { it.length == 2 }
        assertTrue(readings.size > 1)
        val s = session.type("nizai")
        assertEquals(listOf(readings.last()) + readings.dropLast(1), s.candidates.take(readings.size))
        assertEquals(plain.drop(readings.size), s.candidates.drop(readings.size))
        assertEquals(List(5) { "" }, contexts)
        // the pick is what a space commits, and what follows reads after it and the pieces picked
        val committed = session.apply(Select(0)).commit
        assertEquals(readings.last(), committed)
        contexts.clear()
        val typed = session.type("nizai")
        assertEquals(List(5) { committed }, contexts)
        session.apply(Select(typed.candidates.indexOf("拟")))
        assertEquals(committed + "拟", contexts.last())
        // gone with a reset
        session.apply(Reset)
        contexts.clear()
        session.type("ni")
        assertEquals(listOf("", ""), contexts)
    }

    @Test
    fun whatTheRerankerPutFirstIsLearnedAsPickedOverTheDecoder() {
        val user = UserModel(data.dictionary, data.vocabulary)
        val session = PinyinSession(data, PinyinSegmenter(), pageSize = 20, user = user) { _, readings, _ -> readings.lastIndex }
        // the decoder's best, second now: nothing new
        assertEquals("你在", session.type("nizai").candidates[1])
        session.apply(Select(1))
        assertEquals(0, user.size)
        // the reranker's: learned as one word, for the decoder to have it first without it
        val picked = session.type("nizai").candidates[0]
        assertEquals(picked, session.apply(Select(0)).commit)
        assertEquals(1, user.size)
        assertEquals(picked, PinyinSession(data, PinyinSegmenter(), user = user).type("nizai").candidates.first())
    }

    @Test
    fun whileTheUserPausesTheRefinerPutsItsPickFirst() {
        val budgets = ArrayList<Int>()
        val refiner = SentenceRefiner { _, readings, _, budget ->
            budgets += budget
            if (budgets.size < 3) null else readings.lastIndex
        }
        val session = PinyinSession(data, PinyinSegmenter(), pageSize = 20, refiner = refiner) { _, _, _ -> 0 }
        val typed = session.type("nizai")
        assertTrue(typed.refines)
        // slices that pick nothing leave it as it was
        repeat(2) { assertEquals(typed, session.apply(Action.Refine)) }
        val refined = session.apply(Action.Refine)
        assertFalse(refined.refines)
        assertNotEquals(typed.candidates[0], refined.candidates[0])
        assertEquals(typed.candidates.toSet(), refined.candidates.toSet())
        assertEquals(List(3) { PinyinSession.REFINE_BUDGET }, budgets)
        // done: nothing more asked of it
        assertEquals(refined, session.apply(Action.Refine))
        assertEquals(3, budgets.size)
        // any other action drops what was left: the refiner weighs the first page as last read
        assertTrue(session.apply(Key('a')).refines)
        assertFalse(session.apply(NextPage).refines)
        session.apply(Action.Refine)
        assertEquals(3, budgets.size)
    }

    @Test
    fun aRefinerThatNeverPicksIsGivenUpOnAfterItsSlices() {
        var asked = 0
        val session = PinyinSession(data, PinyinSegmenter(), refiner = { _, _, _, _ -> asked++; null }) { _, _, _ -> 0 }
        var shown = session.type("nizai")
        var slices = 0
        while (shown.refines) {
            shown = session.apply(Action.Refine)
            slices++
            assertTrue(slices <= PinyinSession.REFINE_SLICES + 1)
        }
        assertEquals(PinyinSession.REFINE_SLICES, asked)
        // the next input gets its own
        assertTrue(session.apply(Key('a')).refines)
    }

    @Test
    fun aRefinerWithNothingToSayLeavesTheRerankersPick() {
        val refiner = SentenceRefiner { _, _, _, _ -> SentenceRefiner.NONE }
        val session = PinyinSession(data, PinyinSegmenter(), refiner = refiner) { _, readings, _ -> readings.lastIndex }
        val typed = session.type("nizai")
        val refined = session.apply(Action.Refine)
        assertFalse(refined.refines)
        assertEquals(typed.candidates, refined.candidates)
    }

    @Test
    fun theTextTheAppHasIsTheContextOfWhatIsTypedNext() {
        val session = session()
        val context = session.apply(Action.Context("他说我"))
        // nothing is offered for it, as a prediction would be after a commit
        assertEquals(emptyList<String>(), context.candidates)
        assertEquals("再", session.type("zai").candidates.first())
        // text ending with no word of the model's is nothing to go on from
        session.apply(Action.Context("我 "))
        assertEquals("在", session.type("zai").candidates.first())
        session.apply(Action.Context("我"))
        session.apply(Reset)
        assertEquals("在", session.type("zai").candidates.first())
    }

    @Test
    fun theAppsTextEndingWithWhatWasCommittedKeepsTheContextCommitted() {
        val contexts = ArrayList<String>()
        val session = PinyinSession(data, PinyinSegmenter()) { context, _, _ -> contexts += context; 0 }
        session.type("wo")
        assertEquals("我", session.apply(Select(0)).commit)
        // the editor reports the cursor where the service did not expect it, but it did not move
        session.apply(Action.Context("他说我"))
        session.type("zai")
        assertEquals("我", contexts.last())
        // it did move
        session.apply(Reset)
        session.apply(Action.Context("他说你"))
        session.type("zai")
        assertEquals("他说你", contexts.last())
        // more committed than the host reads of the text: its text ends what was
        session.apply(Reset)
        session.apply(Action.Context("我".repeat(70)))
        session.type("zai")
        assertEquals("再", session.apply(Select(0)).commit)
        session.apply(Action.Context(("我".repeat(70) + "再").takeLast(64)))
        session.type("zai")
        assertEquals("我".repeat(70) + "再", contexts.last())
        // but not a word of it that happens to be
        session.apply(Action.Context("他说再"))
        session.type("zai")
        assertEquals("他说再", contexts.last())
    }

    @Test
    fun whileLearningIsOffTheAppsTextIsNoContext() {
        val contexts = ArrayList<String>()
        val session = PinyinSession(data, PinyinSegmenter()) { context, _, _ -> contexts += context; 0 }
        session.learning = false
        session.apply(Action.Context("我"))
        assertEquals("在", session.type("zai").candidates.first())
        assertEquals(listOf(""), contexts.distinct())
        session.learning = true
        session.apply(Action.Context("我"))
        assertEquals("再", session.type("zai").candidates.first())
        assertEquals("我", contexts.last())
    }

    @Test
    fun theRerankerIsToldMoreThanItReadsSoACommitDropsNothingItDoes() {
        val contexts = ArrayList<String>()
        val session = PinyinSession(data, PinyinSegmenter()) { context, _, _ -> contexts += context; 0 }
        val committed = StringBuilder()
        repeat(40) {
            session.type("nizai")
            committed.append(session.apply(Select(0)).commit)
        }
        session.type("nizai")
        assertTrue(committed.length > 64)
        assertEquals(committed.toString(), contexts.last())
        // cut where a pair of chars is not split
        val pairs = "\uD840\uDC00".repeat(100)
        assertEquals(pairs.substring(74) + "b", PinyinSession.tail("a" + pairs + "b"))
    }

    @Test
    fun whileLearningIsOffTheRerankerIsToldNothingCommitted() {
        val contexts = ArrayList<String>()
        val session = PinyinSession(data, PinyinSegmenter()) { context, _, _ -> contexts += context; 0 }
        session.learning = false
        session.type("nizai")
        session.apply(Select(0))
        session.type("nizai")
        assertEquals(listOf(""), contexts.distinct())
    }

    @Test
    fun aPiecePickedIsTheContextOfTheRest() {
        val session = session(pageSize = 20)
        val s = session.type("nizai")
        assertEquals("你在", s.candidates.first())
        val picked = session.apply(Select(s.candidates.indexOf("拟")))
        assertEquals("", picked.commit)
        assertEquals("拟zai", picked.preedit)
        // after 拟, 再 is the likelier
        assertEquals("再", picked.candidates.first())
        assertEquals("拟再", session.apply(Select(0)).commit)
    }

    @Test
    fun backspaceDeletesAKeyAndThenThePieceItEmptied() {
        val session = session()
        val s = session.type("nihao")
        assertEquals("你hao", session.apply(Select(s.candidates.indexOf("你"))).preedit)
        assertEquals("你ha", session.apply(Backspace).preedit)
        assertEquals("你h", session.apply(Backspace).preedit)
        // the last key after 你 goes, and 你 with it: ni is read again
        val back = session.apply(Backspace)
        assertEquals("ni", back.preedit)
        assertEquals("你", back.candidates.first())
        assertEquals("n", session.apply(Backspace).preedit)
        val empty = session.apply(Backspace)
        assertEquals("", empty.preedit)
        assertEquals(emptyList<String>(), empty.candidates)
        assertTrue(empty.handled)
        // nothing typed: the app's backspace
        assertFalse(session.apply(Backspace).handled)
    }

    @Test
    fun pagesTurnWithinTheCandidates() {
        val session = session(pageSize = 1)
        val first = session.type("ni")
        assertEquals(0, first.page)
        assertFalse(first.hasPreviousPage)
        assertTrue(first.hasNextPage)
        val second = session.apply(NextPage)
        assertEquals(1, second.page)
        assertTrue(second.hasPreviousPage)
        assertEquals(first.candidates.size, 1)
        // the last page does not turn further
        var s = second
        while (s.hasNextPage) s = session.apply(NextPage)
        assertEquals(s, session.apply(NextPage))
        // a pick is on the page shown
        val picked = session.apply(Select(0))
        assertEquals(s.candidates.single(), picked.commit)
        session.type("ni")
        assertEquals(0, session.apply(PreviousPage).page)
        // an index past the page picks nothing, but the key was for the candidates
        val past = session.apply(Select(1))
        assertEquals("", past.commit)
        assertTrue(past.handled)
        // with no candidates, space or a digit is the app's
        session.apply(Reset)
        assertFalse(session.apply(Select(0)).handled)
        assertThrows(IllegalArgumentException::class.java) { session(pageSize = 0) }
    }

    @Test
    fun aHostListsAndPicksAmongAllCandidatesWhateverThePage() {
        val session = session(pageSize = 1)
        val s = session.type("ni")
        val all = session.candidates(0, 100).map { it.text }
        assertEquals(all.size, s.total)
        assertTrue(all.size > 1)
        assertEquals(all.drop(1).take(1), session.candidates(1, 1).map { it.text })
        assertTrue(session.candidates(all.size, 5).isEmpty())
        // the second, though page one shows only the first
        assertEquals(all[1], session.apply(Pick(1)).commit)
        // nothing there: the pick is ignored while something is typed
        session.type("ni")
        assertTrue(session.apply(Pick(all.size)).handled)
        session.apply(Reset)
        assertFalse(session.apply(Pick(0)).handled)
    }

    @Test
    fun theKeysReadAreThoseOfTheSegmenterAndASeparatorWithinInput() {
        val session = session()
        assertTrue(session.reads('a'))
        assertFalse(session.reads('1'))
        assertFalse(session.reads(','))
        assertFalse(session.reads('\''))
        session.type("xi")
        assertTrue(session.reads('\''))
        // 双拼 reads what its scheme gives a part, ; in 微软
        val ms = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.MICROSOFT), spell = true)
        assertTrue(ms.reads(';'))
        assertFalse(session.reads(';'))
    }

    @Test
    fun enterCommitsWhatWasTyped() {
        val session = session()
        val s = session.type("nihao")
        session.apply(Select(s.candidates.indexOf("你")))
        val raw = session.apply(CommitRaw)
        assertEquals("你hao", raw.commit)
        assertEquals("", raw.preedit)
        // text as typed ends the context: nothing to predict
        assertFalse(raw.predicting)
        assertFalse(session.apply(CommitRaw).handled)
    }

    @Test
    fun separatorsShowWhereTheyWereTyped() {
        val session = session()
        assertEquals("xi'an", session.type("xi'an").preedit)
        session.apply(Reset)
        val xian = session.type("xian")
        assertEquals("先", xian.candidates.first())
        assertEquals("xian", xian.preedit)
        session.apply(Reset)
        // a separator first is the app's, and ends predictions shown
        assertFalse(session.apply(Key('\'')).handled)
        session.type("wo")
        assertTrue(session.apply(Select(0)).predicting)
        val separator = session.apply(Key('\''))
        assertFalse(separator.handled)
        assertFalse(separator.predicting)
        assertEquals(emptyList<String>(), separator.candidates)
        // input nothing reads: kept as typed
        assertEquals("vvv", session.type("vvv").preedit)
    }

    @Test
    fun 双拼IsShownSpeltOut() {
        val session = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.XIAOHE), spell = true)
        val s = session.type("nihc")
        assertEquals("你好", s.candidates.first())
        assertEquals("ni hao", s.preedit)
        // a key still being typed shows as typed
        session.apply(Reset)
        assertEquals("ni h", session.type("nih").preedit)
    }

    @Test
    fun aFuzzyReadingShowsWhatWasTyped() {
        val session = PinyinSession(data, PinyinSegmenter(setOf(Fuzzy.Z_ZH)))
        val s = session.type("zong")
        assertEquals("中", s.candidates.first())
        assertEquals("zong", s.preedit)
    }

    @Test
    fun aKeyNotReadCommitsTheFirstCandidateAndEndsTheContext() {
        val session = session()
        session.type("nihao")
        val s = session.apply(Key(','))
        assertEquals("你好", s.commit)
        assertFalse(s.handled)
        assertFalse(s.predicting)
        assertTrue(s.candidates.isEmpty())
        // 我 would have 再 follow it; after the space it is no context
        session.type("wo")
        assertTrue(session.apply(Select(0)).predicting)
        assertFalse(session.apply(Key(' ')).handled)
        assertEquals("在", session.type("zai").candidates.first())
    }

    @Test
    fun aKeyNotReadKeepsWhatTheFirstCandidateLeavesAsTyped() {
        val session = session(pageSize = 20)
        val s = session.type("nizai")
        session.apply(Select(s.candidates.indexOf("拟")))
        // 再 reads the rest
        assertEquals("拟再", session.apply(Key('.')).commit)
        // nothing to commit: the key is the app's all the same
        val separator = session.apply(Key('\''))
        assertEquals("", separator.commit)
        assertFalse(separator.handled)
    }

    @Test
    fun nothingIsLearnedWhileLearningIsOff() {
        val (session, user) = learning()
        session.learning = false
        val s = session.type("nizai")
        session.apply(Select(s.candidates.indexOf("拟")))
        val done = session.apply(Select(0))
        assertEquals("拟再", done.commit)
        assertFalse(done.predicting)
        assertEquals(0f, user.total)
        assertEquals(0, user.size)
    }

    private fun learning(pageSize: Int = 20): Pair<Session, UserModel> {
        val user = UserModel(data.dictionary, data.vocabulary)
        return PinyinSession(data, PinyinSegmenter(), pageSize = pageSize, user = user) to user
    }

    @Test
    fun aWordPickedOftenComesFirst() {
        val (session, user) = learning()
        repeat(3) {
            val s = session.type("ni")
            // 拟 is far less likely than 你 until picked three times
            assertEquals("你", s.candidates.first())
            assertEquals("拟", session.apply(Select(s.candidates.indexOf("拟"))).commit)
            session.apply(Reset)
        }
        assertEquals("拟", session.type("ni").candidates.first())
        assertEquals(3f, user.total)
    }

    @Test
    fun piecesPutTogetherAreLearnedAsOneWord() {
        val (session, user) = learning()
        val s = session.type("nizai")
        session.apply(Select(s.candidates.indexOf("拟")))
        val committed = session.apply(Select(0))
        assertEquals("拟再", committed.commit)
        assertEquals(1, user.size)
        // the model never saw the user's word: what follows is what follows its end, 再
        assertTrue(committed.predicting)
        assertEquals(listOf("见"), committed.candidates)
        session.apply(Reset)
        // found whole, and read as its syllables
        val again = session.type("nizai")
        assertEquals("拟再", again.candidates.first())
        assertEquals("ni zai", again.preedit)
        // and picked whole: learned again, not anew
        assertEquals("拟再", session.apply(Select(0)).commit)
        assertEquals(1, user.size)
        assertEquals(2f, user.total)
    }

    @Test
    fun aLongRunOfPiecesIsLearnedAsItsWords() {
        val (session, user) = learning()
        var s = session.type("ni".repeat(PinyinSession.MAX_PHRASE + 1))
        while (s.commit.isEmpty()) s = session.apply(Select(s.candidates.indexOf("拟")))
        assertEquals("拟".repeat(PinyinSession.MAX_PHRASE + 1), s.commit)
        assertEquals(0, user.size)
        assertEquals(PinyinSession.MAX_PHRASE + 1f, user.total)
    }

    @Test
    fun predictionsAndTextAsTypedAreNotLearned() {
        val (session, user) = learning()
        session.type("wo")
        session.apply(Select(0))
        // 再, predicted after 我: nothing says how it was read
        assertEquals("再", session.apply(Select(0)).commit)
        assertEquals(1f, user.total)
        session.type("vvv")
        session.apply(CommitRaw)
        // a sentence with text kept as typed
        val s = session.type("nivvv")
        assertEquals("你vvv", session.apply(Select(s.candidates.indexOf("你vvv"))).commit)
        assertEquals(1f, user.total)
    }

    @Test
    fun aCandidateForgottenIsReadAsBeforeItWasLearned() {
        val (session, user) = learning(pageSize = 1)
        session.type("nizai")
        val fresh = session.candidates(0, 20)
        session.apply(Reset)
        // 拟 then 再: learned as the user's word 拟再
        session.type("nizai")
        session.apply(Pick(session.candidates(0, 20).indexOfFirst { it.text == "拟" }))
        session.apply(Select(0))
        session.apply(Reset)
        val before = session.type("nizai")
        assertEquals(listOf("拟再"), before.candidates)
        assertTrue(before.forgets)
        assertEquals(1, user.size)
        // forgotten from the second page, which stays shown
        session.apply(NextPage)
        val after = session.apply(Forget(session.candidates(0, 20).indexOfFirst { it.text == "拟再" }))
        // read as before: 拟再 as the sentence 拟 再, not the user's word
        assertEquals(fresh, session.candidates(0, 20))
        assertEquals(1, after.page)
        assertEquals("ni zai", after.preedit)
        assertEquals(0f, user.total)
        session.apply(Reset)
        val again = session.type("nizai")
        assertEquals(fresh.take(1).map { it.text }, again.candidates)
        // past the candidates: nothing to forget
        assertEquals(again, session.apply(Forget(99)))
    }

    @Test
    fun aWordCommittedThenForgottenIsNotLearnedAgainAsTheWordBefore() {
        val (session, user) = learning()
        session.type("nizai")
        session.apply(Pick(session.candidates(0, 20).indexOfFirst { it.text == "拟" }))
        session.apply(Select(0))
        // deleted in the app, typed again and forgotten: the context is still 拟再's
        session.apply(Backspace)
        session.type("nizai")
        session.apply(Forget(session.candidates(0, 20).indexOfFirst { it.text == "拟再" }))
        session.apply(Pick(session.candidates(0, 20).indexOfFirst { it.text == "你在" }))
        assertFalse(inTrie(user, "拟再", "ni", "zai"))
        val counted = ArrayList<String>()
        user.forEachCount({ e, _ -> counted += e.toString() }, { a, b, _ -> counted += "$a $b" })
        // 你 在 and their pair, none with 拟再
        assertEquals(listOf("你(ni)", "你(ni) 在(zai)", "在(zai)"), counted.sorted())
    }

    @Test
    fun onlyWhatAUserModelLearnedIsForgotten() {
        assertFalse(session().type("ni").forgets)
        assertEquals(session().type("ni"), session().also { it.type("ni") }.apply(Forget(0)))
        val (session, user) = learning()
        session.type("wo")
        val predicted = session.apply(Select(0))
        assertTrue(predicted.predicting)
        assertFalse(predicted.forgets)
        // a prediction offered: nothing forgotten
        assertEquals(predicted.copy(commit = ""), session.apply(Forget(0)))
        assertEquals(1f, user.total)
    }

    private fun word(text: String) = (0 until data.vocabulary.size).first { data.vocabulary.word(it) == text }

    @Test
    fun theWordCommittedBeforeIsLearnedWithTheNext() {
        val (session, user) = learning()
        fun commit(keys: String, text: String) {
            val s = session.type(keys)
            assertEquals(text, session.apply(Select(s.candidates.indexOf(text))).commit)
        }
        val alone = { user.probability(word("我"), word("在")) }
        commit("wo", "我")
        commit("zai", "在")
        val paired = alone()
        assertTrue(paired > user.probability(word("你"), word("在")))
        // after a reset, text as typed, or text not learned, 在 follows nothing learned
        for (breaks in listOf<() -> Unit>(
            { session.apply(Reset) },
            { session.type("vvv"); session.apply(CommitRaw) },
            { commit("vvvni", "vvv你") },
        )) {
            commit("wo", "我")
            breaks()
            commit("zai", "在")
        }
        // 我 and 在 counted four times each, the pair still once: its share fell
        assertTrue(alone() < paired)
        assertEquals((1f + 2f * user.probability(NO_WORD, word("在"))) / (4f + 2f), alone(), 1e-6f)
    }

    @Test
    fun aReadingPickedOverABetterOneIsLearnedAsOneWord() {
        val (session, user) = learning()
        val s = session.type("nihaoma")
        assertEquals("你好吗", s.candidates.first())
        // the first reading, picked whole: its words are learned, and no new one
        session.apply(Select(0))
        assertEquals(0, user.size)
        val other = session.type("nizai")
        val index = other.candidates.indexOf("拟再")
        assertTrue(index > 0)
        assertEquals("拟再", session.apply(Select(index)).commit)
        assertEquals(1, user.size)
        session.apply(Reset)
        assertEquals("拟再", session.type("nizai").candidates.first())
    }

    private fun phrased(text: String, user: UserModel? = null) = PinyinSession(
        data, PinyinSegmenter(), pageSize = 20, user = user,
        phrases = CustomPhrases.parse(text), now = { GregorianCalendar(2026, Calendar.SEPTEMBER, 30) },
    )

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
