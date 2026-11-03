/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.floating

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.widget.TextView
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.input.bar.CandidateBarHeight
import org.fcitx.fcitx5.android.input.candidates.ReadingSpan
import splitties.views.backgroundColor
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.textView
import kotlin.math.ceil

class LabeledCandidateItemUi(
    override val ctx: Context,
    val theme: Theme,
    setupTextView: TextView.() -> Unit
) : Ui {

    override val root = textView {
        setupTextView(this)
        // room above every candidate for a hint's reading at its floor (候选词注音 off; ReadingSpan), so the
        // window keeps one height whichever candidates it shows
        val fm = paint.fontMetrics
        val need = paint.textSize * CandidateBarHeight.HAN_TOP + floor * (fm.descent - fm.ascent) / paint.textSize
        val top = paddingTop + if (includeFontPadding) -fm.top else -fm.ascent
        if (need > top) setPadding(paddingLeft, paddingTop + ceil(need - top).toInt(), paddingRight, paddingBottom)
    }

    private val floor get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, CandidateBarHeight.HINT_FLOOR_SP, ctx.resources.displayMetrics)

    fun update(candidate: CandidateWord, active: Boolean) {
        val labelFg = if (active) theme.genericActiveForegroundColor else theme.candidateLabelColor
        val fg = if (active) theme.genericActiveForegroundColor else theme.candidateTextColor
        val altFg = if (active) theme.genericActiveForegroundColor else theme.candidateCommentColor
        root.text = buildSpannedString {
            color(labelFg) {
                append(candidate.label)
            }
            color(fg) {
                if (EngineState.candidateReading.value == ReadingStyle.OFF && candidate.readings.isNotEmpty()) {
                    val fm = root.paint.fontMetrics
                    val baseline = root.paddingTop + if (root.includeFontPadding) -fm.top else -fm.ascent
                    ReadingSpan.annotate(this, candidate.text, candidate.readings, altFg, ReadingSpan.hintSize(root.paint, baseline, floor), baseline)
                } else {
                    ReadingSpan.annotate(this, candidate.text, candidate.readings, altFg, floor = floor)
                }
            }
            if (candidate.comment.isNotBlank()) {
                if (candidate.spaceBetweenComment) {
                    append(" ")
                }
                color(altFg) {
                    append(candidate.comment)
                }
            }
        }
        val bg = if (active) theme.genericActiveBackgroundColor else Color.TRANSPARENT
        root.backgroundColor = bg
    }
}
