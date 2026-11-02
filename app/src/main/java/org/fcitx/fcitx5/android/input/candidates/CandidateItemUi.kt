/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import android.content.Context
import android.util.TypedValue
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.bar.CandidateBarHeight
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.utils.pressHighlightDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import kotlin.math.roundToInt

class CandidateItemUi(override val ctx: Context, val theme: Theme) : Ui {

    private val textSp = ThemeManager.prefs.candidateTextSize.getValue()

    private val text = view(::AutoScaleTextView) {
        scaleMode = AutoScaleTextView.Mode.Proportional
        // 候选字号: the bar, its 联想 and the expanded grid; the view is made anew when it changes
        textSize = textSp.toFloat() // sp
        isSingleLine = true
        gravity = gravityCenter
        setTextColor(theme.candidateTextColor)
    }

    override val root = view(::CustomGestureView) {
        background = pressHighlightDrawable(theme.keyPressHighlightColor)

        /**
         * candidate long press feedback is handled by [org.fcitx.fcitx5.android.input.BaseInputView.showCandidateActionMenu]
         */
        longPressFeedbackEnabled = false

        add(text, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
    }

    fun updateCandidate(candidate: CandidateWord) {
        val fg = theme.candidateTextColor
        val altFg = theme.candidateCommentColor
        // pinyin above the words: the text centred below it, in the bar made taller for it (CandidateBarHeight)
        // or, the setting off, a hint's reading above them in the bar's own height, where plain words stand
        val hint = EngineState.candidateReading.value == ReadingStyle.OFF
        val above = !hint && ReadingSpan.above(candidate.readings)
        text.setPadding(0, if (above) (text.textSize * CandidateBarHeight.READING_LINE).roundToInt() else 0, 0, 0)
        text.text = buildSpannedString {
            color(fg) {
                if (hint && candidate.readings.isNotEmpty()) {
                    val fm = text.paint.fontMetrics
                    val baseline = (ctx.dp(CandidateBarHeight.dp(textSp)) - (fm.bottom - fm.top)) / 2 - fm.top
                    val size = ReadingSpan.hintSize(text.paint, baseline, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, CandidateBarHeight.HINT_FLOOR_SP, ctx.resources.displayMetrics))
                    ReadingSpan.annotate(this, candidate.text, candidate.readings, altFg, size, baseline)
                } else {
                    ReadingSpan.annotate(this, candidate.text, candidate.readings, altFg)
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
    }
}
