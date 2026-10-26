/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.host.KeyStates.ALT
import org.fcitx.fcitx5.android.host.KeyStates.CAPS_LOCK
import org.fcitx.fcitx5.android.host.KeyStates.CTRL
import org.fcitx.fcitx5.android.host.KeyStates.NUM_LOCK
import org.fcitx.fcitx5.android.host.KeyStates.SHIFT
import org.fcitx.fcitx5.android.host.KeyStates.SUPER
import org.fcitx.fcitx5.android.host.KeyStates.SUPER2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `Key::normalize`, one case per branch; lines are `fcitx5/lib/fcitx-utils/key.cpp`. */
class KeyNormalizeTest {

    private fun norm(sym: Int, states: Int = 0) = Key(sym, states).normalize()

    @Test
    fun `normalize, one row per branch`() = assertRows(
        "shift with a letter drops the shift and keeps the sym (key.cpp:517-520)" to {
            assertEquals(Key('a'.code), norm('a'.code, SHIFT))
            assertEquals(Key('A'.code), norm('A'.code, SHIFT))
        },
        "another modifier makes a lowercase letter uppercase (key.cpp:509-511)" to {
            assertEquals(Key('A'.code, CTRL), norm('a'.code, CTRL))
        },
        "not Shift alone, so the Shift stays as well (:517-520 only clears Shift alone)" to {
            assertEquals(Key('A'.code, CTRL or SHIFT), norm('a'.code, CTRL or SHIFT))
        },
        "shift with a printable symbol or a keypad digit drops the shift (key.cpp:521-529)" to {
            assertEquals(Key('1'.code), norm('1'.code, SHIFT))
            assertEquals(Key('!'.code), norm('!'.code, SHIFT))
            assertEquals(Key('!'.code, ALT), norm('!'.code, ALT or SHIFT))
            assertEquals(Key(KeySyms.KP_0 + 1), norm(KeySyms.KP_0 + 1, SHIFT))
        },
        "a non-ASCII character with a keysym of its own (keydata.h): Shift goes too" to {
            assertEquals(Key(0x01a1), norm(0x01a1, SHIFT))
        },
        "shift stays with space, return and tab (key.cpp:524-525)" to {
            assertEquals(Key(KeySyms.SPACE, SHIFT), norm(KeySyms.SPACE, SHIFT))
            assertEquals(Key(KeySyms.RETURN, SHIFT), norm(KeySyms.RETURN, SHIFT))
            assertEquals(Key(KeySyms.TAB, SHIFT), norm(KeySyms.TAB, SHIFT))
        },
        "a key that types nothing keeps it too: Shift+Left" to {
            assertEquals(Key(0xff51, SHIFT), norm(0xff51, SHIFT))
        },
        "caps lock and num lock are masked off (key.cpp:501-503)" to {
            assertEquals(Key('a'.code), norm('a'.code, CAPS_LOCK))
            assertEquals(Key('A'.code), norm('A'.code, CAPS_LOCK or SHIFT))
            assertEquals(Key(KeySyms.KP_0), norm(KeySyms.KP_0, NUM_LOCK))
            assertEquals(Key('A'.code, CTRL), norm('a'.code, CTRL or CAPS_LOCK or KeyStates.VIRTUAL))
        },
        "iso left tab is tab (key.cpp:496-498)" to {
            assertEquals(Key(KeySyms.TAB), norm(KeySyms.ISO_LEFT_TAB))
            assertEquals(Key(KeySyms.TAB, SHIFT), norm(KeySyms.ISO_LEFT_TAB, SHIFT))
        },
        "super2 is super (key.cpp:504-507)" to {
            assertEquals(Key('A'.code, SUPER), norm('a'.code, SUPER2))
        },
        "no modifier leaves the key alone" to {
            assertEquals(Key('a'.code), norm('a'.code))
            assertEquals(Key(KeySyms.BACKSPACE), norm(KeySyms.BACKSPACE))
        },
    )

    @Test
    fun `key predicates`() {
        // key.cpp:446-458,489
        assertTrue(Key('a'.code).isLAZ)
        assertFalse(Key('a'.code, SHIFT).isLAZ)
        assertTrue(Key('Z'.code).isUAZ)
        assertTrue(Key('5'.code).isDigit)
        assertTrue(Key('~'.code).isSimple)
        assertFalse(Key(KeySyms.BACKSPACE).isSimple)
        assertTrue(Key('a'.code, CTRL).hasModifier)
        assertFalse(Key('a'.code, CAPS_LOCK).hasModifier)
    }

    @Test
    fun `keysym to unicode`() {
        // key.cpp:738-800, in the order of its checks
        assertEquals('a'.code, KeySyms.toUnicode('a'.code))
        assertEquals(0xe9, KeySyms.toUnicode(0xe9))
        assertEquals(' '.code, KeySyms.toUnicode(KeySyms.KP_SPACE))
        assertEquals(8, KeySyms.toUnicode(KeySyms.BACKSPACE))
        assertEquals(13, KeySyms.toUnicode(KeySyms.KP_ENTER))
        assertEquals('5'.code, KeySyms.toUnicode(KeySyms.KP_0 + 5))
        assertEquals(0x7f, KeySyms.toUnicode(KeySyms.DELETE))
        assertEquals(0, KeySyms.toUnicode(0x0100d800))
        assertEquals(0x4e2d, KeySyms.toUnicode(KeySyms.UNICODE_OFFSET + 0x4e2d))
        assertEquals(0x1f600, KeySyms.toUnicode(KeySyms.UNICODE_OFFSET + 0x1f600))
        // keydata.h: first, last and a middle entry of keysym_to_unicode_tab
        assertEquals(0x0104, KeySyms.toUnicode(0x01a1))
        assertEquals(0x20ac, KeySyms.toUnicode(0x20ac))
        assertEquals(0x3002, KeySyms.toUnicode(0x04a1))
        assertEquals(0, KeySyms.toUnicode(0xff51))
        assertEquals(0, KeySyms.toUnicode(0x0019))
    }
}
