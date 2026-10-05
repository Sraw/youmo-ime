/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubtypeManagerTest {

    private fun entry(uniqueName: String) =
        InputMethodEntry(uniqueName, uniqueName, "", "", "", "", "", false, InputMethodSubMode())

    @Test
    fun theKeyboardIsRegisteredLastAndTheRestKeepTheirOrder() {
        val enabled = arrayOf(entry("keyboard-us"), entry("engine-pinyin"), entry("engine-shuangpin"))
        assertEquals(
            listOf("engine-pinyin", "engine-shuangpin", "keyboard-us"),
            SubtypeManager.registrationOrder(enabled).map { it.uniqueName }
        )
    }

    @Test
    fun aSubtypeOfOursReadsAsItsInputMethod() {
        val subtype = InputMethodSubtypeBuilder().setSubtypeExtraValue("engine-pinyin").build()
        assertEquals("engine-pinyin", SubtypeManager.inputMethodOf(subtype))
    }

    @Test
    fun theSystemsSubtypeReadsAsNoInputMethodNotTheKeyboard() {
        assertNull(SubtypeManager.inputMethodOf(InputMethodSubtypeBuilder().build()))
    }
}
