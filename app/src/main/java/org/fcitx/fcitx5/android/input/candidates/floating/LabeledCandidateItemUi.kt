/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.floating

import android.content.Context
import android.graphics.Typeface
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
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.scene.engine.BarLook
import org.fcitx.fcitx5.android.scene.engine.CandidateLook
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.textView
import kotlin.math.ceil

/**
 * A candidate of the physical keyboard's window, its fcitx label before it: drawn as the theme has the
 * candidates ([bar], 060-render-p3 T6a), the one the cursor is on `:selected`, the page's first `:first`;
 * `label-style` none drops the label, dot puts a dot for it.
 */
class LabeledCandidateItemUi(
    override val ctx: Context,
    val theme: Theme,
    setupTextView: TextView.() -> Unit,
    val bar: BarLook = SceneBar.look(ctx, theme),
) : Ui {

    override val root = textView {
        setupTextView(this)
        SceneBar.typeface(theme, bar.plain)?.let { typeface = it }
        // room above every candidate for a hint's reading at its floor (候选词注音 off; ReadingSpan), so the
        // window keeps one height whichever candidates it shows. Kept with 候选词注音 on as well, where a pinyin
        // reading raises the line by itself: the item is made once, not again as the setting changes
        val fm = paint.fontMetrics
        val need = paint.textSize * CandidateBarHeight.HAN_TOP + floor * (fm.descent - fm.ascent) / paint.textSize
        val top = paddingTop + if (includeFontPadding) -fm.top else -fm.ascent
        if (need > top) setPadding(paddingLeft, paddingTop + ceil(need - top).toInt(), paddingRight, paddingBottom)
    }

    private val floor get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, CandidateBarHeight.HINT_FLOOR_SP, ctx.resources.displayMetrics)

    // the look last drawn: its fill and font set again only as it changes
    private var shown: CandidateLook? = null

    /** Draws [candidate], the cursor on it if [active], the page's [first]. */
    fun update(candidate: CandidateWord, active: Boolean, first: Boolean = false) {
        val look = bar.candidate(first, active)
        if (look != shown) {
            if (look.fill != shown?.fill) root.background = SceneBar.candidateBackground(ctx, bar, look, pressable = false)
            if (look.font != (shown ?: bar.plain).font || look.weight != (shown ?: bar.plain).weight) {
                root.typeface = SceneBar.typeface(theme, look) ?: Typeface.DEFAULT
            }
            shown = look
        }
        val labelFg = look.labelColor.value
        val fg = look.color.value
        val altFg = look.commentColor.value
        // fcitx's label (its number) as ever, or what the theme's label-style puts there
        val label = when (bar.labelStyle) {
            BarLook.LabelStyle.Number, null -> candidate.label
            BarLook.LabelStyle.None -> ""
            BarLook.LabelStyle.Dot -> SceneBar.label(BarLook.LabelStyle.Dot, 0)
        }
        root.text = buildSpannedString {
            color(labelFg) {
                append(label)
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
    }
}
