/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals(KeyStates(KeyState.Meta, KeyState.Super), states(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_META_LEFT, 0))
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

    /** A key fcitx hands back with no event left to replay is made up from its states: Android's meta state again. */
    @Test
    fun aModifierMadeUpFromItsStatesHasItsOwnBitAsAndroidHasIt() {
        val shift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        // a lone Shift: on in its press, off in its release, as it came
        assertEquals(shift, states(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, shift).metaStateOf(KeyEvent.KEYCODE_SHIFT_LEFT, up = false))
        assertEquals(0, states(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0).metaStateOf(KeyEvent.KEYCODE_SHIFT_LEFT, up = true))
        // Ctrl held throughout stays on
        assertEquals(ctrl, KeyStates(KeyState.Ctrl, KeyState.Shift).metaStateOf(KeyEvent.KEYCODE_SHIFT_LEFT, up = true))
        assertEquals(ctrl or shift, KeyStates(KeyState.Ctrl).metaStateOf(KeyEvent.KEYCODE_SHIFT_LEFT, up = false))
        // any other key's as its states have it
        assertEquals(ctrl, KeyStates(KeyState.Ctrl).metaStateOf(KeyEvent.KEYCODE_A, up = true))
        // no sides: right Shift's press has the left's flag, and left Shift's release with right
        // Shift still held has the bit off
        assertEquals(shift, KeyStates.Empty.metaStateOf(KeyEvent.KEYCODE_SHIFT_RIGHT, up = false))
        assertEquals(0, KeyStates(KeyState.Shift).metaStateOf(KeyEvent.KEYCODE_SHIFT_LEFT, up = true))
    }

    @Test
    fun metaIsSuperAsFcitxsHotkeysNameIt() { // F3809
        // Super+` opens quick input (QuickPhrase's trigger keys); fcitx's normalize drops Meta alone. The
        // space, not the `: Robolectric's key map gives every key its own char as its number, which the
        // symbol rule above strips (a device's generic.kcm types nothing for Meta+`, so it is not stripped)
        val meta = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        val space = KeyStates.fromKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0, meta))
        assertTrue(space.has(KeyState.Super))
        // as the editor gets it back: Meta, not more
        assertEquals(meta, space.metaState)
    }
}
