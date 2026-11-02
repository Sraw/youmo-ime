/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.view.View
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.theme.ThemeManager
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

    private fun shown(style: ReadingStyle, sp: Int, name: String) {
        ThemeManager.prefs.candidateTextSize.setValue(sp)
        EngineState.candidateReadingState.value = style
        val h = KeyboardHarness().also { harness = it }.start()
        val bar = h.described(h.service.getString(R.string.expand_toolbar)).parent as View
        val readings = if (style == ReadingStyle.ZHUYIN) zhuyin else pinyin
        h.api.candidates = Array(words.size) { CandidateWord("", words[it], "", readings = readings[it]) }
        h.emit(HostEvent.Candidates(words.size, h.api.candidates.toList()))
        h.relayout()
        assertTrue(h.texts().toString(), "你好" in h.texts())
        assertEquals("$style at $sp sp", h.service.dp(CandidateBarHeight.dp(sp, style)), bar.height)
        // the candidates in place of the toolbar, and the keys below
        val candidates = h.inputView.findViewById<View>(R.id.candidate_view)
        assertTrue(h.shown(candidates))
        assertEquals(bar.height, candidates.height)
        h.capture("CandidateReadingsUatTest/$name")
        h.close()
        harness = null
    }

    @Test
    fun portrait() {
        for (style in listOf(ReadingStyle.PINYIN, ReadingStyle.ZHUYIN)) for (sp in listOf(20, 28)) {
            shown(style, sp, "${style.key.lowercase()}_${sp}sp_port")
        }
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun landscape() {
        for (style in listOf(ReadingStyle.PINYIN, ReadingStyle.ZHUYIN)) for (sp in listOf(20, 28)) {
            shown(style, sp, "${style.key.lowercase()}_${sp}sp_land")
        }
    }
}
