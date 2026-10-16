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

    private fun down(
        key: Key, t: Long, ctrl: Boolean = false, shift: Boolean = false, others: Boolean = false, repeat: Boolean = false,
        canToggle: Boolean = true,
    ) = s.down(key, ctrl, shift, others, repeat, t) { canToggle }

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
    fun oneHeldTheOtherTappedAgainGoesOnEachTime() {
        // Ctrl held, Shift tapped three times: three on, as on Windows
        down(Key.CTRL, 0)
        down(Key.SHIFT, 60, ctrl = true)
        assertEquals(Action.NEXT, s.up(Key.SHIFT, 120).action)
        down(Key.SHIFT, 500, ctrl = true)
        assertEquals(Action.NEXT, s.up(Key.SHIFT, 560).action)
        down(Key.SHIFT, 900, ctrl = true)
        assertEquals(Action.NEXT, s.up(Key.SHIFT, 960).action)
        assertEquals(none, s.up(Key.CTRL, 1000))
        // Shift held, Ctrl tapped again; Shift let go after is no lone Shift's tap
        down(Key.SHIFT, 2000)
        down(Key.CTRL, 2050, shift = true)
        assertEquals(Action.NEXT, s.up(Key.CTRL, 2100).action)
        down(Key.CTRL, 2400, shift = true)
        assertEquals(Action.NEXT, s.up(Key.CTRL, 2450).action)
        assertEquals(none, s.up(Key.SHIFT, 2470))
        // nothing held: a Shift tap is the toggle again
        down(Key.SHIFT, 3000)
        assertEquals(Action.TOGGLE, s.up(Key.SHIFT, 3050).action)
    }

    @Test
    fun oneSlowTapWithTheOtherHeldDoesNotEndTheCycle() {
        down(Key.CTRL, 0)
        down(Key.SHIFT, 60, ctrl = true)
        assertEquals(Action.NEXT, s.up(Key.SHIFT, 120).action)
        // a Shift held too long goes nowhere
        down(Key.SHIFT, 500, ctrl = true)
        assertEquals(none, s.up(Key.SHIFT, 500 + HardwareShortcuts.TAP_MS + 1))
        // Ctrl still held: the next tap goes on again
        down(Key.SHIFT, 1000, ctrl = true)
        assertEquals(Action.NEXT, s.up(Key.SHIFT, 1060).action)
        // and a first press of the two together held long is no NEXT, but the tap after it is
        s.up(Key.CTRL, 1100)
        down(Key.CTRL, 2000)
        down(Key.SHIFT, 2050, ctrl = true)
        assertEquals(none, s.up(Key.SHIFT, 2050 + HardwareShortcuts.TAP_MS + 1))
        down(Key.SHIFT, 3000, ctrl = true)
        assertEquals(Action.NEXT, s.up(Key.SHIFT, 3050).action)
    }

    @Test
    fun oneSlowCtrlTapWithShiftHeldDoesNotEndTheCycle() {
        down(Key.SHIFT, 0)
        down(Key.CTRL, 60, shift = true)
        assertEquals(Action.NEXT, s.up(Key.CTRL, 120).action)
        // a Ctrl held too long goes nowhere
        down(Key.CTRL, 500, shift = true)
        assertEquals(none, s.up(Key.CTRL, 500 + HardwareShortcuts.TAP_MS + 1))
        // Shift still held: the next tap goes on again
        down(Key.CTRL, 1000, shift = true)
        assertEquals(Action.NEXT, s.up(Key.CTRL, 1060).action)
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
    fun ctrlSpaceWithNowhereToGoIsTheApps() {
        // one input method, or no English: the app's, its completion say, or the system's
        down(Key.CTRL, 0)
        assertEquals(none, down(Key.SPACE, 50, ctrl = true, canToggle = false))
        assertEquals(none, down(Key.SPACE, 600, ctrl = true, repeat = true, canToggle = false))
        assertEquals(none, s.up(Key.SPACE, 700))
        assertEquals(none, s.up(Key.CTRL, 720))
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

    @Test
    fun aSpacePassedWhereTheShortcutsDoNotApplyLetsATakenOneGo() {
        down(Key.CTRL, 0)
        assertEquals(Action.TOGGLE, down(Key.SPACE, 50, ctrl = true).action)
        s.up(Key.CTRL, 100)
        // pressed in a terminal, or with the shortcuts off: its release goes on with it
        s.passed(Key.SPACE)
        assertEquals(none, s.up(Key.SPACE, 1050))
        // nor does a Shift tap go on across a key passed
        down(Key.SHIFT, 2000)
        s.passed(Key.OTHER)
        assertEquals(none, s.up(Key.SHIFT, 2050))
    }
}
