/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.hardware

import org.fcitx.fcitx5.android.engine.host.Engines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputMethodSwitchTest {

    private val en = Engines.ENGLISH
    private val list = listOf(Engines.PINYIN, en, Engines.HANDWRITING, Engines.WUBI, Engines.T9)
    private val any: (String) -> Boolean = { true }
    private val keyboard: (String) -> Boolean = Engines::takesKeyboard

    @Test
    fun toggleGoesToEnglishAndBackToWhereItCameFrom() {
        assertEquals(en, InputMethodSwitch.toggle(Engines.WUBI, list, null, en, keyboard))
        assertEquals(Engines.WUBI, InputMethodSwitch.toggle(en, list, Engines.WUBI, en, keyboard))
        // from nowhere known: the list's first Chinese
        assertEquals(Engines.PINYIN, InputMethodSwitch.toggle(en, list, null, en, keyboard))
        // the one it came from since removed, or not for this keyboard
        assertEquals(Engines.PINYIN, InputMethodSwitch.toggle(en, list - Engines.WUBI, Engines.WUBI, en, keyboard))
        assertEquals(Engines.PINYIN, InputMethodSwitch.toggle(en, list, Engines.HANDWRITING, en, keyboard))
        assertEquals(Engines.HANDWRITING, InputMethodSwitch.toggle(en, list, Engines.HANDWRITING, en, any))
    }

    @Test
    fun toggleWithoutEnglishOrChineseGoesNowhere() {
        assertNull(InputMethodSwitch.toggle(Engines.PINYIN, list - en, null, en, any))
        assertNull(InputMethodSwitch.toggle(en, listOf(en), null, en, any))
    }

    @Test
    fun nextGoesRoundSkippingWhatTheKeyboardCannotType() {
        assertEquals(en, InputMethodSwitch.next(Engines.PINYIN, list, any))
        assertEquals(Engines.HANDWRITING, InputMethodSwitch.next(en, list, any))
        assertEquals(Engines.WUBI, InputMethodSwitch.next(en, list, keyboard))
        // round to the start, past the nine keys
        assertEquals(Engines.PINYIN, InputMethodSwitch.next(Engines.WUBI, list, keyboard))
        // from one no longer in the list: the first
        assertEquals(Engines.PINYIN, InputMethodSwitch.next("gone", list, any))
        assertNull(InputMethodSwitch.next(Engines.PINYIN, listOf(Engines.PINYIN), any))
    }

    @Test
    fun nextFromOneNotInTheListReachesTheLastToo() {
        // fcitx fallen back to an English the user removed, or its input method not read yet
        assertEquals(Engines.PINYIN, InputMethodSwitch.next(en, listOf(Engines.PINYIN), any))
        assertEquals(Engines.PINYIN, InputMethodSwitch.next(en, listOf(Engines.T9, Engines.HANDWRITING, Engines.PINYIN), keyboard))
    }

    @Test
    fun aToggleCanGoOnlyWithEnglishAndAChineseOneToGoTo() {
        assertTrue(InputMethodSwitch.canToggle(Engines.WUBI, list, en, keyboard))
        assertTrue(InputMethodSwitch.canToggle(en, list, en, keyboard))
        assertFalse(InputMethodSwitch.canToggle(Engines.PINYIN, listOf(Engines.PINYIN), en, any))
        assertFalse(InputMethodSwitch.canToggle(Engines.PINYIN, list - en, en, any))
        assertFalse(InputMethodSwitch.canToggle(en, listOf(en), en, any))
        // on the hardware keyboard, English and only the touch screen's
        assertFalse(InputMethodSwitch.canToggle(en, listOf(en, Engines.T9, Engines.HANDWRITING), en, keyboard))
    }

    @Test
    fun aTouchInputMethodHandsAKeyboardToTheFirstThatTakesOne() {
        assertNull(InputMethodSwitch.forKeyboard(Engines.WUBI, list, en, keyboard))
        assertNull(InputMethodSwitch.forKeyboard(en, list, en, keyboard))
        assertEquals(Engines.PINYIN, InputMethodSwitch.forKeyboard(Engines.HANDWRITING, list, en, keyboard))
        assertEquals(Engines.WUBI, InputMethodSwitch.forKeyboard(Engines.T9, listOf(Engines.T9, en, Engines.WUBI), en, keyboard))
        // no Chinese one that does: English
        assertEquals(en, InputMethodSwitch.forKeyboard(Engines.T9, listOf(Engines.T9, en), en, keyboard))
        assertNull(InputMethodSwitch.forKeyboard(Engines.T9, listOf(Engines.T9, Engines.HANDWRITING), en, keyboard))
    }

    @Test
    fun aTouchInputMethodHandsAKeyboardToPinyinFirst() {
        // as Sogou and Baidu, pinyin though a table comes before it
        assertEquals(Engines.PINYIN, InputMethodSwitch.forKeyboard(Engines.T9, listOf(Engines.T9, Engines.WUBI, en, Engines.PINYIN), en, keyboard))
        // 全拼 or 双拼, whichever the list has first
        assertEquals(
            Engines.SHUANGPIN,
            InputMethodSwitch.forKeyboard(Engines.HANDWRITING, listOf(Engines.HANDWRITING, Engines.WUBI, Engines.SHUANGPIN, Engines.PINYIN), en, keyboard)
        )
    }

    @Test
    fun theTouchScreensOwnTakeNoKeyboard() {
        assertFalse(Engines.takesKeyboard(Engines.T9))
        assertFalse(Engines.takesKeyboard(Engines.HANDWRITING))
        assertTrue(Engines.takesKeyboard(Engines.SHUANGPIN))
        assertTrue(Engines.takesKeyboard("engine-cangjie"))
    }
}
