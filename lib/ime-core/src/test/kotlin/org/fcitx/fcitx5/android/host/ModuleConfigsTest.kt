/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * punctuation.conf and chttrans.conf read and written as fcitx does (`ca/punctuation/punctuation.h:36-46`,
 * `ca/chttrans/chttrans.h:46-66`; bools by `fcitx-config/marshallfunction.cpp:21-32`, lists by
 * `marshallfunction.h:70-88`), the rest of each file kept.
 */
class ModuleConfigsTest {

    @Test
    fun `punctuation's three values, a bad one its default, a missing file all defaults`() {
        assertEquals(PunctuationConfig(), ModuleConfigs.punctuation(null))
        val read = ModuleConfigs.punctuation("HalfWidthPuncAfterLetterOrNumber=False\nEnabled=false\nTypePairedPunctuationsTogether=True\n")
        assertEquals(PunctuationConfig(enabled = true, halfWidthAfterLatinOrNumber = false, typePairedTogether = true), read)
    }

    @Test
    fun `punctuation written with defaults commented and the hotkey kept`() {
        val old = "HalfWidthPuncAfterLetterOrNumber=False\n\n[Hotkey]\n0=Control+period\n"
        val text = ModuleConfigs.punctuationText(old, PunctuationConfig(enabled = false, halfWidthAfterLatinOrNumber = false))
        assertEquals(
            "HalfWidthPuncAfterLetterOrNumber=False\nEnabled=False\n# TypePairedPunctuationsTogether=False\n\n[Hotkey]\n0=Control+period\n\n",
            text,
        )
        assertEquals(PunctuationConfig(enabled = false, halfWidthAfterLatinOrNumber = false), ModuleConfigs.punctuation(text))
    }

    @Test
    fun `chttrans' list read till the first gap, written in place, the engine kept`() {
        val old = "Engine=OpenCC\n\n[EnabledIM]\n0=engine-pinyin\n1=engine-t9\n3=engine-wubi\n"
        assertEquals(setOf("engine-pinyin", "engine-t9"), ModuleConfigs.chttrans(old))
        val text = ModuleConfigs.chttransText(old, setOf("engine-wubi"))
        assertEquals("Engine=OpenCC\n\n[EnabledIM]\n0=engine-wubi\n\n", text)
        assertEquals(setOf("engine-wubi"), ModuleConfigs.chttrans(text))
        assertEquals("Engine=OpenCC\n\n", ModuleConfigs.chttransText(old, emptySet()))
        assertEquals(emptySet<String>(), ModuleConfigs.chttrans(null))
    }
}
