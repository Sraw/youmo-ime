/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.hardware

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputMethodSwitchTest {

    private val en = InputMethodCatalog.ENGLISH
    private val list = listOf(InputMethodCatalog.PINYIN, en, InputMethodCatalog.SHUANGPIN, InputMethodCatalog.WUBI)

    @Test
    fun toggleGoesToEnglishAndBackToWhereItCameFrom() {
        assertEquals(en, InputMethodSwitch.toggle(InputMethodCatalog.WUBI, list, null, en))
        assertEquals(InputMethodCatalog.WUBI, InputMethodSwitch.toggle(en, list, InputMethodCatalog.WUBI, en))
        // from nowhere known: the list's first Chinese
        assertEquals(InputMethodCatalog.PINYIN, InputMethodSwitch.toggle(en, list, null, en))
        // the one it came from since removed
        assertEquals(InputMethodCatalog.PINYIN, InputMethodSwitch.toggle(en, list - InputMethodCatalog.WUBI, InputMethodCatalog.WUBI, en))
    }

    @Test
    fun toggleWithoutEnglishOrChineseGoesNowhere() {
        assertNull(InputMethodSwitch.toggle(InputMethodCatalog.PINYIN, list - en, null, en))
        assertNull(InputMethodSwitch.toggle(en, listOf(en), null, en))
    }

    @Test
    fun nextGoesRound() {
        assertEquals(en, InputMethodSwitch.next(InputMethodCatalog.PINYIN, list))
        assertEquals(InputMethodCatalog.SHUANGPIN, InputMethodSwitch.next(en, list))
        // round to the start
        assertEquals(InputMethodCatalog.PINYIN, InputMethodSwitch.next(InputMethodCatalog.WUBI, list))
        // from one no longer in the list: the first
        assertEquals(InputMethodCatalog.PINYIN, InputMethodSwitch.next("gone", list))
        assertNull(InputMethodSwitch.next(InputMethodCatalog.PINYIN, listOf(InputMethodCatalog.PINYIN)))
    }

    @Test
    fun nextFromOneNotInTheListReachesTheLastToo() {
        // fcitx fallen back to an English the user removed, or its input method not read yet
        assertEquals(InputMethodCatalog.PINYIN, InputMethodSwitch.next(en, listOf(InputMethodCatalog.PINYIN)))
        assertEquals(InputMethodCatalog.WUBI, InputMethodSwitch.next(en, listOf(InputMethodCatalog.WUBI, InputMethodCatalog.PINYIN)))
    }

    @Test
    fun aToggleCanGoOnlyWithEnglishAndAChineseOneToGoTo() {
        assertTrue(InputMethodSwitch.canToggle(InputMethodCatalog.WUBI, list, en))
        assertTrue(InputMethodSwitch.canToggle(en, list, en))
        assertFalse(InputMethodSwitch.canToggle(InputMethodCatalog.PINYIN, listOf(InputMethodCatalog.PINYIN), en))
        assertFalse(InputMethodSwitch.canToggle(InputMethodCatalog.PINYIN, list - en, en))
        assertFalse(InputMethodSwitch.canToggle(en, listOf(en), en))
    }
}
