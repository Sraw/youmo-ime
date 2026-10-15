/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.Cursor
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.fcitx.fcitx5.android.engine.session.Action.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cursor in pinyin's input: a tap on the preedit or an arrow puts it, keys go in there. */
class PinyinSessionCursorTest {

    private val data = sessionTestData()

    private fun session() = PinyinSession(data, PinyinSegmenter())

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    @Test
    fun theCursorIsAtTheEndTillMoved() {
        val s = session().type("nihao")
        assertEquals("ni hao", s.preedit)
        assertEquals(-1, s.cursor)
    }

    @Test
    fun aKeyMissedIsTypedWhereTheTapPutTheCursor() {
        val session = session()
        val typed = session.type("nhao")
        // after the n
        val tapped = session.apply(Cursor(1))
        assertTrue(tapped.handled)
        assertEquals(typed.preedit, tapped.preedit)
        assertEquals(1, tapped.cursor)
        val fixed = session.apply(Key('i'))
        assertEquals("ni hao", fixed.preedit)
        assertEquals("你好", fixed.candidates.first())
        // after the i: before the space between the syllables
        assertEquals(2, fixed.cursor)
    }

    @Test
    fun backspaceDeletesBeforeTheCursor() {
        val session = session()
        session.type("nixhao")
        // after the x
        session.apply(Step(-3))
        val fixed = session.apply(Backspace)
        assertEquals("ni hao", fixed.preedit)
        assertEquals("你好", fixed.candidates.first())
        assertEquals(2, fixed.cursor)
        // the cursor at the start: nothing before it to delete, not even the app's
        session.apply(Step.START)
        val start = session.apply(Backspace)
        assertTrue(start.handled)
        assertEquals("ni hao", start.preedit)
        assertEquals(0, start.cursor)
    }

    @Test
    fun arrowsStopAtTheEnds() {
        val session = session()
        session.type("ni")
        assertEquals(-1, session.apply(Step(1)).cursor)
        assertEquals(0, session.apply(Step(-5)).cursor)
        assertEquals(-1, session.apply(Step.END).cursor)
    }

    @Test
    fun aTapInASpaceOrPastTheEndPutsTheCursorBetweenTheKeys() {
        val session = session()
        session.type("nihao")
        // ni |hao: after the space, the same place in the input as before it
        assertEquals(2, session.apply(Cursor(3)).cursor)
        assertEquals(-1, session.apply(Cursor(99)).cursor)
    }

    @Test
    fun aSeparatorTypedIsAKeyTheCursorPasses() {
        val session = session()
        assertEquals("xi'an", session.type("xi'an").preedit)
        // before the a
        assertEquals(3, session.apply(Step(-2)).cursor)
        // before the separator
        assertEquals(2, session.apply(Step(-1)).cursor)
        assertEquals("xian", session.apply(Step(1)).let { session.apply(Backspace) }.preedit)
    }

    @Test
    fun aPiecePickedIsKeptWhileTheKeysAfterItChange() {
        val session = session()
        session.type("nihaoo")
        assertEquals("你hao o", session.apply(Select(session.apply(Step.END).candidates.indexOf("你"))).preedit)
        val back = session.apply(Step(-1))
        assertEquals(4, back.cursor)
        val fixed = session.apply(Backspace)
        assertEquals("你hao", fixed.preedit)
        assertEquals("好", fixed.candidates.first())
        assertEquals(3, fixed.cursor)
    }

    @Test
    fun theCursorGoesNoFurtherBackThanThePiecesAndBackspaceThereDropsThem() {
        val session = session()
        val s = session.type("nihao")
        session.apply(Select(s.candidates.indexOf("你")))
        // a tap on 你: the cursor at the end of the pieces, after 你
        val tapped = session.apply(Cursor(0))
        assertEquals(1, tapped.cursor)
        // what is before it is 你's last key: 你 goes, and its keys are read again but that
        val back = session.apply(Backspace)
        assertEquals("n hao", back.preedit)
        assertEquals(1, back.cursor)
        assertEquals("ni hao", session.apply(Key('i')).preedit)
    }

    @Test
    fun keysInTheMiddleAreReadWithTheRestAsTheyWouldBeTyped() {
        val edited = session()
        edited.type("nihaoma")
        edited.apply(Step(-2))
        edited.apply(Backspace)
        edited.apply(Backspace)
        edited.apply(Backspace)
        val fixed = edited.type("zai")
        val typed = session().type("nizaima")
        assertEquals(typed.preedit, fixed.preedit)
        assertEquals(typed.candidates, fixed.candidates)
        assertEquals(typed.preedit.indexOf(" ma"), fixed.cursor)
    }

    @Test
    fun 双拼MovesAKeyAtATimeThroughWhatIsSpeltOut() {
        val session = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.XIAOHE), spell = true)
        assertEquals("ni hao", session.type("nihc").preedit)
        // between the syllables' keys
        assertEquals(2, session.apply(Step(-2)).cursor)
        assertEquals(0, session.apply(Step(-2)).cursor)
    }

    @Test
    fun 双拼SyllablesOfMoreLettersThanKeysPutNoPlaceAtTheirEnd() {
        val session = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.XIAOHE), spell = true)
        assertEquals("xian jian", session.type("xmjm").preedit)
        // between the syllables' keys: before the space, not before xian's n
        assertEquals(4, session.apply(Step(-2)).cursor)
        // a tap between its a and n: within xian's keys, after the first
        assertEquals(2, session.apply(Cursor(3)).cursor)
    }

    @Test
    fun aSeparatorLastIsAKeyNotShown() {
        val session = session()
        val shown = session.type("xi'").preedit
        assertEquals(1, session.apply(Cursor(1)).cursor)
        assertEquals(session().type("xai'").preedit, session.apply(Key('a')).preedit)
        // the a went between x and i, not after the separator
        assertEquals("xi", shown)
    }

    @Test
    fun aPiecePickedPastTheCursorTakesItToThePiecesEnd() {
        val session = session()
        val s = session.type("nihao")
        session.apply(Step.START)
        session.apply(Step(1))
        val picked = session.apply(Select(s.candidates.indexOf("你")))
        assertEquals("你hao", picked.preedit)
        assertEquals(1, picked.cursor)
    }

    @Test
    fun theHostsTapIsInCodePointsAndTheSessionsCursorInUtf16Units() {
        // a piece picked past the BMP before the code: 𠮷 is two UTF-16 units, one code point
        val taken = ArrayList<Int>()
        val session = object : Session {
            override var learning = true
            override fun reads(c: Char) = c in 'a'..'z'
            override fun candidates(from: Int, count: Int) = emptyList<Choice>()
            override fun offers(index: Int) = emptySet<Offer>()
            override fun apply(action: Action): Snapshot {
                if (action is Cursor) taken += action.at
                return Snapshot("", "\uD842\uDFB7ab", emptyList(), 0, false, false, true, false)
            }
        }
        val keyboard = Keyboard(session, nineKeys = false)
        keyboard.onEvent(EngineEvent.CHAR, 'a'.code)
        keyboard.onEvent(EngineEvent.CURSOR, 2)
        keyboard.onEvent(EngineEvent.CURSOR, 99)
        assertEquals(listOf(3, 4), taken)
    }

    private fun nineKeys() = PinyinSession(data, T9Segmenter(abbreviations = false), spell = true, prediction = false)

    @Test
    fun theNineKeysKeepACursorToo() {
        val session = nineKeys()
        session.type("6426")
        // after the 6
        session.apply(Step.START)
        assertEquals(1, session.apply(Step(1)).cursor)
        val fixed = session.apply(Key('4'))
        assertEquals("ni hao", fixed.preedit)
        assertEquals("你好", fixed.candidates.first())
        assertEquals(2, fixed.cursor)
        // a stray 6 after ni deleted
        session.apply(Step(1))
        session.apply(Key('6'))
        assertEquals("ni hao", session.apply(Backspace).preedit)
    }

    @Test
    fun aSyllableTakenOnTheNineKeysPutsTheCursorPastItIfItWasInItsDigits() {
        val session = nineKeys()
        session.type("64426")
        session.apply(Step.START)
        val taken = session.apply(Action.Syllable(0))
        assertEquals("ni'hao", taken.preedit)
        assertEquals(3, taken.cursor)
        // not at the end: backspace deletes the key before the cursor, the separator, giving nothing back
        val mid = session.apply(Backspace)
        assertEquals("ni hao", mid.preedit)
        assertEquals(2, mid.cursor)
    }

    @Test
    fun aPressAgainAfterTheCursorMovedIsAPressOfItsOwn() {
        val moved = nineKeys()
        moved.apply(Action.Letter('a'))
        moved.type("6")
        moved.apply(Step(-1))
        // a slice of refining meanwhile is no key
        moved.apply(Action.Refine)
        // after the A: again would have made it B, in its place
        val s = moved.apply(Action.Letter('b', again = true))
        val typed = nineKeys()
        typed.apply(Action.Letter('a'))
        typed.type("6")
        typed.apply(Step(-1))
        typed.apply(Step(0))
        assertEquals(typed.apply(Action.Letter('b')).preedit, s.preedit)
    }

    @Test
    fun aLetterTypedAsItselfOnTheNineKeysGoesInAtTheCursor() {
        val session = nineKeys()
        session.type("426")
        session.apply(Step.START)
        val typed = session.apply(Action.Letter('n'))
        // N426, the n read with the 4 as ni
        assertEquals("ni 26", typed.preedit)
        assertEquals(1, typed.cursor)
    }

    @Test
    fun nothingTypedTheTapAndTheArrowAreTheApps() {
        val session = session()
        assertFalse(session.apply(Cursor(0)).handled)
        assertFalse(session.apply(Step(-1)).handled)
        // nor over the words offered after a commit
        session.type("wo")
        assertTrue(session.apply(Select(0)).predicting)
        assertFalse(session.apply(Step(-1)).handled)
    }

    @Test
    fun arrowsHomeAndEndMoveTheCursorWhileSomethingIsTyped() {
        val keyboard = Keyboard(session(), nineKeys = false)
        "nhao".forEach { keyboard.onEvent(EngineEvent.CHAR, it.code) }
        assertEquals(0, keyboard.onEvent(EngineEvent.OTHER, HOME).cursor)
        assertEquals(1, keyboard.onEvent(EngineEvent.OTHER, RIGHT).cursor)
        val fixed = keyboard.onEvent(EngineEvent.CHAR, 'i'.code)
        assertEquals("ni hao", fixed.preedit)
        assertEquals(-1, keyboard.onEvent(EngineEvent.OTHER, END).cursor)
        assertEquals(5, keyboard.onEvent(EngineEvent.OTHER, LEFT).cursor)
        // another key no character: swallowed, the input as it was
        val tab = keyboard.onEvent(EngineEvent.OTHER, TAB)
        assertTrue(tab.handled)
        assertEquals(5, tab.cursor)
    }

    private companion object {
        const val HOME = 0xff50
        const val LEFT = 0xff51
        const val RIGHT = 0xff53
        const val END = 0xff57
        const val TAB = 0xff09
    }
}
