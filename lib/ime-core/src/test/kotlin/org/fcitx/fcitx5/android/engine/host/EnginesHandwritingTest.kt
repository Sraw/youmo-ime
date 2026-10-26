/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.handwriting.HandwritingSession
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.session.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The handwriting pad's input method, as [Engines] serves it: readings offered, then the Ink key. */
class EnginesHandwritingTest : EnginesFixture() {

    @get:Rule
    val folder = TemporaryFolder()

    // the pad's reading offered, then its key: shown, read with the pinyin model after the text before
    @Test
    fun handwritingShowsWhatThePadOffered() {
        val engines = Engines(::load, null, warmUps = inPlace)
        // a field's text is told the keyboards there are: the input method switched to first
        engines.onEvent(Engines.HANDWRITING, EngineEvent.RESET, 0)
        engines.context(Engines.HANDWRITING, "好")
        engines.offerInk(InkReading(listOf("你", "拟"), doubleArrayOf(0.0, 0.0)), 0)
        val shown = engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK)
        // 好 then 拟: the model's bigram
        assertEquals("拟", shown.preedit)
        assertEquals(listOf("拟", "你"), engines.candidates(Engines.HANDWRITING, 0, 5).map { it.text })
        assertEquals("你", engines.onEvent(Engines.HANDWRITING, EngineEvent.PICK, 1).commit)
        assertTrue(Engines.PINYIN_DATA in loaded)
        assertEquals(0, engines.inkEnded)
    }

    // a second character joins the first, nothing put in till one is picked
    @Test
    fun handwritingReadsTheCharactersWrittenTogether() {
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.offerInk(InkReading(listOf("好"), doubleArrayOf(0.0)), 0)
        engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK)
        engines.offerInk(InkReading(listOf("你", "拟"), doubleArrayOf(0.0, 0.0)), 1)
        val two = engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK)
        assertEquals("", two.commit)
        assertEquals("好拟", two.preedit)
        assertEquals(HandwritingSession.NO_PIECE, engines.inkEnded)
        assertEquals("好拟", engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, ' '.code).commit)
        assertEquals(1, engines.inkEnded)
    }

    // UAT HW-20 (U-06): two characters no word, written and put in twice, are the user's word, typed
    // in pinyin at the reading the dictionary gives them; never moving pinyin's order (D28)
    @Test
    fun aSentenceWrittenTwiceThatIsNoWordIsTheUsersWord() {
        Engines(::load, folder.newFolder(), warmUps = inPlace).use { engines ->
            val before = engines.type(Engines.PINYIN, "ni").candidates
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            repeat(2) { n ->
                engines.onEvent(Engines.HANDWRITING, EngineEvent.RESET, 0)
                engines.context(Engines.HANDWRITING, "")
                engines.offerInk(InkReading(listOf("好"), doubleArrayOf(0.0)), 2 * n)
                engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK)
                engines.offerInk(InkReading(listOf("拟"), doubleArrayOf(0.0)), 2 * n + 1)
                engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK)
                assertEquals("好拟", engines.onEvent(Engines.HANDWRITING, EngineEvent.PICK, 0).commit)
            }
            assertEquals(listOf("好拟"), engines.userWords(Engines.UserWord.Kind.LEARNED).map { it.text })
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            engines.context(Engines.PINYIN, "")
            assertEquals("好拟", engines.type(Engines.PINYIN, "haoni").candidates.first())
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            assertTrue("好拟" in engines.type(Engines.PINYIN, "hn").candidates)
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            assertEquals(before, engines.type(Engines.PINYIN, "ni").candidates)
        }
    }

    // a word added drops the sessions: the piece ended stays ended, a reading late for it is not shown
    @Test
    fun handwritingMadeAgainKeepsWhatWasEnded() {
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.offerInk(InkReading(listOf("好"), doubleArrayOf(0.0)), 0)
        engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK)
        assertEquals("好", engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, ' '.code).commit)
        assertEquals(0, engines.inkEnded)
        assertTrue(engines.addWord("你好", "ni hao"))
        assertEquals(0, engines.inkEnded)
        engines.offerInk(InkReading(listOf("好"), doubleArrayOf(0.0)), 0)
        assertEquals("", engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK).preedit)
        assertEquals(0, engines.inkEnded)
    }

    // what follows a character written may be blocked there, as in pinyin: 拟 after 好
    @Test
    fun handwritingOffersToBlockAPrediction() {
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.offerInk(InkReading(listOf("好"), doubleArrayOf(0.0)), 0)
        engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK)
        val after = engines.onEvent(Engines.HANDWRITING, EngineEvent.PICK, 0)
        assertEquals(listOf("拟"), after.candidates)
        assertTrue(Offer.BLOCK in engines.offers(Engines.HANDWRITING, 0))
    }

    // U-72 in handwriting (review-E-engine S10): 拟 after 好 turned off there, as pinyin's
    @Test
    fun handwritingTurnsAPredictionOffAfterItsWord() {
        val engines = Engines(::load, folder.newFolder(), warmUps = inPlace)
        engines.offerInk(InkReading(listOf("好"), doubleArrayOf(0.0)), 0)
        engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK)
        assertEquals(listOf("拟"), engines.onEvent(Engines.HANDWRITING, EngineEvent.PICK, 0).candidates)
        val menu = engines.learning(Engines.HANDWRITING, LearningOp.Menu(0, "拟")) as LearningResult.Menu
        assertTrue(MenuItem.NO_PREDICT in menu.items)
        assertTrue(engines.learning(Engines.HANDWRITING, LearningOp.NoPredict(0, "拟")) is LearningResult.Done)
        assertTrue(engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.AGAIN).candidates.isEmpty())
        engines.close()
    }

    // turned off after 好, not offered when 好 is written and put in again
    @Test
    fun handwritingsPredictionTurnedOffStaysOffWhenItsWordIsWrittenAgain() {
        val engines = Engines(::load, folder.newFolder(), warmUps = inPlace)
        fun writeHao() {
            engines.offerInk(InkReading(listOf("好"), doubleArrayOf(0.0)), 0)
            engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK)
        }
        writeHao()
        assertEquals(listOf("拟"), engines.onEvent(Engines.HANDWRITING, EngineEvent.PICK, 0).candidates)
        assertTrue(engines.learning(Engines.HANDWRITING, LearningOp.NoPredict(0, "拟")) is LearningResult.Done)
        engines.onEvent(Engines.HANDWRITING, EngineEvent.RESET, 0)
        writeHao()
        assertTrue(engines.onEvent(Engines.HANDWRITING, EngineEvent.PICK, 0).candidates.isEmpty())
        engines.close()
    }

    // review-int S1: 不再联想 taken back shows what may follow again, the session kept with its word before
    @Test
    fun handwritingsPredictionTurnedOffAndTakenBackIsOfferedAgain() {
        Engines(::load, folder.newFolder(), warmUps = inPlace).use { engines ->
            engines.offerInk(InkReading(listOf("好"), doubleArrayOf(0.0)), 0)
            engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK)
            assertEquals(listOf("拟"), engines.onEvent(Engines.HANDWRITING, EngineEvent.PICK, 0).candidates)
            val done = engines.learning(Engines.HANDWRITING, LearningOp.NoPredict(0, "拟")) as LearningResult.Done
            assertTrue(engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.AGAIN).candidates.isEmpty())
            assertEquals(LearningResult.Done(), engines.learning(Engines.HANDWRITING, LearningOp.Undo(done.undo)))
            assertEquals(listOf("拟"), engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.AGAIN).candidates)
        }
    }

    // review-int S1: an undo while characters are written keeps them, read anew, the pad's piece not ended
    @Test
    fun handwritingKeepsWhatIsWrittenThroughAnUndo() {
        Engines(::load, folder.newFolder(), warmUps = inPlace).use { engines ->
            // 拟 picked once: learned, to be deleted
            engines.offerInk(InkReading(listOf("拟"), doubleArrayOf(0.0)), 0)
            engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK)
            assertEquals("拟", engines.onEvent(Engines.HANDWRITING, EngineEvent.PICK, 0).commit)
            engines.onEvent(Engines.HANDWRITING, EngineEvent.RESET, 0)
            engines.offerInk(InkReading(listOf("拟"), doubleArrayOf(0.0)), 1)
            assertEquals("拟", engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK).preedit)
            val ended = engines.inkEnded
            val done = engines.learning(Engines.HANDWRITING, LearningOp.Delete(0, "拟")) as LearningResult.Done
            assertEquals(LearningResult.Done(), engines.learning(Engines.HANDWRITING, LearningOp.Undo(done.undo)))
            val again = engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.AGAIN)
            assertEquals("拟", again.preedit)
            assertEquals(ended, engines.inkEnded)
        }
    }

    // a prediction blocked there is blocked for pinyin too, one forgotten no longer learned: the one lexicon
    @Test
    fun handwritingBlocksAndForgetsAPredictionInTheLexicon() {
        Engines(::load, folder.newFolder()).use { engines ->
            fun afterHao(): List<String> {
                engines.offerInk(InkReading(listOf("好"), doubleArrayOf(0.0)), engines.inkEnded + 1)
                engines.onEvent(Engines.HANDWRITING, EngineEvent.CHAR, Keyboard.INK)
                return engines.onEvent(Engines.HANDWRITING, EngineEvent.PICK, 0).candidates
            }
            assertEquals(listOf("拟"), afterHao())
            // picked: learned, to be forgotten
            assertEquals("拟", engines.onEvent(Engines.HANDWRITING, EngineEvent.PICK, 0).commit)
            afterHao()
            assertTrue(Offer.FORGET in engines.offers(Engines.HANDWRITING, 0))
            engines.onEvent(Engines.HANDWRITING, EngineEvent.FORGET, 0)
            assertFalse(Offer.FORGET in engines.offers(Engines.HANDWRITING, 0))
            engines.onEvent(Engines.HANDWRITING, EngineEvent.BLOCK, 0)
            engines.onEvent(Engines.HANDWRITING, EngineEvent.RESET, 0)
            assertEquals(listOf("你"), engines.type(Engines.PINYIN, "ni").candidates)
        }
    }
}
