/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holding ⌫ while typing pinyin used to delete the pinyin and run on into the field's text: a hold
 * begun with something typed stops once that is gone.
 */
class BackspaceHoldTest {

    // each repeat's turn, as the host saw things then; whether it deleted
    private fun BackspaceHold.turns(vararg composing: Boolean) = composing.map(::repeat)

    @Test
    fun aHoldBegunTypingDeletesWhatIsTypedThenStops() {
        // "ni": two repeats take it, the third finds nothing typed and deletes nothing of the field's
        val hold = BackspaceHold()
        assertEquals(listOf(true, true, false), hold.turns(true, true, false))
        assertTrue(hold.stopped)
    }

    @Test
    fun aStoppedHoldStaysStoppedThoughSomethingIsTypedAgain() {
        // another finger types while ⌫ is held: the hold does not come back to life
        val hold = BackspaceHold()
        hold.turns(true, false)
        assertEquals(listOf(false, false), hold.turns(true, false))
    }

    @Test
    fun aHoldBegunWithNothingTypedDeletesOn() {
        val hold = BackspaceHold()
        assertEquals(listOf(true, true, true), hold.turns(false, false, false))
        assertFalse(hold.stopped)
    }

    @Test
    fun aHoldBegunWithNothingTypedIsNotStoppedBySomethingTypedMeanwhile() {
        // only what was typed as it began counts: a letter typed by another finger is deleted and the hold goes on
        val hold = BackspaceHold()
        assertEquals(listOf(true, true, true), hold.turns(false, true, false))
    }

    @Test
    fun aRepeatTheKeyboardTookCountsAsSomethingTyped() {
        // strokes written on the keys taken back first: once they are gone and the engine has nothing, the hold stops
        val hold = BackspaceHold()
        hold.taken()
        assertEquals(listOf(false), hold.turns(false))
        assertTrue(hold.stopped)
    }

    @Test
    fun aRepeatTakenAfterTheFirstTurnChangesNothing() {
        val hold = BackspaceHold()
        hold.repeat(false)
        hold.taken()
        assertEquals(listOf(true), hold.turns(false))
    }

    @Test
    fun itsPressTellsWhatWasTypedNotItsFirstRepeat() {
        // pinyin typed as ⌫ went down, committed by another finger before the first repeat: nothing of the field's deleted
        val hold = BackspaceHold()
        hold.press(true)
        assertEquals(listOf(false), hold.turns(false))
        // nothing typed as it went down, a letter typed meanwhile: deleted, and the hold goes on
        val other = BackspaceHold()
        other.press(false)
        assertEquals(listOf(true, true), other.turns(true, false))
    }

    @Test
    fun strokesTakenBackBeforeThePresssTurnCountAsSomethingTyped() {
        // the keyboard tells first, on the main thread: the press's turn, the host seeing nothing typed, changes nothing
        val hold = BackspaceHold()
        hold.taken()
        hold.press(false)
        assertEquals(listOf(false), hold.turns(false))
    }

    @Test
    fun aNewHoldStartsAfresh() {
        // let go and pressed again: the field's text is deleted as usual
        BackspaceHold().turns(true, false)
        assertEquals(listOf(true, true), BackspaceHold().turns(false, false))
    }
}
