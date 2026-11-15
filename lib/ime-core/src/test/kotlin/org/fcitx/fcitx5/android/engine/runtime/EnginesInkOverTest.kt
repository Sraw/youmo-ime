/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.SHUANGPIN
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.handwriting.HandwritingSession
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Writing on a Chinese input method's keys (键盘手写), as [Engines] serves it: the Ink key to that input method. */
class EnginesInkOverTest : EnginesFixture() {

    private fun Engines.write(im: String, piece: Int, vararg texts: String) =
        onEvent(im, EngineEvent.CHAR, Keyboard.INK, payload = Action.Ink(piece, InkReading(texts.toList(), DoubleArray(texts.size))))

    @Test
    fun whatIsWrittenOnPinyinsKeysShowsAndSpacePutsItIn() {
        val engines = Engines(::load, null, warmUps = inPlace)
        assertEquals("好", engines.write(PINYIN, 0, "好").preedit)
        assertEquals(HandwritingSession.NO_PIECE, engines.inkEnded)
        assertEquals("好", engines.onEvent(PINYIN, EngineEvent.CHAR, ' '.code).commit)
        assertEquals(0, engines.inkEnded)
        // pinyin types on after it
        assertEquals("ni", engines.type(PINYIN, "ni").preedit)
    }

    // the input ended (the field left, the cursor moved) puts it in; 清除 (a reset that drops) and Escape do not
    @Test
    fun aResetPutsWhatIsWrittenInAndADropOrEscapeDoesNot() {
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.write(PINYIN, 0, "好")
        assertEquals("好", engines.onEvent(PINYIN, EngineEvent.RESET, 0).commit)
        engines.write(PINYIN, 1, "好")
        val dropped = engines.onEvent(PINYIN, EngineEvent.RESET, EngineEvent.RESET_DROP)
        assertEquals("", dropped.commit)
        assertEquals("", dropped.preedit)
        engines.write(PINYIN, 2, "好")
        val escaped = engines.onEvent(PINYIN, EngineEvent.ESCAPE, 0)
        assertEquals("", escaped.commit)
        assertEquals("", escaped.preedit)
        // nothing left to put in when the input then ends
        assertEquals("", engines.onEvent(PINYIN, EngineEvent.RESET, 0).commit)
    }

    @Test
    fun aLetterAfterTheWritingPutsItInAndIsTyped() {
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.write(SHUANGPIN, 0, "好")
        val s = engines.onEvent(SHUANGPIN, EngineEvent.CHAR, 'n'.code)
        assertEquals("好", s.commit)
        assertTrue(s.preedit.isNotEmpty())
    }

    @Test
    fun aPieceHeldWithNoKeyIsShownWithTheNext() {
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.ink(Action.Ink(0, InkReading(listOf("好"), DoubleArray(1))), shown = false, im = PINYIN)
        val s = engines.onEvent(PINYIN, EngineEvent.CHAR, Keyboard.INK, payload = Action.Ink(HandwritingSession.NO_PIECE))
        assertEquals("好", s.preedit)
        // English is written on by none: nothing held, nothing typed there
        engines.ink(Action.Ink(1, InkReading(listOf("你"), DoubleArray(1))), shown = false, im = InputMethodCatalog.ENGLISH)
        // nor held for pinyin's writing: the next Ink with no piece shows what it showed, nothing added
        assertEquals("好", engines.onEvent(PINYIN, EngineEvent.CHAR, Keyboard.INK, payload = Action.Ink(HandwritingSession.NO_PIECE)).preedit)
    }

    @Test
    fun twoCharactersWrittenWithAPauseBetweenAreOneSentence() {
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.write(PINYIN, 0, "好")
        assertEquals("好拟", engines.write(PINYIN, 1, "拟").preedit)
        // a letter puts it in and is typed: pinyin's own now
        val s = engines.type(PINYIN, "n")
        assertEquals("n", s.preedit)
    }

    // 061-handwriting-toolbar §15: what pinyin has typed (from before handwriting was on) goes in as space puts it, then the stroke is written
    @Test
    @Uat("HW-46")
    fun aStrokeWhilePinyinIsTypedPutsItsFirstCandidateInThenIsWritten() {
        val engines = Engines(::load, null, warmUps = inPlace)
        val first = engines.type(PINYIN, "ni").candidates.first()
        val ink = Action.Ink(0, InkReading(listOf("人"), DoubleArray(1)))
        val s = engines.onEvent(PINYIN, EngineEvent.CHAR, Keyboard.INK, payload = ink)
        assertEquals(first, s.commit)
        assertEquals("人", s.preedit)
        engines.inkSent(ink, im = PINYIN)
        assertEquals("人", engines.onEvent(PINYIN, EngineEvent.CHAR, ' '.code).commit)
    }

    // 061-handwriting-toolbar §3.5: what offers 固定首位 is what shows, not whether handwriting was ever written
    @Test
    @Uat("HW-51")
    fun pinyinsOwnCandidatesOfferPinningAfterWritingHandwritingsDoNot() {
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.write(PINYIN, 0, "好", "拟")
        assertFalse(Offer.PIN in engines.offers(PINYIN, 1))
        engines.onEvent(PINYIN, EngineEvent.PICK, 0)
        engines.onEvent(PINYIN, EngineEvent.RESET, 0)
        engines.type(PINYIN, "ni")
        assertTrue(Offer.PIN in engines.offers(PINYIN, 1))
    }

    // typed on the nine keys and the 14 keys, deleted, then written
    @Test
    fun aKeyTypedAndDeletedOnTheNineAndThe14KeysThenWritten() {
        fun takenBack(im: String, key: Int) {
            val engines = Engines(::load, null, warmUps = inPlace)
            engines.onEvent(im, EngineEvent.CHAR, key)
            engines.onEvent(im, EngineEvent.BACKSPACE, 0)
            assertEquals(im, "主", engines.write(im, 0, "主").preedit)
        }
        takenBack(InputMethodCatalog.T9, '6'.code)
        takenBack(PINYIN, NineKeyLetters.G14.key('n').code)
    }

    // U-03: a sentence written over the keys and deleted as soon as it was put in is taken back, as on the pad
    @Test
    fun aSentenceDeletedAtOnceIsNotLearned() {
        fun learned(deleted: Boolean): Boolean = Engines(::load, MemoryFiles(), warmUps = inPlace).use { engines ->
            repeat(2) { n ->
                engines.onEvent(PINYIN, EngineEvent.RESET, 0)
                engines.context(PINYIN, "")
                engines.write(PINYIN, 2 * n, "好")
                engines.write(PINYIN, 2 * n + 1, "拟")
                assertEquals("好拟", engines.onEvent(PINYIN, EngineEvent.PICK, 0).commit)
                if (deleted) repeat(2) { engines.onEvent(PINYIN, EngineEvent.BACKSPACE, 0) }
            }
            engines.onEvent(PINYIN, EngineEvent.RESET, 0)
            engines.context(PINYIN, "")
            "好拟" in engines.userWords(UserWord.Kind.LEARNED).map { it.text }
        }
        assertTrue(learned(deleted = false))
        assertFalse(learned(deleted = true))
    }

    // pinyin typed after the sentence: backspaces then delete pinyin's text, not the sentence, which stays learned
    @Test
    fun aSentenceFollowedByPinyinStaysLearnedThroughItsBackspaces() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { engines ->
            repeat(2) { n ->
                engines.onEvent(PINYIN, EngineEvent.RESET, 0)
                engines.context(PINYIN, "")
                engines.write(PINYIN, 2 * n, "好")
                engines.write(PINYIN, 2 * n + 1, "拟")
                assertEquals("好拟", engines.onEvent(PINYIN, EngineEvent.PICK, 0).commit)
                val ma = engines.type(PINYIN, "ma")
                val put = engines.onEvent(PINYIN, EngineEvent.PICK, 0).commit
                assertTrue(ma.toString(), put.isNotEmpty())
                repeat(put.length) { engines.onEvent(PINYIN, EngineEvent.BACKSPACE, 0) }
            }
            engines.onEvent(PINYIN, EngineEvent.RESET, 0)
            engines.context(PINYIN, "")
            assertTrue("好拟" in engines.userWords(UserWord.Kind.LEARNED).map { it.text })
        }
    }

    // a word added drops the sessions: the piece ended stays ended, over the keys as on the pad
    @Test
    fun madeAgainKeepsWhatWasEnded() {
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.write(PINYIN, 0, "好")
        engines.write(PINYIN, 1, "你")
        assertTrue(engines.addWord("你好", "ni hao"))
        assertEquals(1, engines.inkEnded)
        assertEquals("", engines.write(PINYIN, 1, "好").preedit)
        assertFalse(engines.write(PINYIN, 2, "好").preedit.isEmpty())
    }

    @Test
    fun aGlideStillReadsUnderTheInk() {
        val engines = Engines(::load, null, warmUps = inPlace)
        engines.onEvent(PINYIN, EngineEvent.RESET, 0)
        // made, wrapped: still a glide's session under it, which reads the glide (English's would pass it on unread)
        val g = Glide(floatArrayOf(0f, 0f, 1f, 0f), GlideLayout.QWERTY)
        assertTrue(engines.glide(PINYIN, g)?.readings != null)
    }
}
