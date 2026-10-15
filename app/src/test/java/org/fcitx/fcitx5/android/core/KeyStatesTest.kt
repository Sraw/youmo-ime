/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A hardware key's state as fcitx reads it: X11's, as it was before the key. */
@RunWith(RobolectricTestRunner::class)
class KeyStatesTest {

    private fun states(action: Int, code: Int, meta: Int) =
        KeyStates.fromKeyEvent(KeyEvent(0, 0, action, code, 0, meta))

    @Test
    fun aModifierGoesDownWithoutItselfAndComesUpWithIt() {
        // as Android sends a lone Shift: on in its own press, off in its release
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        assertEquals(KeyStates.Empty, states(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, shift))
        assertEquals(KeyStates(KeyState.Shift), states(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0))
        assertEquals(KeyStates(KeyState.Shift), states(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_RIGHT, 0))
        assertEquals(KeyStates.Empty, states(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.META_CTRL_ON))
        assertEquals(KeyStates(KeyState.Alt), states(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, 0))
        assertEquals(KeyStates(KeyState.Meta), states(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_META_LEFT, 0))
    }

    @Test
    fun anotherModifierHeldStaysInTheState() {
        // Ctrl held, Shift pressed and let go: Ctrl throughout
        val ctrl = KeyEvent.META_CTRL_ON
        assertEquals(KeyStates(KeyState.Ctrl), states(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, ctrl or KeyEvent.META_SHIFT_ON))
        assertEquals(KeyStates(KeyState.Ctrl, KeyState.Shift), states(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, ctrl))
    }

    @Test
    fun otherKeysKeepTheirState() {
        assertEquals(KeyStates(KeyState.Ctrl), states(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON))
        assertEquals(KeyStates(KeyState.Ctrl), states(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON))
    }

    @Test
    fun theOtherSideOfAPairHeldKeepsItsBit() {
        // right Shift held, left Shift pressed: Shift was already on before the key
        val both = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_RIGHT_ON
        assertEquals(KeyStates(KeyState.Shift), states(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, both))
        val ctrls = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_RIGHT_ON
        assertEquals(KeyStates(KeyState.Ctrl), states(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_RIGHT, ctrls))
    }
}
