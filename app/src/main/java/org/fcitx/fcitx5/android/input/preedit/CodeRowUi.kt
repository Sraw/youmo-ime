/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.content.Context
import android.text.SpannedString
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.buildSpannedString
import androidx.core.view.doOnPreDraw
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

/**
 * The row above the candidates for the code being typed ([PreeditComponent]): [PreeditUi]'s line
 * below put after the one above, for a row of one line's height ([CodeRow]); scrolled to keep the
 * cursor in sight, and a tap on it is [onTap]'s. The 14 or 18 keys' syllables ([syllables]) share the row
 * at its right end, in a part of their own: a tap on either is never the other's.
 */
class CodeRowUi(ctx: Context, theme: Theme, setupTextView: TextView.() -> Unit) :
    PreeditUi(ctx, theme, setupTextView) {

    /** Where in the preedit a tap on the row puts the cursor, in code points (FcitxAPI.moveCursor). */
    var onTap: ((Int) -> Unit)? = null

    private val row = HorizontalScrollView(ctx).apply {
        id = R.id.code_row_text
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        // a tap past the end of short text is still on it: the cursor to its end
        isFillViewport = true
        add(upView, lParams(wrapContent, matchParent))
    }

    /** The syllables to take (拼音选择栏) at the row's right end, the 14 or 18 keys': gone else, the code taking the whole row. */
    val syllables = SyllableStrip(ctx, theme)

    override val root: View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        add(row, LinearLayout.LayoutParams(0, matchParent, 1f))
        add(syllables, LinearLayout.LayoutParams(wrapContent, matchParent))
    }

    // what the line above shows: the length of aux and the preedit
    private var auxShown = 0
    private var preeditShown = ""
    private var revealing = false

    init {
        val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = preeditShown.isNotEmpty()

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val layout = upView.layout ?: return false
                val offset = layout.getOffsetForHorizontal(0, e.x - upView.totalPaddingLeft)
                onTap?.invoke(CodeRow.tapAt(offset, cursorShown, auxShown, preeditShown))
                return true
            }
        })
        upView.setOnTouchListener { _, e -> taps.onTouchEvent(e) }
    }

    override fun lines(above: SpannedString, below: SpannedString): Pair<CharSequence, CharSequence> {
        val line = if (below.isEmpty()) above else buildSpannedString { append(above); append("  "); append(below) }
        return line to ""
    }

    override fun update(inputPanel: FcitxEvent.InputPanelEvent.Data) {
        super.update(inputPanel)
        auxShown = inputPanel.auxUp.length
        preeditShown = inputPanel.preedit.toString()
        // once laid out with the new text; one waiting at a time, while the window draws nothing
        if (visible && !revealing) {
            revealing = true
            upView.doOnPreDraw {
                revealing = false
                reveal()
            }
        }
    }

    // the row scrolled to show the cursor with some of the text around it, the end if it is there
    private fun reveal() {
        val layout = upView.layout ?: return
        val x = if (cursorShown < 0) upView.width else upView.totalPaddingLeft + layout.getPrimaryHorizontal(cursorShown).toInt()
        // the keyboard's side padding is on the root, outside the row (BoardFollowers.place)
        val seen = row.width
        val to = CodeRow.scrollFor(x, ctx.dp(REVEAL_MARGIN), row.scrollX, seen) ?: return
        row.scrollTo(to, 0)
    }

    companion object {
        private const val REVEAL_MARGIN = 32
    }
}
