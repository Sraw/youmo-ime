/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.reading.CharCard
import org.fcitx.fcitx5.android.input.popup.CharCardLayout
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import splitties.dimensions.dp
import kotlin.math.min

/**
 * A character's card, the first row of its held candidate's menu (BaseInputView.showCandidateActionMenu):
 * the character large on the left, each reading on its right (pinyin, zhuyin, up to two words), the
 * readings scrolled past [CharCardLayout.readingRows]; then the radical, strokes, parts and stroke order,
 * under them or, [twoColumns], beside them; [buttons] (上屏, 复制) last, under them all or, [twoColumns],
 * in the right column. What the card does not know it leaves out. TalkBack reads it as one sentence
 * ([CharCard.spoken]), the buttons each on its own.
 */
@SuppressLint("ViewConstructor")
class CharCardView(
    context: Context,
    val card: CharCard,
    /** The candidates' text size, in pixels: the character [CharCardLayout.CHAR_SCALE] times it. */
    textSize: Float,
    twoColumns: Boolean,
    accent: Int,
    buttons: View? = null,
) : LinearLayout(context) {

    private val secondary = context.styledColorOrDefault(android.R.attr.textColorSecondary, Color.GRAY)

    private fun text(s: CharSequence, sp: Float = TEXT_SP, color: Int? = null) = TextView(context).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        color?.let(::setTextColor)
    }

    // the readings, a row each: pinyin, zhuyin, the words
    private val readings = TableLayout(context).apply {
        card.readings.forEach { r ->
            addView(TableRow(context).apply {
                addView(text(r.pinyin), cell())
                addView(text(r.zhuyin), cell())
                addView(text(r.examples.joinToString(" "), color = secondary), cell())
            })
        }
    }

    private fun cell() = TableRow.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginEnd = dp(CELL_GAP)
        bottomMargin = dp(ROW_GAP)
    }

    /** The readings' scroller, as high as [CharCardLayout.readingRows] of them. */
    val readingsScroll: ScrollView = Capped(context, CharCardLayout.readingRows(twoColumns)).apply {
        isVerticalScrollBarEnabled = true
        addView(readings)
    }

    private val info = LinearLayout(context).apply {
        orientation = VERTICAL
        val first = listOfNotNull(
            card.radical?.let { context.getString(R.string.char_card_radical) + " " + it },
            card.strokes?.let { context.getString(R.string.char_card_strokes, it) },
        ).joinToString(SPACE)
        if (first.isNotEmpty()) addView(text(first))
        card.partsShown()?.let { addView(text(context.getString(R.string.char_card_parts) + " " + it)) }
        card.orderShown()?.let { addView(text(context.getString(R.string.char_card_order) + " " + it)) }
    }

    init {
        orientation = if (twoColumns) HORIZONTAL else VERTICAL
        val pad = dp(PADDING)
        setPadding(pad, pad, pad, pad)
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(text(card.text, color = accent).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * CharCardLayout.CHAR_SCALE)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(CELL_GAP * 2) })
            addView(readingsScroll, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_VERTICAL })
        }
        if (info.childCount == 0) info.visibility = GONE
        if (twoColumns) {
            // the readings as wide as they are, the rest what is left: the radical and the rest, the buttons under them
            addView(top, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val side = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(info, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                buttons?.let { addView(it, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ROW_GAP * 2) }) }
            }
            addView(side, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(CELL_GAP * 2) })
        } else {
            addView(top, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(info, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ROW_GAP * 2) })
            buttons?.let { addView(it, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ROW_GAP * 2) }) }
        }
        // one sentence for TalkBack, not a stop at each line
        val strokeNames = context.resources.getStringArray(R.array.char_card_stroke_names)
        contentDescription = card.spoken(
            CharCard.Words(
                context.getString(R.string.char_card_readings) + " ", context.getString(R.string.char_card_radical) + " ",
                context.getString(R.string.char_card_parts) + " ", context.getString(R.string.char_card_order) + " ", "、", "，",
                { context.getString(R.string.char_card_strokes, it) },
            ) { strokeNames[it.ordinal] },
        )
        top.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        info.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    // a scroller no higher than its first [rows] rows (and half the next, to show there are more); in the
    // menu's ListView, which would take a vertical drag past the touch slop though it cannot scroll, the
    // drag kept here while there is more to scroll to (review 051-hw-reading M1)
    private class Capped(context: Context, private val rows: Int) : ScrollView(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                parent?.requestDisallowInterceptTouchEvent(canScrollVertically(1) || canScrollVertically(-1))
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val table = getChildAt(0) as? ViewGroup ?: return
            if (table.childCount <= rows) return
            val row = table.getChildAt(0).let { it.measuredHeight + (it.layoutParams as MarginLayoutParams).bottomMargin }
            setMeasuredDimension(measuredWidth, min(measuredHeight, row * rows + row / 2))
        }
    }

    private companion object {
        const val TEXT_SP = 16f
        const val PADDING = 12
        const val CELL_GAP = 12
        const val ROW_GAP = 2
        const val SPACE = "　"
    }
}
