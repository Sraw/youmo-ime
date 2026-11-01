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
    private val list = listOf(InputMethodCatalog.PINYIN, en, InputMethodCatalog.HANDWRITING, InputMethodCatalog.WUBI)
    private val any: (String) -> Boolean = { true }
    private val keyboard: (String) -> Boolean = InputMethodCatalog::takesKeyboard

    @Test
    fun toggleGoesToEnglishAndBackToWhereItCameFrom() {
        assertEquals(en, InputMethodSwitch.toggle(InputMethodCatalog.WUBI, list, null, en, keyboard))
        assertEquals(InputMethodCatalog.WUBI, InputMethodSwitch.toggle(en, list, InputMethodCatalog.WUBI, en, keyboard))
        // from nowhere known: the list's first Chinese
        assertEquals(InputMethodCatalog.PINYIN, InputMethodSwitch.toggle(en, list, null, en, keyboard))
        // the one it came from since removed, or not for this keyboard
        assertEquals(InputMethodCatalog.PINYIN, InputMethodSwitch.toggle(en, list - InputMethodCatalog.WUBI, InputMethodCatalog.WUBI, en, keyboard))
        assertEquals(InputMethodCatalog.PINYIN, InputMethodSwitch.toggle(en, list, InputMethodCatalog.HANDWRITING, en, keyboard))
        assertEquals(InputMethodCatalog.HANDWRITING, InputMethodSwitch.toggle(en, list, InputMethodCatalog.HANDWRITING, en, any))
    }

    @Test
    fun toggleWithoutEnglishOrChineseGoesNowhere() {
        assertNull(InputMethodSwitch.toggle(InputMethodCatalog.PINYIN, list - en, null, en, any))
        assertNull(InputMethodSwitch.toggle(en, listOf(en), null, en, any))
    }

    @Test
    fun nextGoesRoundSkippingWhatTheKeyboardCannotType() {
        assertEquals(en, InputMethodSwitch.next(InputMethodCatalog.PINYIN, list, any))
        assertEquals(InputMethodCatalog.HANDWRITING, InputMethodSwitch.next(en, list, any))
        assertEquals(InputMethodCatalog.WUBI, InputMethodSwitch.next(en, list, keyboard))
        // round to the start
        assertEquals(InputMethodCatalog.PINYIN, InputMethodSwitch.next(InputMethodCatalog.WUBI, list, keyboard))
        // from one no longer in the list: the first
        assertEquals(InputMethodCatalog.PINYIN, InputMethodSwitch.next("gone", list, any))
        assertNull(InputMethodSwitch.next(InputMethodCatalog.PINYIN, listOf(InputMethodCatalog.PINYIN), any))
    }

    @Test
    fun nextFromOneNotInTheListReachesTheLastToo() {
        // fcitx fallen back to an English the user removed, or its input method not read yet
        assertEquals(InputMethodCatalog.PINYIN, InputMethodSwitch.next(en, listOf(InputMethodCatalog.PINYIN), any))
        assertEquals(InputMethodCatalog.PINYIN, InputMethodSwitch.next(en, listOf(InputMethodCatalog.HANDWRITING, InputMethodCatalog.PINYIN), keyboard))
    }

    @Test
    fun aToggleCanGoOnlyWithEnglishAndAChineseOneToGoTo() {
        assertTrue(InputMethodSwitch.canToggle(InputMethodCatalog.WUBI, list, en, keyboard))
        assertTrue(InputMethodSwitch.canToggle(en, list, en, keyboard))
        assertFalse(InputMethodSwitch.canToggle(InputMethodCatalog.PINYIN, listOf(InputMethodCatalog.PINYIN), en, any))
        assertFalse(InputMethodSwitch.canToggle(InputMethodCatalog.PINYIN, list - en, en, any))
        assertFalse(InputMethodSwitch.canToggle(en, listOf(en), en, any))
        // on the hardware keyboard, English and only the touch screen's
        assertFalse(InputMethodSwitch.canToggle(en, listOf(en, InputMethodCatalog.HANDWRITING), en, keyboard))
    }

    @Test
    fun aTouchInputMethodHandsAKeyboardToTheFirstThatTakesOne() {
        assertNull(InputMethodSwitch.forKeyboard(InputMethodCatalog.WUBI, list, en, keyboard))
        assertNull(InputMethodSwitch.forKeyboard(en, list, en, keyboard))
        assertEquals(InputMethodCatalog.PINYIN, InputMethodSwitch.forKeyboard(InputMethodCatalog.HANDWRITING, list, en, keyboard))
        assertEquals(InputMethodCatalog.WUBI, InputMethodSwitch.forKeyboard(InputMethodCatalog.HANDWRITING, listOf(InputMethodCatalog.HANDWRITING, en, InputMethodCatalog.WUBI), en, keyboard))
        // no Chinese one that does: English
        assertEquals(en, InputMethodSwitch.forKeyboard(InputMethodCatalog.HANDWRITING, listOf(InputMethodCatalog.HANDWRITING, en), en, keyboard))
        assertNull(InputMethodSwitch.forKeyboard(InputMethodCatalog.HANDWRITING, listOf(InputMethodCatalog.HANDWRITING), en, keyboard))
    }

    @Test
    fun aTouchInputMethodHandsAKeyboardToPinyinFirst() {
        // as Sogou and Baidu, pinyin though a table comes before it
        assertEquals(InputMethodCatalog.PINYIN, InputMethodSwitch.forKeyboard(InputMethodCatalog.HANDWRITING, listOf(InputMethodCatalog.HANDWRITING, InputMethodCatalog.WUBI, en, InputMethodCatalog.PINYIN), en, keyboard))
        // 全拼 or 双拼, whichever the list has first
        assertEquals(
            InputMethodCatalog.SHUANGPIN,
            InputMethodSwitch.forKeyboard(InputMethodCatalog.HANDWRITING, listOf(InputMethodCatalog.HANDWRITING, InputMethodCatalog.WUBI, InputMethodCatalog.SHUANGPIN, InputMethodCatalog.PINYIN), en, keyboard)
        )
    }

    @Test
    fun handwritingAloneTakesNoKeyboard() {
        // pinyin's nine keys a keyboard of pinyin, which a hardware keyboard types 全拼 on
        assertTrue(InputMethodCatalog.takesKeyboard(InputMethodCatalog.PINYIN))
        assertFalse(InputMethodCatalog.takesKeyboard(InputMethodCatalog.HANDWRITING))
        assertTrue(InputMethodCatalog.takesKeyboard(InputMethodCatalog.SHUANGPIN))
        assertTrue(InputMethodCatalog.takesKeyboard("engine-cangjie"))
    }
}
