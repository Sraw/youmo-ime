/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.SHUANGPIN
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.handwriting.HandwritingSession
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.session.Action
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
        // sent and taken: not held again
        engines.inkSent(Action.Ink(HandwritingSession.NO_PIECE), im = PINYIN)
        // English is written on by none
        engines.ink(Action.Ink(1), shown = false, im = InputMethodCatalog.ENGLISH)
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
        // made, wrapped: still a glide's session under it
        val g = Glide(floatArrayOf(0f, 0f, 1f, 0f), GlideLayout.QWERTY)
        assertTrue(engines.glide(PINYIN, g) != null)
    }
}
