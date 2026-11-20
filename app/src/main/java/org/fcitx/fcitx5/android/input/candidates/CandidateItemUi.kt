/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.util.TypedValue
import androidx.annotation.ColorInt
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
import org.fcitx.fcitx5.android.scene.engine.BarLook
import org.fcitx.fcitx5.android.scene.engine.CandidateLook
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import kotlin.math.roundToInt

class CandidateItemUi(override val ctx: Context, val theme: Theme, val bar: BarLook = SceneBar.of(ctx, theme)) : Ui {

    private val textSp = ThemeManager.prefs.candidateTextSize.getValue()

    private val text = view(::AutoScaleTextView) {
        scaleMode = AutoScaleTextView.Mode.Proportional
        // the view is made anew when 候选字号 changes
        textSize = textSp.toFloat() // sp
        isSingleLine = true
        gravity = gravityCenter
        setTextColor(bar.plain.color.value)
        SceneBar.typeface(theme, bar.plain)?.let { typeface = it }
    }

    override val root = view(::CustomGestureView) {
        background = SceneBar.candidateBackground(ctx, bar, bar.plain)

        /**
         * candidate long press feedback is handled by [org.fcitx.fcitx5.android.input.BaseInputView.showCandidateActionMenu]
         */
        longPressFeedbackEnabled = false

        add(text, lParams(wrapContent, matchParent) {
            gravity = gravityCenter
        })
    }

    private val floor get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, CandidateBarHeight.HINT_FLOOR_SP, ctx.resources.displayMetrics)

    // set again only as it changes
    private var shown: CandidateLook = bar.plain

    // none for a holder cleared (index -1): it is let go of, not shown
    private fun label(index: Int) = if (index < 0) "" else SceneBar.label(bar.labelStyle, index)

    /** [index] -1: drawn as any but the first, no label. */
    fun updateCandidate(candidate: CandidateWord, index: Int = -1) {
        val look = bar.candidate(first = index == 0, selected = false)
        if (look != shown) {
            if (look.fill != shown.fill || look.pressed != shown.pressed) root.background = SceneBar.candidateBackground(ctx, bar, look)
            if (look.font != shown.font || look.weight != shown.weight) text.typeface = SceneBar.typeface(theme, look) ?: Typeface.DEFAULT
            shown = look
        }
        val fg = look.color.value
        val altFg = look.commentColor.value
        val label = label(index)
        // a candidate of no reading (an emoji, English) is centred below the room too, so it stands on its neighbours'
        // baseline; zhuyin, or a hint's reading, take no room above
        val style = EngineState.candidateReading.value
        val hint = style == ReadingStyle.OFF
        text.setPadding(0, if (style == ReadingStyle.PINYIN) (text.textSize * CandidateBarHeight.READING_LINE).roundToInt() else 0, 0, 0)
        text.text = buildSpannedString {
            if (label.isNotEmpty()) color(look.labelColor.value) { append(label) }
            color(fg) {
                if (hint && candidate.readings.isNotEmpty()) {
                    val fm = text.paint.fontMetrics
                    val baseline = (ctx.dp(CandidateBarHeight.dp(textSp, ReadingStyle.OFF, bar.heightPercent)) - (fm.bottom - fm.top)) / 2 - fm.top
                    val size = ReadingSpan.hintSize(text.paint, baseline, floor)
                    ReadingSpan.annotate(this, candidate.text, candidate.readings, altFg, size, baseline)
                } else {
                    ReadingSpan.annotate(this, candidate.text, candidate.readings, altFg, floor = floor)
                }
            }
            appendComment(candidate, altFg)
        }
        // the theme's number or dot is not read out, only the candidate
        text.contentDescription = if (label.isEmpty()) null else text.text.subSequence(label.length, text.text.length)
    }
}

internal fun SpannableStringBuilder.appendComment(candidate: CandidateWord, @ColorInt fg: Int) {
    if (candidate.comment.isBlank()) return
    if (candidate.spaceBetweenComment) append(" ")
    color(fg) { append(candidate.comment) }
}
