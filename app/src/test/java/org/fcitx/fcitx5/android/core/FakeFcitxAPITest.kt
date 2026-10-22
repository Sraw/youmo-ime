/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.test.runTest
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/** The fake answers as fcitx and the engine do where a test may depend on it. */
class FakeFcitxAPITest {

    private fun entry(name: String) = InputMethodEntry(name, name, "", "", "", "zh_CN", "androidengine", false)

    private val pinyin = entry("pinyin")
    private val t9 = entry("t9")
    private val wubi = entry("wubi")

    private fun fcitx() = FakeFcitxAPI().apply { availableIme = arrayOf(pinyin, t9, wubi) }

    @Test
    fun theInputMethodsEnabledAreInTheOrderGiven() = runTest {
        val fcitx = fcitx()
        fcitx.setEnabledIme(arrayOf("wubi", "none", "pinyin"))
        assertEquals(listOf(wubi, pinyin), fcitx.enabledIme().toList())
    }

    @Test
    fun theCurrentInputMethodTakenOutIsNoLongerCurrent() = runTest {
        val fcitx = fcitx()
        fcitx.setEnabledIme(arrayOf("pinyin", "t9"))
        fcitx.activateIme("t9")
        fcitx.setEnabledIme(arrayOf("wubi", "pinyin"))
        assertEquals(wubi, fcitx.currentIme())
        // one still enabled stays current, wherever it moved to
        fcitx.setEnabledIme(arrayOf("pinyin", "wubi"))
        assertEquals(wubi, fcitx.currentIme())
    }
}
