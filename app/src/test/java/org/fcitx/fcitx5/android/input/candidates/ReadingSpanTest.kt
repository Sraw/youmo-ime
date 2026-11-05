/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import android.graphics.Paint
import android.text.TextPaint
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.bar.CandidateBarHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil
import kotlin.math.max

/** A handwritten character's several readings ([ReadingSpan]): pinyin made smaller to the floor before the candidate widens; zhuyin a column each. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class)
class ReadingSpanTest {

    private val paint = TextPaint().apply { textSize = 60f }

    private fun width(reading: String, floor: Float = 0f) = ReadingSpan(reading, 0, floor = floor).getSize(paint, "行", 0, 1, null)

    private fun measured(text: String, size: Float) = TextPaint(paint).apply { textSize = size }.measureText(text)

    @Test
    fun severalPinyinReadingsShrinkToTheFloorThenWiden() {
        val char = paint.measureText("行")
        // one reading: half the size, as ever, the floor or not
        assertEquals(ceil(max(char, measured("xíng", 30f))).toInt(), width("xíng", 21f))
        // several, no floor: half the size, the candidate widened for them
        assertEquals(ceil(measured("xíng/háng…", 30f)).toInt(), width("xíng/háng…"))
        // with a floor: smaller, but no smaller than it
        val shrunk = width("xíng/háng…", 21f)
        assertEquals(ceil(max(char, measured("xíng/háng…", 21f))).toInt(), shrunk)
        assertTrue("$shrunk < ${width("xíng/háng…")}", shrunk < width("xíng/háng…"))
    }

    @Test
    fun severalZhuyinReadingsAColumnEach() {
        val one = width("ㄏㄠˇ")
        val two = width("ㄏㄠˇ/ㄏㄠˋ")
        val more = width("ㄒㄧㄥˊ/ㄏㄤˊ…")
        assertTrue("$one < $two", one < two)
        assertTrue("$two < $more", two < more)
        // the second column, and the gap before it: wider by more than a column's symbol
        assertTrue(two - one > measured("ㄅ", 30f))
    }

    // the line's metrics, at SDK 29 too: the text's own fonts are read from API 33 (0.5.1 crashed on 10-12 there)
    @Test
    fun aPinyinReadingMakesRoomAboveTheLineAHintsDoesNot() {
        fun metrics(span: ReadingSpan) = Paint.FontMetricsInt().also { span.getSize(paint, "行", 0, 1, it) }
        val hint = metrics(ReadingSpan("jué", 0, hintSize = 20f, hintRoom = 10f))
        val read = metrics(ReadingSpan("xíng", 0))
        assertEquals(ceil(paint.textSize * CandidateBarHeight.READING_LINE).toInt(), hint.ascent - read.ascent)
        assertEquals(hint.descent, read.descent)
    }
}
