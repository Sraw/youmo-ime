/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.handwriting

import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingToggleTest {

    @Test
    fun aTapTurnsItOffOrOnAsLastUsed() {
        assertEquals(ReadingStyle.OFF, ReadingToggle.tapped(ReadingStyle.PINYIN, "Zhuyin"))
        assertEquals(ReadingStyle.OFF, ReadingToggle.tapped(ReadingStyle.ZHUYIN, ""))
        assertEquals(ReadingStyle.ZHUYIN, ReadingToggle.tapped(ReadingStyle.OFF, "Zhuyin"))
        assertEquals(ReadingStyle.PINYIN, ReadingToggle.tapped(ReadingStyle.OFF, "Pinyin"))
        // none used yet, or what no style is
        assertEquals(ReadingStyle.PINYIN, ReadingToggle.tapped(ReadingStyle.OFF, ""))
        assertEquals(ReadingStyle.PINYIN, ReadingToggle.tapped(ReadingStyle.OFF, "Off"))
        assertEquals(ReadingStyle.PINYIN, ReadingToggle.tapped(ReadingStyle.OFF, "x"))
    }

    @Test
    fun theLastUsedIsTheLastOn() {
        assertEquals("Zhuyin", ReadingToggle.last(ReadingStyle.ZHUYIN, "Pinyin"))
        assertEquals("Pinyin", ReadingToggle.last(ReadingStyle.OFF, "Pinyin"))
        assertEquals("", ReadingToggle.last(ReadingStyle.OFF, ""))
    }
}
