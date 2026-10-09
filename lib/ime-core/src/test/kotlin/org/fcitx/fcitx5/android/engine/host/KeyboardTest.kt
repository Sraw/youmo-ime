/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardTest {

    /**
     * Reads lower-case letters; offers the input upper-cased, then the input with a `!`. Picking
     * the second reads only the first letter, leaving the rest typed. A pick then offers `next`.
     * Another key commits the first candidate. Two [Action.Refine] after a letter turn the
     * candidates round.
     */
    private class FakeSession(private val nineKeys: Boolean = false) : Session {
        val actions = ArrayList<Action>()
        val learned = ArrayList<Boolean>()
        private var input = ""
        private var predicting = false
        private var slices = 0
        private var refined = false

        override var learning = true

        override fun reads(c: Char) = c in 'a'..'z' || nineKeys && c in '2'..'9'

        override fun candidates(from: Int, count: Int) = all().drop(from).take(count).map { Choice(it) }
        override fun offers(index: Int) = emptySet<Offer>()

        private fun all() = when {
            predicting -> listOf("next")
            input.isEmpty() -> emptyList()
            else -> listOf(input.uppercase(), input.take(1) + "!").let { if (refined) it.reversed() else it }
        }

        override fun apply(action: Action): Snapshot {
            actions += action
            learned += learning
            if (action == Action.Refine) {
                if (--slices == 0) refined = true
            } else {
                slices = 0
                refined = false
            }
            var commit = ""
            var handled = true
            when (action) {
                is Action.Key -> {
                    predicting = false
                    if (reads(action.char)) {
                        input += action.char
                        slices = 2
                    } else {
                        commit = input.uppercase()
                        input = ""
                        handled = false
                    }
                }
                Action.Backspace -> if (input.isEmpty()) {
                    predicting = false
                    handled = false
                } else {
                    input = input.dropLast(1)
                }
                is Action.Select, is Action.Pick -> {
                    val index = if (action is Action.Select) action.index else (action as Action.Pick).index
                    val picked = all().getOrNull(index)
                    when {
                        picked == null -> handled = input.isNotEmpty()
                        predicting -> {
                            commit = picked
                            predicting = false
                        }
                        index == 1 -> {
                            commit = picked
                            input = input.drop(1)
                        }
                        else -> {
                            commit = picked
                            input = ""
                            predicting = true
                        }
                    }
                }
                Action.CommitRaw -> {
                    commit = input
                    input = ""
                }
                Action.Reset, is Action.Context -> {
                    input = ""
                    predicting = false
                }
                Action.NextPage, Action.PreviousPage, is Action.Forget, is Action.Pin, is Action.Unpin, is Action.Block, Action.Refine, is Action.Syllable -> {}
            }
            val shown = all()
            return Snapshot(commit, input, shown, 0, false, false, handled, predicting, refines = slices > 0)
        }
    }

    private val session = FakeSession()
    private val keyboard = Keyboard(session)

    private fun char(c: Char) = keyboard.onEvent(EngineEvent.CHAR, c.code)

    private fun type(text: String) = text.forEach { char(it) }

    @Test
    fun aSyllablePickComesThroughAsItsIndexAndList() {
        val nine = FakeSession(nineKeys = true)
        val keyboard = Keyboard(nine)
        for ((index, id) in listOf(0 to 0, 5 to 7, Action.Syllable.MAX - 1 to Action.Syllable.IDS - 1)) {
            keyboard.onEvent(EngineEvent.CHAR, Keyboard.syllableKey(index, id).code)
        }
        assertEquals(Keyboard.SYLLABLES.last, Keyboard.syllableKey(Action.Syllable.MAX - 1, Action.Syllable.IDS - 1))
        assertEquals(
            listOf(Action.Syllable(0, 0), Action.Syllable(5, 7), Action.Syllable(Action.Syllable.MAX - 1, Action.Syllable.IDS - 1)),
            nine.actions,
        )
    }

    @Test
    fun onlyOnTheNineKeysIsEscapeWithNothingTypedSwallowedOrACharacterASyllable() {
        // a table whose codes are digits reads 2 as the nine keys do
        val digits = FakeSession(nineKeys = true)
        val table = Keyboard(digits, nineKeys = false)
        assertFalse(table.onEvent(EngineEvent.ESCAPE, 0).handled)
        val c = Keyboard.syllableKey(0, 0)
        assertFalse(table.onEvent(EngineEvent.CHAR, c.code).handled)
        assertEquals(listOf<Action>(Action.Key(c)), digits.actions)
        assertTrue(Keyboard(FakeSession(), nineKeys = true).onEvent(EngineEvent.ESCAPE, 0).handled)
    }

    @Test
    fun lettersTheSessionReadsAreTyped() {
        type("ni")
        val s = char('h')
        assertTrue(s.handled)
        assertEquals("nih", s.preedit)
        assertEquals(listOf(Action.Key('n'), Action.Key('i'), Action.Key('h')), session.actions)
    }

    @Test
    fun whileTheUserPausesTheSessionRefinesTillItHasNoMoreToDo() {
        assertFalse(keyboard.onEvent(EngineEvent.REFINE, 0).handled)
        assertTrue(session.actions.isEmpty())
        type("ab")
        // a slice that changed nothing is not shown again
        val first = keyboard.onEvent(EngineEvent.REFINE, 0)
        assertFalse(first.handled)
        assertTrue(first.refines)
        val second = keyboard.onEvent(EngineEvent.REFINE, 0)
        assertTrue(second.handled)
        assertFalse(second.refines)
        assertEquals(listOf("a!", "AB"), second.candidates)
        session.actions.clear()
        assertFalse(keyboard.onEvent(EngineEvent.REFINE, 0).handled)
        assertTrue(session.actions.isEmpty())
        // what was committed with the last key is not committed again
        char('c')
        char(',')
        type("d")
        assertEquals("", keyboard.onEvent(EngineEvent.REFINE, 0).commit)
    }

    @Test
    fun withNothingTypedOtherKeysAreTheApps() {
        for (c in " 1,A") assertFalse(char(c).handled)
        // characters still reach the session, to end what it would run on from
        assertEquals(" 1,A".map { Action.Key(it) }, session.actions)
        session.actions.clear()
        assertFalse(keyboard.onEvent(EngineEvent.ENTER, 0).handled)
        assertFalse(keyboard.onEvent(EngineEvent.ESCAPE, 0).handled)
        assertFalse(keyboard.onEvent(EngineEvent.PAGE_DOWN, 0).handled)
        assertFalse(keyboard.onEvent(EngineEvent.OTHER, 0).handled)
        assertTrue(session.actions.isEmpty())
    }

    @Test
    fun anEmojiKeyEndsTheInputThoughNothingReadsIt() {
        type("ab")
        val s = keyboard.onEvent(EngineEvent.CHAR, 0x1F600)
        assertEquals("AB", s.commit)
        assertFalse(s.handled)
        assertEquals(Action.Key('\uFFFD'), session.actions.last())
    }

    @Test
    fun keysThatAreNoCharactersAreSwallowedWhileComposing() {
        type("ab")
        val s = keyboard.onEvent(EngineEvent.OTHER, 0)
        assertTrue(s.handled)
        assertEquals("ab", s.preedit)
        assertEquals("", s.commit)
        assertEquals(Action.Key('b'), session.actions.last())
    }

    @Test
    fun whetherToLearnReachesTheSessionWithEachEvent() {
        keyboard.onEvent(EngineEvent.CHAR, 'a'.code, learning = false)
        keyboard.onEvent(EngineEvent.CHAR, ' '.code, learning = false)
        keyboard.onEvent(EngineEvent.CHAR, 'b'.code)
        assertEquals(listOf(false, false, true), session.learned)
    }

    @Test
    fun spaceAndDigitsPick() {
        type("ab")
        assertEquals("AB", char(' ').commit)
        type("ab")
        assertEquals("a!", char('2').commit)
        assertEquals(Action.Select(1), session.actions.last())
        // 0 is the tenth
        type("c")
        char('0')
        assertEquals(Action.Select(9), session.actions.last())
    }

    @Test
    fun enterKeepsTheInputAsTypedAndEscapeDropsIt() {
        type("ab")
        val enter = keyboard.onEvent(EngineEvent.ENTER, 0)
        assertEquals("ab", enter.commit)
        assertTrue(enter.handled)
        type("ab")
        val escape = keyboard.onEvent(EngineEvent.ESCAPE, 0)
        assertEquals("", escape.commit)
        assertEquals("", escape.preedit)
        assertTrue(escape.handled)
    }

    @Test
    fun punctuationEndsTheInputInTheSessionThenGoesToTheApp() {
        type("ab")
        val s = char(',')
        assertEquals("AB", s.commit)
        assertFalse(s.handled)
        assertEquals(Action.Key(','), session.actions.last())
        assertFalse(s.predicting)
        assertTrue(s.candidates.isEmpty())
    }

    @Test
    fun aPredictionIsDroppedByAKeyTheSessionDoesNotRead() {
        type("ab")
        assertTrue(char(' ').predicting)
        // space after a word is a space
        val s = char(' ')
        assertFalse(s.handled)
        assertFalse(s.predicting)
        assertEquals(Action.Key(' '), session.actions.last())
    }

    @Test
    fun theTextBeforeTheCursorGoesToTheSessionOnlyWithNothingShown() {
        keyboard.context("你好")
        assertEquals(Action.Context("你好"), session.actions.last())
        type("ab")
        keyboard.context("x")
        assertEquals(Action.Key('b'), session.actions.last())
        char(' ')
        // a prediction shown
        keyboard.context("x")
        assertEquals(Action.Select(0), session.actions.last())
        keyboard.onEvent(EngineEvent.RESET, 0)
        keyboard.context("x")
        assertEquals(Action.Context("x"), session.actions.last())
        // set for it as for a key: it comes first in a field just focused
        keyboard.context("y", learning = false)
        assertFalse(session.learning)
        keyboard.context("z")
        assertTrue(session.learning)
    }

    @Test
    fun aPredictionIsDroppedByEnterAndEscapeToo() {
        type("ab")
        char(' ')
        assertFalse(keyboard.onEvent(EngineEvent.ENTER, 0).handled)
        assertEquals(Action.Reset, session.actions.last())
        type("ab")
        char(' ')
        // escape only closes the offer
        assertTrue(keyboard.onEvent(EngineEvent.ESCAPE, 0).handled)
    }

    @Test
    fun aLetterAfterAPredictionStartsNewInput() {
        type("ab")
        char(' ')
        val s = char('c')
        assertTrue(s.handled)
        assertEquals("c", s.preedit)
    }

    @Test
    fun pagesTurnOnlyWhileComposing() {
        type("ab")
        keyboard.onEvent(EngineEvent.PAGE_DOWN, 0)
        assertEquals(Action.NextPage, session.actions.last())
        keyboard.onEvent(EngineEvent.PAGE_UP, 0)
        assertEquals(Action.PreviousPage, session.actions.last())
    }

    @Test
    fun aPickIsByIndexAmongAll() {
        type("ab")
        assertEquals("a!", keyboard.onEvent(EngineEvent.PICK, 1).commit)
        assertEquals(Action.Pick(1), session.actions.last())
    }

    @Test
    fun aForgetIsByIndexAmongAll() {
        type("ab")
        val s = keyboard.onEvent(EngineEvent.FORGET, 1)
        assertEquals(Action.Forget(1), session.actions.last())
        assertEquals("ab", s.preedit)
        assertTrue(s.handled)
    }

    @Test
    fun aPinAnUnpinAndABlockAreByIndexAmongAll() {
        type("ab")
        keyboard.onEvent(EngineEvent.PIN, 1)
        assertEquals(Action.Pin(1), session.actions.last())
        keyboard.onEvent(EngineEvent.UNPIN, 0)
        assertEquals(Action.Unpin(0), session.actions.last())
        keyboard.onEvent(EngineEvent.BLOCK, 2)
        assertEquals(Action.Block(2), session.actions.last())
    }

    @Test
    fun backspaceAndResetGoToTheSession() {
        type("ab")
        assertEquals("a", keyboard.onEvent(EngineEvent.BACKSPACE, 0).preedit)
        keyboard.onEvent(EngineEvent.RESET, 0)
        assertEquals(Action.Reset, session.actions.last())
        assertFalse(keyboard.onEvent(EngineEvent.BACKSPACE, 0).handled)
        assertFalse(keyboard.onEvent(99, 0).handled)
    }
}
