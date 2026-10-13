/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A code with one candidate commits it itself (五笔's 四码唯一), and what follows such a code. */
class TableLoneCodeTest {

    private val wubi = dictionary("a 工", "a 戈", "sie 梢", "sieg 梢", "w 人", "wqiy 你", "wun 们", "wqvb 你好")

    // a clock that stands still unless a test moves it: a space comes as soon as the test types it
    private fun session(now: () -> Long = { 0L }) = TableSession(wubi, TableOptions.WUBI, now = now)

    @Test
    fun aLoneCandidateOfTheWholeCodeCommitsItself() {
        val t = session()
        // wqi leads only to 你, whose code is longer: nothing yet
        assertEquals("", t.type("wqi").first)
        val (commit, s) = t.type("y")
        assertEquals("你", commit)
        assertEquals("", s.preedit)
        assertEquals(emptyList<String>(), s.candidates)
        // at any length, not only a full code
        assertEquals("们", session().type("wun").first)
        // and alone once each text shows once: sie and sieg are both 梢
        assertEquals("梢", session().type("sie").first)
    }

    /** 五笔 users type a space after each code: after one that committed itself it made a gap. */
    @Test
    fun aSpaceRightAfterACodeCommittedItselfIsTakenOnce() {
        val t = session()
        assertEquals("你", t.type("wqiy").first)
        assertTrue(t.reads(' '))
        val taken = t.apply(Key(' '))
        assertTrue(taken.handled)
        assertEquals("", taken.commit)
        // only that one: a second space is the app's
        assertFalse(t.reads(' '))
        // nor after anything else: a code committed by space, then another space
        val u = session()
        u.type("a")
        assertEquals("工", u.apply(Select(0)).commit)
        assertFalse(u.reads(' '))
        // a key between: the space after it is the app's
        val v = session()
        v.type("wqiy")
        v.apply(Backspace)
        assertFalse(v.reads(' '))
    }

    /** A pause, to read or scroll, then a space: that one was meant, not the one that commits the code. */
    @Test
    fun theSpaceIsTakenOnlyWithinASecondOfTheCode() {
        var time = 0L
        val t = session { time }
        assertEquals("你", t.type("wqiy").first)
        time = 1000
        assertTrue(t.reads(' '))
        assertTrue(t.apply(Key(' ')).handled)
        // past the second: typed
        time = 0
        val u = session { time }
        u.type("wqiy")
        time = 1001
        assertFalse(u.reads(' '))
        assertFalse(u.apply(Key(' ')).handled)
    }

    @Test
    fun throughTheKeyboardTheSpaceAfterALoneCodeIsNotTyped() {
        val keyboard = Keyboard(session(), nineKeys = false)
        "wqiy".forEach { keyboard.onEvent(EngineEvent.CHAR, it.code) }
        val space = keyboard.onEvent(EngineEvent.CHAR, ' '.code)
        assertTrue(space.handled)
        assertEquals("", space.commit)
        assertFalse(keyboard.onEvent(EngineEvent.CHAR, ' '.code).handled)
    }

    @Test
    fun anEnterBetweenLetsTheSpaceThrough() {
        val keyboard = Keyboard(session(), nineKeys = false)
        "wqiy".forEach { keyboard.onEvent(EngineEvent.CHAR, it.code) }
        // nothing typed: Enter is the app's, a new line
        assertFalse(keyboard.onEvent(EngineEvent.ENTER, 0).handled)
        assertFalse(keyboard.onEvent(EngineEvent.CHAR, ' '.code).handled)
    }

    @Test
    fun aTapInTheAppsTextBetweenLetsTheSpaceThrough() {
        val keyboard = Keyboard(session(), nineKeys = false)
        "wqiy".forEach { keyboard.onEvent(EngineEvent.CHAR, it.code) }
        // nothing typed: the tap moves the app's cursor, and the space goes where it now is
        assertFalse(keyboard.onEvent(EngineEvent.CURSOR, 0).handled)
        assertFalse(keyboard.onEvent(EngineEvent.CHAR, ' '.code).handled)
    }
}
