/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.input.candidates.expanded.GridPagingCandidateViewAdapter
import org.fcitx.fcitx5.android.input.candidates.floating.LabeledCandidateItemUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * PY-121: with 候选词注音 on, a candidate of no reading (a character whose tone is not known, in zhuyin; an
 * emoji; a word in English) stands on the baseline of its neighbours read, in a cell as tall (the user,
 * 2026-11-14): the expanded grid's cells and the floating window's. The bar's: CandidateReadingsUatTest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class)
class ReadingBaselineTest {

    @After
    fun off() {
        EngineState.candidateReadingState.value = ReadingStyle.OFF
    }

    private val ctx get() = RuntimeEnvironment.getApplication()

    private val read = CandidateWord("", "你", "", readings = listOf("nǐ"))
    private val zhuyinRead = CandidateWord("", "你", "", readings = listOf("ㄋㄧˇ"))
    private val plain = listOf(CandidateWord("", "好", ""), CandidateWord("", "😀", ""), CandidateWord("", "ok", ""))

    // a grid cell's glyphs' baseline from the cell's top: a row's cells are as tall (the adapter's row height)
    private fun gridBaseline(grid: GridPagingCandidateViewAdapter, word: CandidateWord): Int {
        val holder = grid.onCreateViewHolder(FrameLayout(ctx), 0)
        holder.update(1, word)
        val cell = holder.itemView
        cell.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(cell.layoutParams.height, View.MeasureSpec.EXACTLY))
        cell.layout(0, 0, cell.measuredWidth, cell.measuredHeight)
        val text = (cell as ViewGroup).getChildAt(0)
        return text.top + text.baseline
    }

    // R25r4 F7973: a row a style, each its own grid and style, so a pinyin row failing does not hide zhuyin's
    @Test
    @Uat("PY-121")
    fun aGridCellOfNoReadingStandsOnItsNeighboursBaseline() {
        fun onTheBaselineOf(style: ReadingStyle, withReading: CandidateWord) {
            // the row's height is the style's, read as the cell is made
            EngineState.candidateReadingState.value = style
            try {
                val grid = object : GridPagingCandidateViewAdapter(TestThemes.PixelLight) {}
                val baseline = gridBaseline(grid, withReading)
                assertEquals("$style", plain.map { it.text to baseline }, plain.map { it.text to gridBaseline(grid, it) })
            } finally {
                EngineState.candidateReadingState.value = ReadingStyle.OFF
            }
        }
        assertRows(
            "pinyin: the reading's line above every cell, left empty over a candidate of none" to { onTheBaselineOf(ReadingStyle.PINYIN, read) },
            "zhuyin: beside the character, no room above any cell" to { onTheBaselineOf(ReadingStyle.ZHUYIN, zhuyinRead) },
        )
    }

    // the floating window's candidate: a TextView laid out by lines, a line of pinyin raised by its span
    // (ReadingSpan.getSize), one of none by the padding the item gives it; the window's own text size and padding
    private fun floating(word: CandidateWord): View {
        val ui = LabeledCandidateItemUi(ctx, TestThemes.PixelLight, { textSize = 20f; setPadding(12, 6, 12, 6) })
        ui.update(word, active = false)
        ui.root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        ui.root.layout(0, 0, ui.root.measuredWidth, ui.root.measuredHeight)
        return ui.root
    }

    // a Han character read and one not, alike in their fonts, at Android 10 as at 15: below API 33 the span cannot ask
    // the character's own fonts and takes a Han character's line as laid out (ReadingSpan.hanLine; R25r4 F7990, the
    // paint's own font there, shorter than the plain line's fallback, put the two lines on two baselines).
    // Robolectric's fonts have no fallback of another height: at sdk 29 this passes with or without hanLine (R25r5
    // F8003, tried), so it guards the path's arithmetic only; the device's look is PY-121's screenshot on youmo29
    @Test
    @Uat("PY-121")
    @Config(sdk = [29, 35])
    fun aFloatingCandidateOfNoReadingIsAsTallAndOnTheSameBaseline() {
        fun pinyin() {
            EngineState.candidateReadingState.value = ReadingStyle.PINYIN
            val withReading = floating(read)
            val without = floating(plain[0])
            assertEquals("baseline", withReading.baseline, without.baseline)
            assertEquals("height", withReading.measuredHeight, without.measuredHeight)
        }
        // R25r4 F7987: a candidate read and one not compared alone held whether the one not was given room or not;
        // its height with the setting off says it was given none
        fun zhuyin() {
            EngineState.candidateReadingState.value = ReadingStyle.OFF
            val off = floating(plain[0]).measuredHeight
            EngineState.candidateReadingState.value = ReadingStyle.ZHUYIN
            assertEquals("no room above", off, floating(plain[0]).measuredHeight)
            assertEquals("baseline", floating(zhuyinRead).baseline, floating(plain[0]).baseline)
        }
        assertRows(
            "pinyin: a candidate of none given the reading's line above, as tall as one read and on its baseline" to { pinyin() },
            "zhuyin: beside the character, no room above for either" to { zhuyin() },
        )
    }

    private companion object {
        const val WIDTH = 300
    }
}
