/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.hardware

import org.fcitx.fcitx5.android.input.hardware.HardwareShortcuts.Action
import org.fcitx.fcitx5.android.input.hardware.HardwareShortcuts.Decision
import org.fcitx.fcitx5.android.input.hardware.HardwareShortcuts.Key
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwareShortcutsTest {

    private val s = HardwareShortcuts()
    private val none = Decision(null, consumed = false)

    private fun down(key: Key, t: Long, ctrl: Boolean = false, shift: Boolean = false, others: Boolean = false, repeat: Boolean = false) =
        s.down(key, ctrl, shift, others, repeat, t)

    @Test
    fun shiftTappedAloneSwitchesChineseAndEnglish() {
        assertEquals(none, down(Key.SHIFT, 0))
        // the key itself still goes on: the app and the engine see a Shift as ever
        assertEquals(Decision(Action.TOGGLE, consumed = false), s.up(Key.SHIFT, 120))
        // and again
        down(Key.SHIFT, 1000)
        assertEquals(Action.TOGGLE, s.up(Key.SHIFT, 1100).action)
    }

    @Test
    fun shiftForACapitalIsNoTap() {
        down(Key.SHIFT, 0)
        down(Key.OTHER, 50, shift = true)
        s.up(Key.OTHER, 80)
        assertEquals(none, s.up(Key.SHIFT, 120))
    }

    @Test
    fun shiftHeldLongIsNoTapThoughItRepeats() {
        down(Key.SHIFT, 0)
        down(Key.SHIFT, 400, shift = true, repeat = true)
        assertEquals(none, s.up(Key.SHIFT, HardwareShortcuts.TAP_MS + 1))
    }

    @Test
    fun shiftWithAltOrAfterCtrlAloneOnlyIsNoToggle() {
        down(Key.SHIFT, 0, others = true)
        assertEquals(none, s.up(Key.SHIFT, 50))
        // Ctrl+C, then Shift with Ctrl still held: not Ctrl+Shift
        down(Key.CTRL, 100)
        down(Key.OTHER, 120, ctrl = true)
        down(Key.SHIFT, 140, ctrl = true)
        assertEquals(none, s.up(Key.SHIFT, 160))
    }

    @Test
    fun ctrlAndShiftTogetherGoOnInEitherOrder() {
        down(Key.CTRL, 0)
        down(Key.SHIFT, 60, ctrl = true)
        assertEquals(Decision(Action.NEXT, consumed = false), s.up(Key.SHIFT, 120))
        // the other let go after: nothing more
        assertEquals(none, s.up(Key.CTRL, 150))

        down(Key.SHIFT, 1000)
        down(Key.CTRL, 1050, shift = true)
        assertEquals(Action.NEXT, s.up(Key.CTRL, 1100).action)
        assertEquals(none, s.up(Key.SHIFT, 1120))
    }

    @Test
    fun ctrlShiftWithAKeyIsTheAppsShortcut() {
        down(Key.CTRL, 0)
        down(Key.SHIFT, 30, ctrl = true)
        down(Key.OTHER, 60, ctrl = true, shift = true)
        s.up(Key.OTHER, 90)
        assertEquals(none, s.up(Key.SHIFT, 120))
    }

    @Test
    fun ctrlSpaceSwitchesAndIsKeptFromTheApp() {
        down(Key.CTRL, 0)
        assertEquals(Decision(Action.TOGGLE, consumed = true), down(Key.SPACE, 50, ctrl = true))
        assertEquals(Decision(null, consumed = true), down(Key.SPACE, 600, ctrl = true, repeat = true))
        assertEquals(Decision(null, consumed = true), s.up(Key.SPACE, 700))
        assertEquals(none, s.up(Key.CTRL, 720))
        // Space alone, or with Shift or Alt too, is the app's
        assertEquals(none, down(Key.SPACE, 1000))
        assertEquals(none, s.up(Key.SPACE, 1050))
        assertEquals(none, down(Key.SPACE, 2000, ctrl = true, shift = true))
        assertEquals(none, down(Key.SPACE, 3000, ctrl = true, others = true))
    }

    @Test
    fun ctrlTappedAloneDoesNothing() {
        down(Key.CTRL, 0)
        assertEquals(none, s.up(Key.CTRL, 50))
    }

    @Test
    fun aTakenSpaceWhoseReleaseWasLostDoesNotSwallowTheNext() {
        down(Key.CTRL, 0)
        assertEquals(Action.TOGGLE, down(Key.SPACE, 50, ctrl = true).action)
        // its release went elsewhere (focus moved); Ctrl let go
        s.up(Key.CTRL, 100)
        // an ordinary Space later: both its press and its release go on
        assertEquals(none, down(Key.SPACE, 1000))
        assertEquals(none, s.up(Key.SPACE, 1050))
    }
}
