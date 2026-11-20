/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.handwriting.HandwritingSession
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The handwriting pad's input method, as [Engines] serves it: readings offered, then the Ink key. */
class EnginesHandwritingTest : EnginesFixture() {

    // the pad's reading of [piece] offered with its key, every text scored alike: the target carried in the payload
    private fun Engines.write(piece: Int, vararg texts: String) = onEvent(
        InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.INK, payload = Action.Ink(piece, InkReading(texts.toList(), DoubleArray(texts.size))),
    )

    // the pad's reading offered, then its key: shown, read with the pinyin model after the text before
    @Test
    fun handwritingShowsWhatThePadOffered() {
        val engines = Engines(::load, null, warmUps = inPlace)
        // a field's text is told the keyboards there are: the input method switched to first
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
        engines.context(InputMethodCatalog.PINYIN, "好")
        val shown = engines.write(0, "你", "拟")
        assertEquals("拟", shown.preedit)
        assertEquals(listOf("拟", "你"), engines.candidates(InputMethodCatalog.PINYIN, 0, 5).map { it.text })
        assertEquals("你", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 1).commit)
        assertTrue(InputMethodCatalog.PINYIN_DATA in loaded)
        assertEquals(0, engines.inkEnded)
    }

    // a second character joins the first, nothing put in till one is picked
    @Test
    fun handwritingReadsTheCharactersWrittenTogether() {
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.write(0, "好")
        val two = engines.write(1, "你", "拟")
        assertEquals("", two.commit)
        assertEquals("好拟", two.preedit)
        assertEquals(HandwritingSession.NO_PIECE, engines.inkEnded)
        assertEquals("好拟", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code).commit)
        assertEquals(1, engines.inkEnded)
    }

    // never moving pinyin's order
    @Test
    fun aSentenceWrittenTwiceThatIsNoWordIsTheUsersWord() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { engines ->
            val before = engines.type(InputMethodCatalog.PINYIN, "ni").candidates
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            repeat(2) { n ->
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
                engines.context(InputMethodCatalog.PINYIN, "")
                engines.write(2 * n, "好")
                engines.write(2 * n + 1, "拟")
                assertEquals("好拟", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0).commit)
            }
            assertEquals(listOf("好拟"), engines.userWords(UserWord.Kind.LEARNED).map { it.text })
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            engines.context(InputMethodCatalog.PINYIN, "")
            assertEquals("好拟", engines.type(InputMethodCatalog.PINYIN, "haoni").candidates.first())
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertTrue("好拟" in engines.type(InputMethodCatalog.PINYIN, "hn").candidates)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertEquals(before, engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
        }
    }

    // a word added drops the sessions: the piece ended stays ended, a reading late for it is not shown
    @Test
    fun handwritingMadeAgainKeepsWhatWasEnded() {
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.write(0, "好")
        assertEquals("好", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code).commit)
        assertEquals(0, engines.inkEnded)
        assertTrue(engines.addWord("你好", "ni hao"))
        assertEquals(0, engines.inkEnded)
        assertEquals("", engines.write(0, "好").preedit)
        assertEquals(0, engines.inkEnded)
    }

    // what follows a character written may be blocked there, as in pinyin: 拟 after 好
    @Test
    fun handwritingOffersToBlockAPrediction() {
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.write(0, "好")
        val after = engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0)
        assertEquals(listOf("拟"), after.candidates)
        assertTrue(Offer.BLOCK in engines.offers(InputMethodCatalog.PINYIN, 0))
    }

    // 拟 after 好 turned off there, as pinyin's
    @Test
    fun handwritingTurnsAPredictionOffAfterItsWord() {
        val engines = Engines(::load, MemoryFiles(), warmUps = inPlace)
        engines.write(0, "好")
        assertEquals(listOf("拟"), engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0).candidates)
        val menu = engines.learning(InputMethodCatalog.PINYIN, LearningOp.Menu(0, "拟")) as LearningResult.Menu
        assertTrue(MenuItem.NO_PREDICT in menu.items)
        assertTrue(engines.learning(InputMethodCatalog.PINYIN, LearningOp.NoPredict(0, "拟")) is LearningResult.Done)
        assertTrue(engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.AGAIN).candidates.isEmpty())
        engines.close()
    }

    @Test
    fun handwritingsPredictionTurnedOffStaysOffWhenItsWordIsWrittenAgain() {
        val engines = Engines(::load, MemoryFiles(), warmUps = inPlace)
        fun writeHao() {
            engines.write(0, "好")
        }
        writeHao()
        assertEquals(listOf("拟"), engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0).candidates)
        assertTrue(engines.learning(InputMethodCatalog.PINYIN, LearningOp.NoPredict(0, "拟")) is LearningResult.Done)
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
        writeHao()
        assertTrue(engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0).candidates.isEmpty())
        engines.close()
    }

    // 不再联想 taken back shows what may follow again, the session kept with its word before
    @Test
    fun handwritingsPredictionTurnedOffAndTakenBackIsOfferedAgain() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { engines ->
            engines.write(0, "好")
            assertEquals(listOf("拟"), engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0).candidates)
            val done = engines.learning(InputMethodCatalog.PINYIN, LearningOp.NoPredict(0, "拟")) as LearningResult.Done
            assertTrue(engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.AGAIN).candidates.isEmpty())
            assertEquals(LearningResult.Done(), engines.learning(InputMethodCatalog.PINYIN, LearningOp.Undo(done.undo)))
            assertEquals(listOf("拟"), engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.AGAIN).candidates)
        }
    }

    // an undo while characters are written keeps them, read anew, the pad's piece not ended
    @Test
    fun handwritingKeepsWhatIsWrittenThroughAnUndo() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { engines ->
            engines.write(0, "拟")
            assertEquals("拟", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0).commit)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertEquals("拟", engines.write(1, "拟").preedit)
            val ended = engines.inkEnded
            val done = engines.learning(InputMethodCatalog.PINYIN, LearningOp.Delete(0, "拟")) as LearningResult.Done
            assertEquals(LearningResult.Done(), engines.learning(InputMethodCatalog.PINYIN, LearningOp.Undo(done.undo)))
            val again = engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, Keyboard.AGAIN)
            assertEquals("拟", again.preedit)
            assertEquals(ended, engines.inkEnded)
        }
    }

    // a prediction blocked there is blocked for pinyin too, one forgotten no longer learned: the one lexicon
    @Test
    fun handwritingBlocksAndForgetsAPredictionInTheLexicon() {
        Engines(::load, MemoryFiles()).use { engines ->
            fun afterHao(): List<String> {
                engines.write(engines.inkEnded + 1, "好")
                return engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0).candidates
            }
            assertEquals(listOf("拟"), afterHao())
            assertEquals("拟", engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0).commit)
            afterHao()
            assertTrue(Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, 0))
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.FORGET, 0)
            assertFalse(Offer.FORGET in engines.offers(InputMethodCatalog.PINYIN, 0))
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.BLOCK, 0)
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertEquals(listOf("你"), engines.type(InputMethodCatalog.PINYIN, "ni").candidates)
        }
    }
}
