/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every code a keyboard sends is internal, so the app never commits one handed back (code review S2). */
class KeyCodesTest {

    @Test
    fun everyKeyboardCodeIsInternal() {
        val codes = listOf(
            KeyCodes.GROUPED, KeyCodes.G18, KeyCodes.NINE_SHUANGPIN, Keyboard.TAPS, Keyboard.LETTERS,
            Keyboard.NINE_DIGITS, Keyboard.NINE_LETTERS, Keyboard.INK..Keyboard.AGAIN,
            Keyboard.SYLLABLES.first.code..Keyboard.SYLLABLES.last.code,
        )
        for (range in codes) for (c in range) assertTrue("U+%X".format(c), KeyCodes.isInternal(c))
        // every grouped keyboard's code, the 18 keys' and the nine keys' 双拼's too
        for (keys in listOf(NineKeyLetters.G14, NineKeyLetters.G18, NineKeyLetters.NINE_SP)) {
            for (c in keys.codes) assertTrue("U+%X".format(c.code), KeyCodes.isInternal(c.code))
        }
    }

    @Test
    fun textIsNot() {
        for (c in "an好⒈⒑①Ⓐ ".codePoints().toArray()) assertFalse("U+%X".format(c), KeyCodes.isInternal(c))
        assertFalse(KeyCodes.isInternal(0x249B))
        assertFalse(KeyCodes.isInternal(0x24B6))
        assertFalse(KeyCodes.isInternal("😀".codePointAt(0)))
    }
}
