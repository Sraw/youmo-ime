/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View.MeasureSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** The baseline a parent aligns by is the one the text is drawn on, padding counted once. */
@RunWith(RobolectricTestRunner::class)
class AutoScaleTextViewTest {

    @Test
    fun theBaselineCountsTheTopPaddingOnce() {
        val view = AutoScaleTextView(RuntimeEnvironment.getApplication()).apply {
            gravity = Gravity.TOP
            setPadding(0, 10, 0, 0)
            text = "你好"
        }
        view.measure(
            MeasureSpec.makeMeasureSpec(1000, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(200, MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 1000, 200)
        // drawn at the top of the content, its ascent below the padding
        assertEquals((10 - view.paint.fontMetrics.top).roundToInt(), view.baseline)
    }

    // a part made smaller is drawn smaller: the text takes less room than drawn all at one size
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aSpannedTextKeepsItsSpans() {
        val app = RuntimeEnvironment.getApplication()
        fun width(text: CharSequence) = AutoScaleTextView(app).run {
            textSize = 40f
            this.text = text
            measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            measuredWidth
        }
        val styled = SpannableString("中/英").apply { setSpan(RelativeSizeSpan(0.5f), 1, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        assertTrue(width(styled) < width("中/英"))
        // set again with the same characters, the spans go too
        val view = AutoScaleTextView(app).apply { text = styled }
        view.text = "中/英"
        assertFalse(view.text is Spanned)
    }

    // inkBounds is where the glyphs are drawn: the drawn pixels' box, within 2 px, at its size and
    // squeezed sideways into a narrow view
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theInkIsWhereTheGlyphsAreDrawn() {
        for ((text, width) in listOf("g" to 200, "Wg@" to 200, "WWWWWW" to 120)) {
            val view = AutoScaleTextView(RuntimeEnvironment.getApplication()).apply {
                textSize = 60f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                scaleMode = AutoScaleTextView.Mode.Horizontal
                this.text = text
            }
            view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(150, MeasureSpec.EXACTLY))
            view.layout(0, 0, width, 150)
            val ink = RectF()
            assertTrue(view.inkBounds(ink))
            val bitmap = Bitmap.createBitmap(width, 150, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            var left = width
            var top = 150
            var right = -1
            var bottom = -1
            for (y in 0 until 150) for (x in 0 until width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x + 1)
                    bottom = maxOf(bottom, y + 1)
                }
            }
            assertTrue(text, right > left)
            assertEquals("$text left", left.toFloat(), ink.left, 2f)
            assertEquals("$text top", top.toFloat(), ink.top, 2f)
            assertEquals("$text right", right.toFloat(), ink.right, 2f)
            assertEquals("$text bottom", bottom.toFloat(), ink.bottom, 2f)
        }
    }
}
