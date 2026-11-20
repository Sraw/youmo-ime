/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The full keyboard's Shift is 分词 while pinyin or 双拼 is typed, Shift otherwise. */
class ShiftSeparatorTest {

    @Test
    fun pinyinAndShuangpinWhileTyped() {
        for (im in listOf(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN)) {
            assertTrue(im, ShiftSeparator.on(im, im, composing = true))
            assertFalse(im, ShiftSeparator.on(im, im, composing = false))
        }
        assertEquals("'", ShiftSeparator.TYPED)
    }

    @Test
    fun neverEnglishATableNorZhuyin() {
        val others = listOf(InputMethodCatalog.ENGLISH, InputMethodCatalog.WUBI, InputMethodCatalog.ZHUYIN)
        for (im in others) assertFalse(im, ShiftSeparator.on(im, im, composing = true))
    }

    // the answer another input method's: none of the one shown typed (a switch's old one's last word)
    @Test
    fun anotherInputMethodsAnswerIsNotTyping() {
        assertFalse(ShiftSeparator.on(InputMethodCatalog.PINYIN, InputMethodCatalog.SHUANGPIN, composing = true))
        assertFalse(ShiftSeparator.on(InputMethodCatalog.SHUANGPIN, InputMethodCatalog.PINYIN, composing = true))
        assertFalse(ShiftSeparator.on(InputMethodCatalog.PINYIN, "", composing = true))
    }
}
