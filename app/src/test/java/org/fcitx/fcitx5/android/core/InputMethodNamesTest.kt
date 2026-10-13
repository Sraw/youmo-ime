/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.fcitx.fcitx5.android.engine.host.Engines
import org.junit.Assert.assertEquals
import org.junit.Test

/** An input method renamed or added in the engine must not fall back to fcitx's Chinese name. */
class InputMethodNamesTest {

    // the app's resources are not on the test classpath: any string id reads as its own number
    private val context = mockk<Context> {
        every { getString(any()) } answers { "#${firstArg<Int>()}" }
    }

    @Test
    fun everyInputMethodOfTheAppHasANameOfItsOwn() {
        val ids = listOf(Engines.PINYIN, Engines.SHUANGPIN, Engines.T9, Engines.ENGLISH) + Engines.TABLES.keys
        assertEquals(emptyList<String>(), ids.filter { InputMethodNames.of(context, it, fallback = "") == "" })
    }

    @Test
    fun oneTheAppDoesNotKnowKeepsFcitxsName() {
        assertEquals("Unicode", InputMethodNames.of(context, "unicode", "Unicode"))
    }
}
