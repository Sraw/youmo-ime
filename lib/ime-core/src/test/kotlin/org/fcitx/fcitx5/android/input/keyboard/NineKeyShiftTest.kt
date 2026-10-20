/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NineKeyShiftTest {

    @Test
    fun pressedOnceTheNextLetterIsACapital() {
        val shift = NineKeyShift()
        shift.pressed(lock = false)
        assertTrue(shift.typed())
        assertFalse(shift.typed())
        shift.pressed(lock = false)
        shift.pressed(lock = false)
        assertFalse(shift.upper)
    }

    @Test
    fun lockedEveryLetterIsUntilPressedAgain() {
        val shift = NineKeyShift()
        // a double tap: the first press, then the lock
        shift.pressed(lock = false)
        shift.pressed(lock = true)
        assertEquals(AutoCaps.Caps.LOCK, shift.caps)
        assertTrue(shift.typed())
        assertTrue(shift.typed())
        shift.pressed(lock = true)
        assertFalse(shift.upper)
    }

    @Test
    fun aSentenceStartTurnsItOnAndOffButNotWhatTheUserDid() {
        val shift = NineKeyShift()
        assertTrue(shift.auto(wants = true))
        assertTrue(shift.upper)
        assertTrue(shift.auto(wants = false))
        assertFalse(shift.upper)
        shift.pressed(lock = false)
        assertFalse(shift.auto(wants = false))
        assertTrue(shift.upper)
        shift.reset()
        assertFalse(shift.upper)
    }
}
