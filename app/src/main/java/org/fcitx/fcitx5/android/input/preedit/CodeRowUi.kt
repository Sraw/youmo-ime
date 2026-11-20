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
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.host.HostEvent
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

/**
 * The line below put after the one above, for a row of one line's height; scrolled to keep the cursor in
 * sight. The syllables share the row at its right end: a tap on either is never the other's. [wrap]: as
 * wide as its text, for the floating strip, which caps it.
 */
class CodeRowUi(ctx: Context, theme: Theme, wrap: Boolean = false, setupTextView: TextView.() -> Unit) :
    PreeditUi(ctx, theme, setupTextView) {

    /** In code points (HostApi.moveCursor). */
    var onTap: ((Int) -> Unit)? = null

    /** [notice] units of the line below (0 for none): a tap on it is [onNotice]'s, a screen reader's click on the row too. */
    var onNotice: (() -> Unit)? = null
    var notice = 0

    // as lines() made it and as drawn (the cursor's | before it)
    private var belowAt = 0
    private var belowShown = 0

    private val row = HorizontalScrollView(ctx).apply {
        id = R.id.code_row_text
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        // a tap past the end of short text is still on it: the cursor to its end
        isFillViewport = true
        add(upView, lParams(wrapContent, matchParent))
    }

    val syllables = SyllableStrip(ctx, theme)

    override val root: View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        // wrapped, the code shrinks, not the syllables
        add(row, LinearLayout.LayoutParams(if (wrap) wrapContent else 0, matchParent, 1f))
        add(syllables, LinearLayout.LayoutParams(wrapContent, matchParent))
    }

    private var auxShown = 0
    private var preeditShown = ""
    private var revealing = false

    init {
        val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = preeditShown.isNotEmpty() || notice > 0

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val layout = upView.layout ?: return false
                val offset = layout.getOffsetForHorizontal(0, e.x - upView.totalPaddingLeft)
                // an offset is the character edge nearest the tap: the notice's from before its first to after its last
                if (notice > 0 && offset in belowShown..belowShown + notice) {
                    onNotice?.invoke()
                    return true
                }
                if (preeditShown.isEmpty()) return false
                onTap?.invoke(CodeRow.tapAt(offset, cursorShown, auxShown, preeditShown))
                return true
            }
        })
        upView.setOnTouchListener { _, e -> taps.onTouchEvent(e) }
        // touches are the detector's; this is a screen reader's double tap
        upView.setOnClickListener { if (notice > 0) onNotice?.invoke() }
        upView.isClickable = false
    }

    override fun lines(above: SpannedString, below: SpannedString): Pair<CharSequence, CharSequence> {
        val line = if (below.isEmpty()) above else buildSpannedString { append(above); append(GAP); append(below) }
        belowAt = above.length + GAP.length
        return line to ""
    }

    override fun update(inputPanel: HostEvent.InputPanel) {
        super.update(inputPanel)
        upView.isClickable = notice > 0
        belowShown = belowAt + if (cursorShown in 0..belowAt) 1 else 0
        auxShown = inputPanel.auxUp.length
        preeditShown = inputPanel.preedit.toString()
        // one waiting at a time, while the window draws nothing
        if (visible && !revealing) {
            revealing = true
            upView.doOnPreDraw {
                revealing = false
                reveal()
            }
        }
    }

    private fun reveal() {
        val layout = upView.layout ?: return
        val x = if (cursorShown < 0) upView.width else upView.totalPaddingLeft + layout.getPrimaryHorizontal(cursorShown).toInt()
        // the keyboard's side padding is on the root, outside the row
        val seen = row.width
        val to = CodeRow.scrollFor(x, ctx.dp(REVEAL_MARGIN), row.scrollX, seen) ?: return
        row.scrollTo(to, 0)
    }

    companion object {
        private const val REVEAL_MARGIN = 32

        private const val GAP = "  "
    }
}
