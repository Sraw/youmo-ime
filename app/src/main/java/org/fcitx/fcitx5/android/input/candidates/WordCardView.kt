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
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.reading.WordCard
import org.fcitx.fcitx5.android.input.popup.CharCardLayout
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import splitties.dimensions.dp
import splitties.resources.styledDrawable

/** TalkBack reads the word as one sentence ([WordCard.spoken]), each row as one, the buttons each on their own. */
@SuppressLint("ViewConstructor")
class WordCardView(
    context: Context,
    val card: WordCard,
    /** In pixels. */
    textSize: Float,
    twoColumns: Boolean,
    accent: Int,
    buttons: View?,
    pick: (Int) -> Unit,
) : LinearLayout(context) {

    private val secondary = context.styledColorOrDefault(android.R.attr.textColorSecondary, Color.GRAY)

    val head: LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(cardText(card.text, accent).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * CharCardLayout.WORD_SCALE)
            includeFontPadding = false
        })
        // in landscape, low, one line of the two
        val readings = listOf(ReadingStyle.PINYIN, ReadingStyle.ZHUYIN).map(card::line).filter { it.isNotEmpty() }
        (if (twoColumns) listOf(readings.joinToString(SPACE)).filter { it.isNotEmpty() } else readings).forEach { addView(cardText(it)) }
        contentDescription = card.spoken(cardWords(context))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
    }

    val rows: List<TableRow>

    private val table = TableLayout(context).apply {
        setColumnShrinkable(RADICAL_COLUMN, true)
    }

    init {
        val words = cardWords(context)
        val strokes = { n: Int -> context.getString(R.string.char_card_strokes, n) }
        rows = card.rows.mapIndexed { i, r ->
            TableRow(context).apply {
                minimumHeight = dp(CharCardLayout.MIN_ROW_DP)
                gravity = Gravity.CENTER_VERTICAL
                background = context.styledDrawable(android.R.attr.selectableItemBackground)
                addView(cardText(r.text, accent), cell())
                addView(cardText(r.pinyin), cell())
                addView(cardText(r.zhuyin), cell())
                addView(cardText(listOfNotNull(r.radical, r.strokes?.let(strokes)).joinToString(SPACE), secondary), cell())
                addView(cardText(CHEVRON, secondary), cell())
                contentDescription = r.spoken(words)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                for (c in 0 until childCount) getChildAt(c).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setOnClickListener { pick(i) }
                tapsTo(context.getString(R.string.card_see_char), button = false)
            }.also { table.addView(it, TableLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        }
        orientation = if (twoColumns) HORIZONTAL else VERTICAL
        val pad = dp(PADDING)
        setPadding(pad, pad, pad, pad)
        val scroll = CappedScroll(context, CharCardLayout.wordRows(twoColumns)).apply {
            isVerticalScrollBarEnabled = true
            addView(table)
        }
        val left = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(head, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(View(context).apply { setBackgroundColor(secondary); alpha = DIVIDER_ALPHA }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(ROW_GAP * 2)
                bottomMargin = dp(ROW_GAP * 2)
            })
            addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (twoColumns) {
            addView(left, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            buttons?.let { addView(it, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(CELL_GAP * 2); gravity = Gravity.BOTTOM }) }
        } else {
            addView(left, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            buttons?.let { addView(it, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ROW_GAP * 2) }) }
        }
    }

    private fun cell() = TableRow.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginEnd = dp(CELL_GAP)
    }

    private companion object {
        const val PADDING = 12
        const val CELL_GAP = 12
        const val ROW_GAP = 2
        const val RADICAL_COLUMN = 3
        const val DIVIDER_ALPHA = 0.3f
        const val SPACE = "　"
        const val CHEVRON = "›"
    }
}
