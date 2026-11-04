/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.os.Looper
import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.bar.CandidateBarHeight
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import splitties.dimensions.dp

/** 候选词注音 on the bar: pinyin above each character, zhuyin to its right, the bar's height as CandidateBarHeight has it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class CandidateReadingsUatTest {

    private var harness: KeyboardHarness? = null

    @After
    fun close() {
        harness?.close()
        EngineState.candidateReadingState.value = ReadingStyle.OFF
        ThemeManager.prefs.candidateTextSize.let { it.setValue(it.defaultValue) }
    }

    // 东西 in 西's own tone (no 轻声, the user 2026-11-02); 呢 has no other, so its ˙ is still drawn
    private val words = listOf("你好", "东西", "你", "呢", "泥", "银行")
    private val pinyin = listOf(listOf("nǐ", "hǎo"), listOf("dōng", "xī"), listOf("nǐ"), listOf("ne"), listOf("ní"), listOf("yín", "háng"))
    private val zhuyin = listOf(listOf("ㄋㄧˇ", "ㄏㄠˇ"), listOf("ㄉㄨㄥ", "ㄒㄧ"), listOf("ㄋㄧˇ"), listOf("˙ㄋㄜ"), listOf("ㄋㄧˊ"), listOf("ㄧㄣˊ", "ㄏㄤˊ"))

    // handwriting's characters, each with all its readings (two, then … for more: CandidateReadings.allOf)
    private val written = listOf("行", "衍", "街", "彷", "和")
    private val writtenPinyin = listOf(listOf("xíng/háng…"), listOf("yǎn"), listOf("jiē"), listOf("páng/fǎng"), listOf("hé/hè…"))
    private val writtenZhuyin = listOf(listOf("ㄒㄧㄥˊ/ㄏㄤˊ…"), listOf("ㄧㄢˇ"), listOf("ㄐㄧㄝ"), listOf("ㄆㄤˊ/ㄈㄤˇ"), listOf("ㄏㄜˊ/ㄏㄜˋ…"))

    private fun shown(style: ReadingStyle, sp: Int, name: String, handwriting: Boolean = false, theme: Theme = ThemePreset.PixelLight) {
        ThemeManager.prefs.candidateTextSize.setValue(sp)
        EngineState.candidateReadingState.value = style
        val h = KeyboardHarness(theme).also { harness = it }.start()
        val bar = h.described(h.service.getString(R.string.expand_toolbar)).parent as View
        val words = if (handwriting) written else words
        val readings = when {
            handwriting -> if (style == ReadingStyle.ZHUYIN) writtenZhuyin else writtenPinyin
            else -> if (style == ReadingStyle.ZHUYIN) zhuyin else pinyin
        }
        h.api.candidates = Array(words.size) { CandidateWord("", words[it], "", readings = readings[it]) }
        h.emit(HostEvent.Candidates(words.size, h.api.candidates.toList()))
        h.relayout()
        assertTrue(h.texts().toString(), words[0] in h.texts())
        assertEquals("$style at $sp sp", h.service.dp(CandidateBarHeight.dp(sp, style)), bar.height)
        // the candidates in place of the toolbar, and the keys below
        val candidates = h.inputView.findViewById<View>(R.id.candidate_view)
        assertTrue(h.shown(candidates))
        assertEquals(bar.height, candidates.height)
        h.capture("CandidateReadingsUatTest/$name")
        h.close()
        harness = null
    }

    // PY-110: each word in its characters' own tones (东西 dōng xī), pinyin and zhuyin
    @Test
    @Uat("PY-110")
    fun portrait() {
        for (style in listOf(ReadingStyle.PINYIN, ReadingStyle.ZHUYIN)) for (sp in listOf(20, 28)) {
            shown(style, sp, "${style.key.lowercase()}_${sp}sp_port")
        }
    }

    // the keyboard is made anew for each other style, compared with the one it was made with: it was
    // compared with the style first read, so turned on and back off it stayed made for the one turned on
    @Test
    fun theKeyboardIsMadeAnewForEachOtherStyle() {
        val h = KeyboardHarness().also { harness = it }.start()
        val off = h.inputView
        EngineState.candidateReadingState.value = ReadingStyle.ZHUYIN
        shadowOf(Looper.getMainLooper()).idle()
        val zhuyin = h.inputView
        assertTrue("made anew for zhuyin", zhuyin !== off)
        EngineState.candidateReadingState.value = ReadingStyle.OFF
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("made anew back off", h.inputView !== zhuyin)
    }

    // the style last used kept whichever keyboard is up (review 051-hw-reading M3): pinyin's here, zhuyin set
    // and then off in the settings; 注 turns zhuyin on again
    @Test
    fun theStyleLastUsedIsKeptFromTheSettingsToo() {
        val last = AppPrefs.getInstance().internal.lastCandidateReading
        last.setValue("")
        try {
            KeyboardHarness().also { harness = it }.start()
            EngineState.candidateReadingState.value = ReadingStyle.ZHUYIN
            shadowOf(Looper.getMainLooper()).idle()
            EngineState.candidateReadingState.value = ReadingStyle.OFF
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(ReadingStyle.ZHUYIN.key, last.getValue())
        } finally {
            last.setValue("")
        }
    }

    // UAT HW-27: a handwritten character's readings, two and … for more, in the bar's height as for one
    @Test
    @Uat("HW-27")
    fun handwritingPortrait() {
        for (theme in listOf(ThemePreset.PixelLight, ThemePreset.PixelDark)) for (style in listOf(ReadingStyle.PINYIN, ReadingStyle.ZHUYIN)) for (sp in listOf(20, 28)) {
            val dark = if (theme.isDark) "_dark" else ""
            shown(style, sp, "handwriting_${style.key.lowercase()}_${sp}sp_port$dark", handwriting = true, theme = theme)
        }
    }

    @Test
    @Uat("HW-27")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun handwritingLandscape() {
        for (style in listOf(ReadingStyle.PINYIN, ReadingStyle.ZHUYIN)) for (sp in listOf(20, 28)) {
            shown(style, sp, "handwriting_${style.key.lowercase()}_${sp}sp_land", handwriting = true)
        }
    }

    @Test
    @Uat("PY-110")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun landscape() {
        for (style in listOf(ReadingStyle.PINYIN, ReadingStyle.ZHUYIN)) for (sp in listOf(20, 28)) {
            shown(style, sp, "${style.key.lowercase()}_${sp}sp_land")
        }
    }
}
