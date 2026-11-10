/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import android.content.Context
import android.graphics.Typeface
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

/**
 * A candidate in the bar or the expanded candidates, drawn as the theme has it ([bar], 060-render-p3 T6a):
 * its fill and pressed colour, its colours and font, the first of the list in its own; the number or dot
 * before it its label-style asks for.
 */
class CandidateItemUi(override val ctx: Context, val theme: Theme, val bar: BarLook = SceneBar.of(ctx, theme)) : Ui {

    private val textSp = ThemeManager.prefs.candidateTextSize.getValue()

    private val text = view(::AutoScaleTextView) {
        scaleMode = AutoScaleTextView.Mode.Proportional
        // 候选字号: the bar, its 联想 and the expanded grid; the view is made anew when it changes
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

    // the smallest a reading is drawn (hints', and a handwritten character's several)
    private val floor get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, CandidateBarHeight.HINT_FLOOR_SP, ctx.resources.displayMetrics)

    // the look last drawn: the first's and the others' drawables and font, set again only as it changes
    private var shown: CandidateLook = bar.plain

    /**
     * What [updateCandidate] draws of the candidate at [index] besides it: whether it is the first, its
     * number. Equal for two indices, the candidate drawn at one is drawn alike at the other.
     */
    fun byIndex(index: Int): Pair<Boolean, String> = (index == 0) to label(index)

    // none for a holder cleared (index -1): it is let go of, not shown
    private fun label(index: Int) = if (index < 0) "" else SceneBar.label(bar.labelStyle, index)

    /** Draws [candidate], the [index]th of the list (from 0; -1 none: drawn as any but the first, no label). */
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
        // pinyin above the words: the text centred below it, in the bar made taller for it (CandidateBarHeight)
        // or, the setting off, a hint's reading above them in the bar's own height, where plain words stand
        val hint = EngineState.candidateReading.value == ReadingStyle.OFF
        val above = !hint && ReadingSpan.above(candidate.readings)
        text.setPadding(0, if (above) (text.textSize * CandidateBarHeight.READING_LINE).roundToInt() else 0, 0, 0)
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
            if (candidate.comment.isNotBlank()) {
                if (candidate.spaceBetweenComment) {
                    append(" ")
                }
                color(altFg) {
                    append(candidate.comment)
                }
            }
        }
        // the theme's number or dot is not read out, only the candidate (a screen reader's, as before it)
        text.contentDescription = if (label.isEmpty()) null else text.text.subSequence(label.length, text.text.length)
    }
}
