/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.util.LruCache
import android.util.TypedValue
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.recyclerview.widget.GridLayoutManager
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.input.bar.CandidateBarHeight
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.ReadingSpan
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent

abstract class GridPagingCandidateViewAdapter(theme: Theme) : PagingCandidateViewAdapter(theme) {

    companion object {
        // 20f here is chosen randomly, since we only care about the ratio
        private const val TEXT_SIZE = 20f
    }

    // 候选字号 scales the words, so the rows and the spans a word takes scale with it (design §6);
    // the input view, and with it this adapter, is made anew when the size changes
    private val textSp = ThemeManager.prefs.candidateTextSize.getValue()
    private val textScale = textSp / CandidateBarHeight.DEFAULT_TEXT_SP.toFloat()

    // cache measureWidth
    private val measuredWidths = object : LruCache<String, Float>(200) {
        private val cachedPaint = Paint().apply { textSize = TEXT_SIZE }
        private val cachedRect = Rect()
        override fun create(key: String): Float {
            cachedPaint.getTextBounds(key, 0, key.length, cachedRect)
            return cachedRect.width() / TEXT_SIZE
        }
    }

    fun measureWidth(position: Int): Float {
        // the span lookup reads ahead of what is shown: getItem would have Paging load for it
        val candidate = peek(position) ?: return 0f
        if (candidate.readings.isEmpty()) return measuredWidths[candidate.textWithComment()] * textScale
        val hint = EngineState.candidateReading.value == ReadingStyle.OFF
        val key = listOf(hint, candidate.text, candidate.readings.joinToString("\u0001"), candidate.comment, candidate.spaceBetweenComment)
            .joinToString("\u0000")
        return annotatedWidths[key] ?: annotatedWidth(candidate, hint).also { annotatedWidths.put(key, it) }
    }

    // the widths of words with readings, measured once each as the grid lays out again and again
    private val annotatedWidths = LruCache<String, Float>(200)

    private val readingPaint = TextPaint()

    /**
     * [candidate]'s width with its readings ([ReadingSpan]): measured as CandidateItemUi draws them, at the
     * candidates' own size, since a reading's floor does not scale with the words; a hint's ([hint], 候选词注音
     * off) small above the characters, else the readings as the setting has them; the comment after them.
     */
    @VisibleForTesting
    internal fun annotatedWidth(candidate: CandidateWord, hint: Boolean): Float {
        val metrics = Resources.getSystem().displayMetrics
        val paint = readingPaint.apply { textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSp.toFloat(), metrics) }
        val floor = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, CandidateBarHeight.HINT_FLOOR_SP, metrics)
        val annotated = SpannableStringBuilder()
        if (hint) {
            val fm = paint.fontMetrics
            val bar = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, CandidateBarHeight.dp(textSp).toFloat(), metrics)
            val baseline = (bar - (fm.bottom - fm.top)) / 2 - fm.top
            ReadingSpan.annotate(annotated, candidate.text, candidate.readings, 0, ReadingSpan.hintSize(paint, baseline, floor), baseline)
        } else {
            ReadingSpan.annotate(annotated, candidate.text, candidate.readings, 0, floor = floor)
        }
        if (candidate.comment.isNotBlank()) annotated.append(if (candidate.spaceBetweenComment) " " else "").append(candidate.comment)
        return Layout.getDesiredWidth(annotated, paint) / paint.textSize * textScale
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        return super.onCreateViewHolder(parent, viewType).apply {
            itemView.apply {
                layoutParams = GridLayoutManager.LayoutParams(matchParent, dp(CandidateBarHeight.dp(textSp, EngineState.candidateReading.value)))
            }
        }
    }
}