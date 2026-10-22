/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.HabitRanker
import org.fcitx.fcitx5.android.engine.learn.KeyedRows
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingSessionTest {

    private fun session(pageSize: Int = HandwritingSession.DEFAULT_PAGE_SIZE) =
        HandwritingSession(handwritingDecoder(), TextWords(handwritingData.model, handwritingData.wordIndex), pageSize)

    // each shows a piece of its own, unless told
    private var pieces = 0

    private fun HandwritingSession.show(reading: InkReading, piece: Int = pieces++) = run {
        offer(reading, piece)
        apply(Action.Ink)
    }

    @Test
    fun `what the pad read shows, the best reading as the preedit`() {
        val s = session(pageSize = 2)
        val shown = s.show(read("天", "人", "山"), piece = 0)
        assertEquals("天", shown.preedit)
        assertEquals(listOf("天", "人"), shown.candidates)
        assertEquals(3, shown.total)
        assertTrue(shown.hasNextPage)
        assertEquals("", shown.commit)
        assertEquals(listOf(Choice("山")), s.candidates(2, 5))
        // an Ink with nothing offered since changes nothing
        assertEquals(shown, s.apply(Action.Ink))
        // the same piece read again, with more strokes: its reading replaces what was shown
        assertEquals("人", s.show(read("人"), piece = 0).preedit)
    }

    @Test
    fun `a later piece adds a character, read with those before, and can put them right`() {
        val s = session()
        assertEquals("淡", s.show(read("淡", "谈", step = 0.3), piece = 0).preedit)
        val two = s.show(read("话", "天"), piece = 1)
        assertEquals("", two.commit)
        assertEquals("谈话", two.preedit)
        // the candidates are what the last character may be
        assertEquals(listOf("话", "天"), two.candidates)
        // nothing put in, nothing ended: the pad wrote on
        assertEquals(HandwritingSession.NO_PIECE, s.ended)
    }

    @Test
    fun `the readings offered before an Ink are shown in turn`() {
        val s = session()
        s.offer(read("淡", "谈", step = 0.3), 0)
        s.offer(read("天"), 1)
        s.offer(read("话"), 1)
        assertEquals("谈话", s.apply(Action.Ink).preedit)
    }

    @Test
    fun `a later piece read as nothing leaves what is shown, a piece written read as nothing is taken out`() {
        val s = session()
        s.show(read("天"), piece = 0)
        // a first dot, too little to read
        assertEquals("天", s.show(InkReading.NONE, piece = 1).preedit)
        assertEquals("天人", s.show(read("人"), piece = 1).preedit)
        // its stroke taken back: the character goes, the pad writes on in it
        val back = s.show(InkReading.NONE, piece = 1)
        assertEquals("天", back.preedit)
        assertTrue(back.handled)
        assertEquals(HandwritingSession.NO_PIECE, s.ended)
        assertEquals("天山", s.show(read("山"), piece = 1).preedit)
        // both taken out: nothing shows
        s.show(InkReading.NONE, piece = 0)
        val none = s.show(InkReading.NONE, piece = 1)
        assertEquals("", none.preedit)
        assertTrue(none.candidates.isEmpty())
    }

    @Test
    fun `a reading of a piece ended, or of one taken back, comes too late`() {
        val s = session()
        s.show(read("天"), piece = 0)
        assertEquals("天", s.apply(Action.Pick(0)).commit)
        assertEquals(0, s.ended)
        assertEquals("", s.show(read("人"), piece = 0).preedit)
        s.show(read("人"), piece = 1)
        s.show(read("山"), piece = 2)
        // backspace takes the last back, ending its piece
        assertEquals("人", s.apply(Action.Backspace).preedit)
        assertEquals(2, s.ended)
        assertEquals("人", s.show(read("天"), piece = 2).preedit)
    }

    @Test
    fun `the cursor moved ends every piece read so far, those waiting too`() {
        val s = session()
        s.show(read("天"), piece = 2)
        s.offer(read("人"), 3)
        val reset = s.apply(Action.Reset)
        assertEquals("", reset.preedit)
        assertEquals(3, s.ended)
        assertEquals("", s.apply(Action.Ink).preedit)
        s.show(read("人"), piece = 4)
        s.apply(Action.Context("高"))
        assertEquals(4, s.ended)
    }

    @Test
    fun `a pick puts in the sentence ending with it, a select picks on the page`() {
        val s = session(pageSize = 2)
        s.show(read("淡", "谈", step = 0.3))
        s.show(read("话", "天", "人"))
        assertEquals("淡人", s.apply(Action.Pick(2)).commit)
        s.show(read("天", "人", "山"))
        s.apply(Action.NextPage)
        assertEquals("山", s.apply(Action.Select(0)).commit)
        s.show(read("天", "人"))
        // past the page: nothing picked, but taken while something shows
        assertTrue(s.apply(Action.Select(3)).handled)
        assertEquals("天", s.apply(Action.Pick(0)).commit)
        // with nothing written, the app's
        assertFalse(s.apply(Action.Backspace).handled)
        assertFalse(s.apply(Action.Select(3)).handled)
        assertFalse(s.apply(Action.CommitRaw).handled)
    }

    @Test
    fun `every sentence put in is learned by its words' text, after the word before it`() {
        val learner = Learner(handwritingData)
        val user = learner.model
        val s = HandwritingSession(handwritingDecoder(user), TextWords(handwritingData.model, handwritingData.wordIndex), user = user, habits = learner.habits)
        // the first too, however it is put in (U-29: 手写直接上屏的 counts for 联想)
        s.show(read("淡", "谈", step = 0.3))
        s.show(read("话"))
        assertEquals("谈话", s.apply(Action.Pick(0)).commit)
        s.show(read("天", "人"))
        s.apply(Action.CommitRaw)
        s.show(read("天", "人"))
        s.apply(Action.Select(0))
        s.show(read("天", "人"))
        s.apply(Action.Key('，'))
        assertEquals(setOf("谈话", "天", "谈话 天", "天 天"), learner.used())
        // never counted for pinyin's order (D28)
        assertEquals(0f, user.count(handwritingData.wordIndex.find("谈话")), 0f)
        val again = Learner(handwritingData)
        val t = HandwritingSession(handwritingDecoder(again.model), TextWords(handwritingData.model, handwritingData.wordIndex), user = again.model, habits = again.habits)
        // another than the first: its words and their order, a word of two as the dictionary reads it
        t.show(read("淡", "谈", step = 0.3))
        val shown = t.show(read("天", "话", step = 3.0))
        assertEquals("淡天", shown.preedit)
        assertEquals("谈话", t.apply(Action.Pick(shown.candidates.indexOf("话"))).commit)
        assertEquals(setOf("谈话"), again.used())
        // each character under what its ink was read as, no word before it: the one picked corrected,
        // the one before it as it was read
        val stats = again.log.state.stats.keys.filter { it.code.kind == Kinds.HANDWRITING && it.code.keys.isNotEmpty() }
        assertEquals(setOf(KeyedRows.handwriting(listOf("淡", "谈")) to "谈", KeyedRows.handwriting(listOf("天", "话")) to "话"), stats.map { it.code.keys to it.text }.toSet())
        t.show(read("淡", "谈", step = 0.3))
        t.show(read("话", "天", "人"))
        assertEquals("淡人", t.apply(Action.Pick(2)).commit)
        // its words, never made one (impl/design-1b.md §4), after the word put in before it
        assertEquals(setOf("谈话", "淡", "人", "谈话 淡", "淡 人"), again.used())
        // where nothing is learned (a password), not even that
        t.learning = false
        t.show(read("天", "山"))
        assertEquals("山", t.apply(Action.Pick(1)).commit)
        assertFalse(again.used().any { it.startsWith("山") })
    }

    @Test
    fun `once a sentence is put in, what may follow is offered, picked, learned and offered on`() {
        val d = handwritingData
        val learner = Learner(d)
        val user = learner.model
        val s = HandwritingSession(
            handwritingDecoder(user), TextWords(d.model, d.wordIndex), user = user, predictor = Predictor(d.model, d.vocabulary, d.dictionary, user),
            habits = learner.habits,
        )
        s.show(read("高"))
        val put = s.apply(Action.Pick(0))
        assertEquals("高", put.commit)
        assertTrue(put.predicting)
        assertEquals("", put.preedit)
        assertEquals("山", put.candidates.first())
        // a pick puts it in, the user's choice learned after the word before it: for 联想 alone, no use of the word (review-C S1)
        assertEquals("山", s.apply(Action.Pick(0)).commit)
        assertEquals(setOf("高", "山", "高 山"), learner.used())
        // enter puts in what is written, as pinyin's, nothing offered after it
        s.show(read("高"))
        assertFalse(s.apply(Action.CommitRaw).predicting)
        // a stroke takes the offer away
        s.show(read("高"))
        s.apply(Action.Pick(0))
        val written = s.show(read("人", "天"))
        assertFalse(written.predicting)
        assertEquals("人", written.preedit)
        assertEquals(listOf("人", "天"), written.candidates)
        // so do backspace and a key, both the app's
        s.apply(Action.Pick(0))
        s.show(read("高"))
        s.apply(Action.Pick(0))
        val back = s.apply(Action.Backspace)
        assertFalse(back.handled)
        assertTrue(back.candidates.isEmpty())
        s.show(read("高"))
        s.apply(Action.Pick(0))
        val key = s.apply(Action.Key('，'))
        assertFalse(key.handled)
        assertTrue(key.candidates.isEmpty())
        // where nothing is learned (a password), nothing offered
        s.learning = false
        s.show(read("高"))
        assertFalse(s.apply(Action.Pick(0)).predicting)
    }

    @Test
    fun `a sentence model puts the sentence it reads best first, the refiner once the character is finished`() {
        val d = handwritingData
        // each prefers 淡话 to the decoder's 谈话, after the text before
        val seen = ArrayList<String>()
        val picker = SentencePicker { context, readings, _ -> seen += context; readings.indexOf("淡话").coerceAtLeast(0) }
        val refiner = SentenceRefiner { _, readings, _, _ -> readings.indexOf("淡话").takeIf { it >= 0 } ?: SentenceRefiner.NONE }
        val written = listOf(read("淡", "谈", step = 0.3), read("话"))
        val words = TextWords(d.model, d.wordIndex)

        val medium = HandwritingSession(handwritingDecoder(), words, reranker = picker)
        medium.apply(Action.Context("我说"))
        written.forEach { medium.show(it) }
        val shown = medium.apply(Action.Ink)
        assertEquals("淡话", shown.preedit)
        assertEquals("我说", seen.last())
        assertFalse(shown.refines)
        // the sentence ending as it does gives way, the rest stay
        assertEquals(1, shown.candidates.count { it == "话" })

        val high = HandwritingSession(handwritingDecoder(), words, refiner = refiner)
        written.forEach { high.show(it) }
        // a character still being written is not weighed
        val before = high.apply(Action.Ink)
        assertEquals("谈话", before.preedit)
        assertFalse(before.refines)
        // nor one finished that is not the last
        high.finished(pieces - 2)
        assertFalse(high.apply(Action.Ink).refines)
        high.finished(pieces - 1)
        assertTrue(high.apply(Action.Ink).refines)
        val after = high.apply(Action.Refine)
        assertEquals("淡话", after.preedit)
        assertFalse(after.refines)
        // nothing more to weigh
        assertEquals(after, high.apply(Action.Refine))
        // the first, put in, is the sentence shown
        assertEquals("淡话", high.apply(Action.Pick(0)).commit)
        // a page turned while it weighs: the order shown stays, the weighing dropped
        val paged = HandwritingSession(handwritingDecoder(), words, pageSize = 1, refiner = refiner)
        written.forEach { paged.show(it) }
        paged.finished(pieces - 1)
        assertTrue(paged.apply(Action.Ink).refines)
        val turned = paged.apply(Action.NextPage)
        assertFalse(turned.refines)
        assertEquals(turned, paged.apply(Action.Refine))
    }

    @Test
    fun `enter, or a key the app takes, puts the best sentence in first`() {
        val s = session()
        s.show(read("淡", "谈", step = 0.3))
        s.show(read("话"))
        assertEquals("谈话", s.apply(Action.CommitRaw).commit)
        s.show(read("天", "人"))
        val key = s.apply(Action.Key('，'))
        assertEquals("天", key.commit)
        assertFalse(key.handled)
        assertFalse(s.apply(Action.Key('，')).handled)
        assertFalse(s.reads('a'))
    }

    @Test
    fun `pages turn within the readings`() {
        val s = session(pageSize = 1)
        s.show(read("天", "人"))
        assertEquals(listOf("人"), s.apply(Action.NextPage).candidates)
        assertEquals(listOf("人"), s.apply(Action.NextPage).candidates)
        assertEquals(listOf("天"), s.apply(Action.PreviousPage).candidates)
        assertEquals(listOf("天"), s.apply(Action.PreviousPage).candidates)
    }

    @Test
    fun `the text before goes on into what is written`() {
        val s = session()
        val shan = read("人", "山", step = 0.5)
        assertEquals("人", s.show(shan).preedit)
        s.apply(Action.Context("高"))
        assertEquals("山", s.show(shan).preedit)
        // what was put in counts as before
        s.apply(Action.Reset)
        s.show(read("高"))
        s.apply(Action.Pick(0))
        assertEquals("山", s.show(shan).preedit)
        // the cursor moved away: not known, as at a start
        s.apply(Action.Pick(0))
        s.passedOn()
        assertEquals("人", s.show(shan).preedit)
    }

    @Test
    fun `without a decoder each character is its first reading`() {
        val s = HandwritingSession(null, null)
        s.show(read("淡", "谈"))
        val two = s.show(read("话", "天"))
        assertEquals("淡话", two.preedit)
        assertEquals(listOf("话", "天"), two.candidates)
        assertEquals("淡天", s.apply(Action.Pick(1)).commit)
    }

    @Test
    fun `the keyboard sends the pad's key as an Ink, and the rest as any session's`() {
        val s = session()
        val keyboard = Keyboard(s, nineKeys = false)
        s.offer(read("天", "人"), 0)
        assertEquals("天", keyboard.onEvent(EngineEvent.CHAR, Keyboard.INK).preedit)
        // space picks the first, as in pinyin
        assertEquals("天", keyboard.onEvent(EngineEvent.CHAR, ' '.code).commit)
        s.offer(read("天", "人"), 1)
        keyboard.onEvent(EngineEvent.CHAR, Keyboard.INK)
        // a digit picks the one it numbers
        assertEquals("人", keyboard.onEvent(EngineEvent.CHAR, '2'.code).commit)
    }

    @Test
    fun `what no handwriting does changes nothing`() {
        val s = session()
        val shown = s.show(read("天"))
        for (a in listOf(Action.Refine, Action.Forget(0), Action.Pin(0), Action.Unpin(0), Action.Block(0), Action.Syllable(0), Action.Letter('a'))) {
            assertEquals(shown, s.apply(a))
        }
        assertFalse(s.apply(Action.Cursor(0)).handled)
        // nothing learned, nothing to block it in: a candidate's long press offers nothing
        assertTrue(s.offers(0).isEmpty())
    }

    @Test
    fun `a sentence picked is learned after the word put in before it, the first too`() {
        val learner = Learner(handwritingData)
        val user = learner.model
        val s = HandwritingSession(handwritingDecoder(user), TextWords(handwritingData.model, handwritingData.wordIndex), user = user, habits = learner.habits)
        s.show(read("高"))
        s.apply(Action.Pick(0))
        s.show(read("天", "人"))
        assertEquals("人", s.apply(Action.Pick(1)).commit)
        assertEquals(setOf("高", "人", "高 人"), learner.used())
    }

    @Test
    fun `a pick with a character of several readings is learned by its words' text, no reading guessed`() {
        val d = polyphoneData
        val learner = Learner(d)
        val user = learner.model
        val s = HandwritingSession(polyphoneDecoder(user), TextWords(d.model, d.wordIndex), user = user, habits = learner.habits)
        s.show(read("人"))
        val shown = s.show(read("人", "行"))
        assertEquals("人行", s.apply(Action.Pick(shown.candidates.indexOf("行"))).commit)
        assertEquals(setOf("人", "行", "人 行"), learner.used())
        // no reading guessed, and none counted for pinyin's order (D28)
        assertEquals(0f, user.count(d.wordIndex.find("行")), 0f)
    }

    @Test
    fun `what is written after a key the app took goes on from the key, not the words before it`() {
        val s = session()
        val shan = read("人", "山", step = 0.5)
        s.show(read("高"))
        assertEquals("高", s.apply(Action.Key('，')).commit)
        assertEquals("人", s.show(shan).preedit)
        // a key with nothing written: 高 put in, then a space
        s.apply(Action.Reset)
        s.show(read("高"))
        s.apply(Action.Pick(0))
        s.apply(Action.Key(' '))
        assertEquals("人", s.show(shan).preedit)
    }

    @Test
    fun `a session made in place of another ends every piece that one heard of`() {
        val s = session()
        s.show(read("天"), piece = 0)
        s.apply(Action.Pick(0))
        s.show(read("人"), piece = 1)
        s.offer(read("山"), 2)
        assertEquals(2, s.latestPiece)
        val next = HandwritingSession(handwritingDecoder(), TextWords(handwritingData.model, handwritingData.wordIndex), ended = s.latestPiece)
        assertEquals(2, next.ended)
        // late readings of the piece put in, of the one written, of the one waiting: none a character of its own
        assertEquals("", next.show(read("天"), piece = 0).preedit)
        assertEquals("", next.show(read("人"), piece = 1).preedit)
        assertEquals("", next.show(read("山"), piece = 2).preedit)
        assertEquals("山", next.show(read("山"), piece = 3).preedit)
    }

    // a session whose refiner prefers 淡话 to the decoder's 谈话
    private fun refining() = HandwritingSession(
        handwritingDecoder(), TextWords(handwritingData.model, handwritingData.wordIndex),
        refiner = SentenceRefiner { _, readings, _, _ -> readings.indexOf("淡话").takeIf { it >= 0 } ?: SentenceRefiner.NONE },
    )

    @Test
    fun `the weighing waits through what changes nothing, and comes back for a finished character taken back to`() {
        val s = refining()
        s.show(read("淡", "谈", step = 0.3), piece = 0)
        s.show(read("话"), piece = 1)
        s.finished(1)
        assertTrue(s.apply(Action.Ink).refines)
        // the cursor, which handwriting keeps none of; an Ink that reads nothing new, the next one's first dot
        assertTrue(s.apply(Action.Cursor(0)).refines)
        assertTrue(s.apply(Action.Step(1)).refines)
        assertTrue(s.show(InkReading.NONE, piece = 2).refines)
        // a character after it is not weighed while written; taken back, the one before it is again
        assertFalse(s.show(read("天"), piece = 2).refines)
        val back = s.apply(Action.Backspace)
        assertEquals("谈话", back.preedit)
        assertTrue(back.refines)
        assertEquals("淡话", s.apply(Action.Refine).preedit)
    }

    @Test
    fun `a character finished at the Ink that reads it again is weighed, one written after it is not`() {
        val s = refining()
        s.show(read("淡", "谈", step = 0.3), piece = 0)
        s.show(read("天"), piece = 1)
        // its last stroke read and the pen up long enough, told at one Ink
        s.offer(read("话"), 1)
        s.finished(1)
        val shown = s.apply(Action.Ink)
        assertEquals("谈话", shown.preedit)
        assertTrue(shown.refines)
        assertEquals("淡话", s.apply(Action.Refine).preedit)
        // finished, then the next character begun, at one Ink: the last is not finished
        val other = refining()
        other.show(read("淡", "谈", step = 0.3), piece = 0)
        other.offer(read("话"), 1)
        other.finished(1)
        other.offer(read("天"), 2)
        assertFalse(other.apply(Action.Ink).refines)
    }

    @Test
    fun `a long press on what may follow or on a candidate forgets what was learned of it or blocks it`() {
        val d = handwritingData
        val learner = Learner(d)
        val user = learner.model
        val forgotten = ArrayList<String>()
        val s = HandwritingSession(
            handwritingDecoder(user), TextWords(d.model, d.wordIndex), user = user, predictor = Predictor(d.model, d.vocabulary, d.dictionary, user),
            // the host forgets it in the learning record (Engines.forgottenEverywhere)
            block = { user.block(it) }, forgotten = { forgotten += it; learner.habits.forget(it) }, habits = learner.habits,
        )
        s.show(read("高"))
        val offered = s.apply(Action.Pick(0))
        assertTrue(offered.actionable)
        assertEquals("山", offered.candidates.first())
        // never picked: nothing learned of it to forget
        assertEquals(setOf(Offer.BLOCK), s.offers(0))
        s.apply(Action.Pick(0))
        s.show(read("高"))
        s.apply(Action.Pick(0))
        assertEquals(setOf(Offer.FORGET, Offer.BLOCK), s.offers(0))
        // picked twice, by its text alone: pinyin's count of it moves not (review C S1)
        assertEquals(0f, user.count(d.wordIndex.find("山")), 0f)
        assertEquals("山", s.apply(Action.Forget(0)).candidates.first())
        assertFalse(learner.used().any { "山" in it })
        assertEquals(listOf("山"), forgotten)
        assertFalse("山" in s.apply(Action.Block(0)).candidates)
        // where nothing is to be kept (a password), nothing offered, the predictions shown or not
        s.learning = false
        assertTrue(s.offers(0).isEmpty())
        s.learning = true
        // a candidate's character as a prediction (U-29): nothing learned of 天 yet; blocked in every
        // reading, what is written read again without it
        assertTrue(s.show(read("天", "人")).actionable)
        assertEquals(setOf(Offer.BLOCK), s.offers(0))
        assertEquals(listOf("人"), s.apply(Action.Block(0)).candidates)
        assertEquals("人", s.apply(Action.Pick(0)).commit)
        // one picked: what was learned of it forgotten everywhere; never pinned (C18)
        s.show(read("人"))
        assertEquals(setOf(Offer.FORGET, Offer.BLOCK), s.offers(0))
        assertEquals(listOf("人"), s.apply(Action.Forget(0)).candidates)
        assertEquals(listOf("山", "人"), forgotten)
        assertFalse(learner.used().any { "人" in it })
        assertEquals(listOf("人"), s.apply(Action.Pin(0)).candidates)
    }

    @Test
    fun `a character blocked then unblocked comes back when what is written is read again`() {
        val d = handwritingData
        val user = Learner(d).model
        val blocked = ArrayList<UserModel.Entry>()
        val s = HandwritingSession(handwritingDecoder(user), TextWords(d.model, d.wordIndex), user = user, block = { blocked += it; user.block(it) })
        s.show(read("天", "人"))
        assertEquals(listOf("人"), s.apply(Action.Block(0)).candidates)
        // the undo (Engines): unblocked, then read again, on no search kept from while it was blocked
        user.unblock(blocked.single())
        assertEquals("天", s.reread()?.candidates?.first())
    }

    @Test
    fun `backspace with nothing written takes the deleted text out of what goes before, enter keeps it`() {
        val d = handwritingData
        val learner = Learner(d)
        val user = learner.model
        val seen = ArrayList<String>()
        val picker = SentencePicker { context, _, _ -> seen += context; 0 }
        val s = HandwritingSession(handwritingDecoder(user), TextWords(d.model, d.wordIndex), user = user, reranker = picker, habits = learner.habits)
        s.show(read("高"))
        assertEquals("高", s.apply(Action.Pick(0)).commit)
        // enter with nothing written: the text before still ends with the word, learned after it
        assertFalse(s.apply(Action.CommitRaw).handled)
        s.show(read("天", "人"))
        assertEquals("高", seen.last())
        assertEquals("人", s.apply(Action.Pick(1)).commit)
        assertTrue("高 人" in learner.used())
        // backspace with nothing written: the app deleted it, neither read after nor learned after
        assertFalse(s.apply(Action.Backspace).handled)
        s.show(read("山", "天"))
        assertEquals("高", seen.last())
        assertEquals("天", s.apply(Action.Pick(1)).commit)
        assertTrue("天" in learner.used())
        assertFalse("人 天" in learner.used())
    }

    // HW-14 (review-int-runtime M1): 人 picked over 天 twice running, first where the pad reads the same
    @Test
    fun `a character picked over the first twice running is first for what reads the same`() {
        val learner = Learner(handwritingData)
        val user = learner.model
        val s = HandwritingSession(handwritingDecoder(user), TextWords(handwritingData.model, handwritingData.wordIndex), user = user, habits = learner.habits)
        assertEquals("天", s.show(read("天", "人")).candidates.first())
        assertEquals("人", s.apply(Action.Pick(1)).commit)
        // once: before those not used, the first kept; what 为什么排在这 says, under what was read (U-29, U-81)
        assertEquals(listOf("天", "人"), s.show(read("天", "人")).candidates.take(2))
        val why = s.why(1)!!
        assertEquals(HabitRanker.Tier.USED, why.tier)
        assertEquals(1, why.uses)
        assertEquals(Code(Kinds.HANDWRITING, KeyedRows.handwriting(listOf("天", "人"))), why.code)
        assertEquals("人", s.apply(Action.Pick(1)).commit)
        // twice running over the first: first
        assertEquals("人", s.show(read("天", "人")).candidates.first())
        assertEquals(HabitRanker.Tier.FIRST_HERE, s.why(0)!!.tier)
        s.apply(Action.Reset)
        // what reads otherwise is not ranked so
        assertEquals("天", s.show(read("天", "大")).candidates.first())
    }
}
