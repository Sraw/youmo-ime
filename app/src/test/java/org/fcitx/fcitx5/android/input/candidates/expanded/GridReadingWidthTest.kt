/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.expanded

import android.text.Layout
import android.view.ViewGroup
import android.widget.TextView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.input.bar.CandidateBarHeight
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The expanded grid's width of a word with readings, which places it in its spans: measured as
 * CandidateItemUi draws it (a copy of its hint's baseline and the readings' floor), so a change to
 * one that the other misses, puts words in too few spans.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class)
class GridReadingWidthTest {

    @After
    fun off() {
        EngineState.candidateReadingState.value = ReadingStyle.OFF
    }

    private val word = CandidateWord("", "银行", "", readings = listOf("yín", "háng"))

    // what CandidateItemUi's text needs, in the grid's unit (a word's width over its text size, scaled by 候选字号)
    private fun drawn(): Float {
        val ui = CandidateItemUi(RuntimeEnvironment.getApplication(), ThemePreset.PixelLight)
        ui.updateCandidate(word)
        val text = (ui.root as ViewGroup).getChildAt(0) as TextView
        val scale = ThemeManager.prefs.candidateTextSize.getValue() / CandidateBarHeight.DEFAULT_TEXT_SP.toFloat()
        return Layout.getDesiredWidth(text.text, text.paint) / text.paint.textSize * scale
    }

    @Test
    @Uat("PY-111")
    fun aWordWithReadingsIsMeasuredAsItIsDrawnInEachStyle() {
        val grid = object : GridPagingCandidateViewAdapter(ThemePreset.PixelLight) {}
        for (style in listOf(ReadingStyle.OFF, ReadingStyle.PINYIN, ReadingStyle.ZHUYIN)) {
            EngineState.candidateReadingState.value = style
            assertEquals("$style", drawn(), grid.annotatedWidth(word, hint = style == ReadingStyle.OFF), 0.01f)
        }
    }
}
