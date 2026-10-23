/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.editing.ForwardedKeys.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardedKeysTest {

    private val keycodeA = 29 // KeyEvent.KEYCODE_A
    private val shiftOn = 0x1 or 0x40 // META_SHIFT_ON | META_SHIFT_LEFT_ON
    private val altOn = 0x2 or 0x10 // META_ALT_ON | META_ALT_LEFT_ON
    private val capsLockOn = 0x100000 // META_CAPS_LOCK_ON

    @Test
    fun aForwardedKeyIsSentTypedOrIgnored() = assertRows(
        "a key with a key code is sent both ways" to {
            assertEquals(Action.SendKey(keycodeA, up = false), ForwardedKeys.decide(keycodeA, up = false, unicode = 'a'.code))
            assertEquals(Action.SendKey(keycodeA, up = true), ForwardedKeys.decide(keycodeA, up = true, unicode = 'a'.code))
        },
        "a key with no key code is typed once on key down" to {
            assertEquals(Action.CommitChar("é"), ForwardedKeys.decide(ForwardedKeys.KEYCODE_UNKNOWN, up = false, unicode = 'é'.code))
            assertEquals(Action.Ignore, ForwardedKeys.decide(ForwardedKeys.KEYCODE_UNKNOWN, up = true, unicode = 'é'.code))
        },
        "a key with neither is ignored" to {
            assertEquals(Action.Ignore, ForwardedKeys.decide(ForwardedKeys.KEYCODE_UNKNOWN, up = false, unicode = 0))
        },
        "a character beyond the basic plane is typed whole" to {
            assertEquals(Action.CommitChar("👋"), ForwardedKeys.decide(ForwardedKeys.KEYCODE_UNKNOWN, false, 0x1F44B))
        },
    )

    @Test
    fun onlyThePickerCharacterOpensThePicker() {
        assertTrue(ForwardedKeys.opensCharacterPicker(0xEF01))
        assertFalse(ForwardedKeys.opensCharacterPicker('a'.code))
    }

    @Test
    fun releasingAModifierClearsWhatItHeld() {
        val meta = StickyMetaState()
        meta.onModifierDown(shiftOn)
        assertEquals(shiftOn, meta.onModifierUp(0))
    }

    @Test
    fun releasingOneOfTwoModifiersKeepsTheOther() {
        val meta = StickyMetaState()
        meta.onModifierDown(shiftOn)
        meta.onModifierDown(shiftOn or altOn)
        assertEquals("only alt went", altOn, meta.onModifierUp(shiftOn))
        assertEquals(shiftOn, meta.onModifierUp(0))
    }

    /** A sticky modifier stays latched after its key is released: nothing to clear yet. */
    @Test
    fun aLatchedModifierIsNotCleared() {
        val meta = StickyMetaState()
        meta.onModifierDown(shiftOn)
        assertEquals(0, meta.onModifierUp(shiftOn))
    }

    /** A release that turns a bit on (a lock, say) leaves it: only what the release dropped is cleared. */
    @Test
    fun aBitTheReleaseTurnedOnIsNotCleared() {
        val meta = StickyMetaState()
        meta.onModifierDown(shiftOn)
        assertEquals(shiftOn, meta.onModifierUp(capsLockOn))
    }
}
