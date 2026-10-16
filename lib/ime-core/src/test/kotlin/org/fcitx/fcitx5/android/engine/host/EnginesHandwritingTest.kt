/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.handwriting.HandwritingSession
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
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
