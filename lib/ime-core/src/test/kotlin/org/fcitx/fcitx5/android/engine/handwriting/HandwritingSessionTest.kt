/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
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
    fun `a sentence picked other than the first is learned, its words as read, the first not`() {
        val user = UserModel(handwritingData.dictionary, handwritingData.vocabulary)
        val s = HandwritingSession(handwritingDecoder(user), TextWords(handwritingData.model, handwritingData.wordIndex), user = user)
        // the first, however it is put in
        s.show(read("淡", "谈", step = 0.3))
        s.show(read("话"))
        assertEquals("谈话", s.apply(Action.Pick(0)).commit)
        s.show(read("天", "人"))
        s.apply(Action.CommitRaw)
        s.show(read("天", "人"))
        s.apply(Action.Select(0))
        s.show(read("天", "人"))
        s.apply(Action.Key('，'))
        assertEquals(emptySet<String>(), user.learned())
        // another: its words and their order, a word of two as the dictionary reads it
        s.show(read("淡", "谈", step = 0.3))
        val shown = s.show(read("天", "话", step = 3.0))
        assertEquals("淡天", shown.preedit)
        assertEquals("谈话", s.apply(Action.Pick(shown.candidates.indexOf("话"))).commit)
        assertEquals(setOf("谈话(tan hua)"), user.learned())
        s.show(read("淡", "谈", step = 0.3))
        s.show(read("话", "天", "人"))
        assertEquals("淡人", s.apply(Action.Pick(2)).commit)
        // after the word put in before it, as pinyin learns a pick
        assertEquals(setOf("谈话(tan hua)", "淡(dan)", "人(ren)", "淡(dan) 人(ren)", "谈话(tan hua) 淡(dan)"), user.learned())
        // where nothing is learned (a password), not even that
        s.learning = false
        s.show(read("天", "山"))
        assertEquals("山", s.apply(Action.Pick(1)).commit)
        assertFalse(user.learned().any { it.startsWith("山") })
    }

    @Test
    fun `once a sentence is put in, what may follow is offered, picked, learned and offered on`() {
        val d = handwritingData
        val user = UserModel(d.dictionary, d.vocabulary)
        val s = HandwritingSession(handwritingDecoder(user), TextWords(d.model, d.wordIndex), user = user, predictor = Predictor(d.model, d.vocabulary, d.dictionary, user))
        s.show(read("高"))
        val put = s.apply(Action.Pick(0))
        assertEquals("高", put.commit)
        assertTrue(put.predicting)
        assertEquals("", put.preedit)
        assertEquals("山", put.candidates.first())
        // a pick puts it in, the user's choice learned after the word before it
        assertEquals("山", s.apply(Action.Pick(0)).commit)
        assertEquals(setOf("山(shan)", "高(gao) 山(shan)"), user.learned())
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
        assertTrue(s.offers(0).isEmpty())
    }

    @Test
    fun `a sentence picked is learned after the word put in before it, the first or not`() {
        val user = UserModel(handwritingData.dictionary, handwritingData.vocabulary)
        val s = HandwritingSession(handwritingDecoder(user), TextWords(handwritingData.model, handwritingData.wordIndex), user = user)
        s.show(read("高"))
        s.apply(Action.Pick(0))
        s.show(read("天", "人"))
        assertEquals("人", s.apply(Action.Pick(1)).commit)
        assertEquals(setOf("人(ren)", "高(gao) 人(ren)"), user.learned())
    }

    @Test
    fun `a character picked alone is learned as it is likeliest read`() {
        val d = polyphoneData
        val user = UserModel(d.dictionary, d.vocabulary)
        val s = HandwritingSession(polyphoneDecoder(user), TextWords(d.model, d.wordIndex), user = user)
        assertEquals(listOf("人", "行"), s.show(read("人", "行")).candidates)
        assertEquals("行", s.apply(Action.Pick(1)).commit)
        // not hang, the first of its syllables by id
        assertEquals(setOf("行(xing)"), user.learned())
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
    fun `a long press on what may follow forgets what was learned of it or blocks it, on a candidate nothing`() {
        val d = handwritingData
        val user = UserModel(d.dictionary, d.vocabulary)
        val forgotten = ArrayList<String>()
        val s = HandwritingSession(
            handwritingDecoder(user), TextWords(d.model, d.wordIndex), user = user, predictor = Predictor(d.model, d.vocabulary, d.dictionary, user),
            block = { user.block(it) }, forgotten = { forgotten += it },
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
        assertEquals("山", s.apply(Action.Forget(0)).candidates.first())
        assertFalse(user.learned().any { "山" in it })
        assertEquals(listOf("山"), forgotten)
        assertFalse("山" in s.apply(Action.Block(0)).candidates)
        // where nothing is to be kept (a password), nothing offered, the predictions shown or not
        s.learning = false
        assertTrue(s.offers(0).isEmpty())
        s.learning = true
        // a candidate shows one character of the sentence a pick puts in: nothing to offer
        assertFalse(s.show(read("天")).actionable)
        assertTrue(s.offers(0).isEmpty())
    }

    @Test
    fun `backspace with nothing written takes the deleted text out of what goes before, enter keeps it`() {
        val d = handwritingData
        val user = UserModel(d.dictionary, d.vocabulary)
        val seen = ArrayList<String>()
        val picker = SentencePicker { context, _, _ -> seen += context; 0 }
        val s = HandwritingSession(handwritingDecoder(user), TextWords(d.model, d.wordIndex), user = user, reranker = picker)
        s.show(read("高"))
        assertEquals("高", s.apply(Action.Pick(0)).commit)
        // enter with nothing written: the text before still ends with the word, learned after it
        assertFalse(s.apply(Action.CommitRaw).handled)
        s.show(read("天", "人"))
        assertEquals("高", seen.last())
        assertEquals("人", s.apply(Action.Pick(1)).commit)
        assertTrue("高(gao) 人(ren)" in user.learned())
        // backspace with nothing written: the app deleted it, neither read after nor learned after
        assertFalse(s.apply(Action.Backspace).handled)
        s.show(read("山", "天"))
        assertEquals("高", seen.last())
        assertEquals("天", s.apply(Action.Pick(1)).commit)
        assertTrue("天(tian)" in user.learned())
        assertFalse("人(ren) 天(tian)" in user.learned())
    }
}
