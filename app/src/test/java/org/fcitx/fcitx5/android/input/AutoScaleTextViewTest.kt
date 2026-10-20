/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

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
}
