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

/** With 候选词注音 on, a candidate of no reading stands on its read neighbours' baseline, in a cell as tall. */
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

    // a row's cells are as tall (the adapter's row height)
    private fun gridBaseline(grid: GridPagingCandidateViewAdapter, word: CandidateWord): Int {
        val holder = grid.onCreateViewHolder(FrameLayout(ctx), 0)
        holder.update(1, word)
        val cell = holder.itemView
        cell.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(cell.layoutParams.height, View.MeasureSpec.EXACTLY))
        cell.layout(0, 0, cell.measuredWidth, cell.measuredHeight)
        val text = (cell as ViewGroup).getChildAt(0)
        return text.top + text.baseline
    }

    // a row a style, so a pinyin row failing does not hide zhuyin's
    @Test
    @Uat("PY-121")
    fun aGridCellOfNoReadingStandsOnItsNeighboursBaseline() {
        fun onTheBaselineOf(style: ReadingStyle, withReading: CandidateWord) {
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

    // a line of pinyin raised by its span (ReadingSpan.getSize), one of none by the item's padding
    private fun floating(word: CandidateWord): View {
        val ui = LabeledCandidateItemUi(ctx, TestThemes.PixelLight, { textSize = 20f; setPadding(12, 6, 12, 6) })
        ui.update(word, active = false)
        ui.root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        ui.root.layout(0, 0, ui.root.measuredWidth, ui.root.measuredHeight)
        return ui.root
    }

    // below API 33 the span cannot ask the character's own fonts (ReadingSpan.hanLine). Robolectric's fonts have no
    // fallback of another height, so this guards the arithmetic only; the device's look is PY-121's screenshot
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
        // compared alone, a read and an unread candidate matched whether given room or not: the setting-off height says none
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
