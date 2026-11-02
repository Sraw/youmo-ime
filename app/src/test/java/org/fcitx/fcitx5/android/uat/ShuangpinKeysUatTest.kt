/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ShuangpinKeyboard
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.preedit.SyllableStrip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * uat/07-shuangpin.md's 18 keys and nine keys, the keyboard's half (the engine's is ShuangpinUatTest
 * in :lib:ime-eval): what the keyboard looks like, and where it offers the syllables the engine tells it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ShuangpinKeysUatTest {

    private val shuangpin = InputMethodEntry(InputMethodCatalog.SHUANGPIN, "双拼", "", "", "双", "zh_CN", "androidengine", false)
    private val setting = AppPrefs.getInstance().keyboard.shuangpinKeyboard
    private var h: KeyboardHarness? = null

    private fun harness(keyboard: ShuangpinKeyboard): KeyboardHarness {
        setting.setValue(keyboard)
        return KeyboardHarness(im = shuangpin).also { h = it }.start()
    }

    @After
    fun close() {
        h?.close()
        setting.setValue(setting.defaultValue)
        EngineState.shuangpinScheme = ShuangpinScheme.XIAOHE
        EngineState.syllablesState.value = EngineState.SyllableState()
    }

    // what the engine tells of wo typed: the code row's preedit, and the syllables to take
    private fun KeyboardHarness.typedWo() {
        emit(HostEvent.InputPanel(FormattedText(arrayOf("wo"), intArrayOf(0), 2), FormattedText.Empty, FormattedText.Empty))
        EngineState.syllablesState.value = EngineState.SyllableState(InputMethodCatalog.SHUANGPIN, composing = true, syllables = listOf("wo", "w"), id = 1)
        idle()
    }

    private fun KeyboardHarness.strip() = inputView.findViewById<SyllableStrip>(R.id.code_row_syllables)

    @Test
    @Uat("SP-14")
    fun theEighteenKeysAreThreeRowsOfCapitalsAndTheCodeRowOffersTheSyllables() {
        val h = harness(ShuangpinKeyboard.Keys18)
        val labels = h.texts()
        for (l in listOf("Q", "WE", "RT", "Y", "U", "IO", "P", "A", "SD", "FG", "H", "JK", "L", "Z", "XC", "V", "BN", "M")) {
            assertTrue("$l in $labels", l in labels)
        }
        assertFalse("no 中/英: $labels", "中/英" in labels)
        assertTrue("no ⇧ nor a small letter: $labels", labels.none { it.length == 1 && it[0] in 'a'..'z' } && "⇧" !in labels)
        h.typedWo()
        val strip = h.strip()
        assertTrue(strip.isVisible)
        assertTrue(h.texts(strip).toString(), "wo" in h.texts(strip))
        h.capture("ShuangpinKeysUatTest/keys18_wo", h.inputView)
    }

    @Test
    @Uat("SP-15")
    fun theNineKeysHaveThePhonesLettersAndTheColumnOffersTheSyllables() {
        val h = harness(ShuangpinKeyboard.NineKeys)
        val labels = h.texts()
        for (l in listOf("ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")) assertTrue("$l in $labels", l in labels)
        assertFalse("no 中/英: $labels", "中/英" in labels)
        h.typedWo()
        // the column has them, the code row none
        assertFalse(h.strip().isVisible)
        val column = KeyboardHarness.all(h.keyboard).filterIsInstance<ScrollView>().single().getChildAt(0) as ViewGroup
        val shown = (0 until column.childCount).map { column.getChildAt(it) as TextView }.filter { it.isVisible }.map { it.text.toString() }
        assertEquals(listOf("wo", "w"), shown)
        h.capture("ShuangpinKeysUatTest/nine_wo", h.inputView)
    }

    @Test
    @Uat("SP-16")
    fun theEighteenKeysHaveLAndSemicolonOnOneKeyAndNoSemicolonKey() {
        EngineState.shuangpinScheme = ShuangpinScheme.MICROSOFT
        val h = harness(ShuangpinKeyboard.Keys18)
        val faces = h.texts()
        assertTrue(faces.toString(), "L;" in faces)
        assertFalse(faces.toString(), ";" in faces || "；" in faces)
    }

    // 小鹤 (and 自然码) type no `;`: the key is L alone, as pinyin's 18 keys have it
    @Test
    @Uat("SP-28")
    fun theEighteenKeysUnderASchemeWithoutTheSemicolonShowLAlone() {
        val h = harness(ShuangpinKeyboard.Keys18)
        val faces = h.texts()
        assertTrue(faces.toString(), "L" in faces)
        assertFalse(faces.toString(), "L;" in faces || ";" in faces || "；" in faces)
    }

    @Test
    @Uat("SP-28")
    fun theNineKeysUnderASchemeWithoutTheSemicolonShowJklAlone() {
        val h = harness(ShuangpinKeyboard.NineKeys)
        assertTrue(h.texts().toString(), "JKL" in h.texts() && "JKL;" !in h.texts())
        val five = h.longPress(h.nineKey("5"))
        assertFalse("$five", five.any { it == ";" || it == "；" || it == ":" || it == "：" })
    }

    @Test
    @Uat("SP-22")
    fun theNineKeysOneIsPunctuationWithNothingTypedTheSeparatorWhileTyping() {
        val h = harness(ShuangpinKeyboard.NineKeys)
        h.tap(h.nineKey("1"))
        assertEquals("，", h.field.text.toString())
        // while typing it is the separator, sent to the engine (ShuangpinUatTest has what it does there)
        h.typedWo()
        h.tap(h.nineKey("1"))
        assertEquals("，", h.field.text.toString())
        assertTrue(h.api.calls.toString(), h.api.calls.last().startsWith("sendKey(key=', "))
    }
}
