/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.engine.LetterCase
import org.fcitx.fcitx5.android.scene.engine.LetterCaseRule
import org.fcitx.fcitx5.android.scene.engine.Shift
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LetterCaseRuleTest {
    @Test
    fun englishAlwaysFollowsShift() {
        LetterCase.entries.forEach { assertEquals(LetterCase.Shift, LetterCaseRule.forIme(it, english = true)) }
        LetterCase.entries.forEach { assertEquals(it, LetterCaseRule.forIme(it, english = false)) }
    }

    @Test
    fun alwaysLowerEvenWithShiftOn() {
        Shift.entries.forEach { s ->
            assertFalse(LetterCaseRule.upper(LetterCase.Lower, s))
            assertFalse(LetterCaseRule.upper(LetterCase.Lower, s, shiftless = true, keepUppercase = true))
        }
    }

    @Test
    fun alwaysUpperEvenWithShiftOff() {
        Shift.entries.forEach { s -> assertTrue(LetterCaseRule.upper(LetterCase.Upper, s)) }
    }

    @Test
    fun followingShift() {
        assertFalse(LetterCaseRule.upper(LetterCase.Shift, Shift.None))
        assertTrue(LetterCaseRule.upper(LetterCase.Shift, Shift.Once))
        assertTrue(LetterCaseRule.upper(LetterCase.Shift, Shift.Lock))
        // no Shift to follow (九键, 14/18 键 pinyin): capitals, as ever; the hidden keep-uppercase too
        assertTrue(LetterCaseRule.upper(LetterCase.Shift, Shift.None, shiftless = true))
        assertTrue(LetterCaseRule.upper(LetterCase.Shift, Shift.None, keepUppercase = true))
    }

    @Test
    fun shownKeepsSemicolon() {
        assertEquals("L;", LetterCaseRule.shown("l;", true))
        assertEquals("qw", LetterCaseRule.shown("QW", false))
    }
}
