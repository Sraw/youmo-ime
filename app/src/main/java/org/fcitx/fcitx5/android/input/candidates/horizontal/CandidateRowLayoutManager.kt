/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.graphics.Rect
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The horizontal bar's one row of candidates: as many as fit, each at its own width and at least
 * [minWidth], then, when [grows] says so for the count shown, the leftover shared out among them (a lone word wider than the bar shrunk to it).
 * Placed as FlexboxLayoutManager's first line was (wrap, flex-start, stretch, every flexGrow 1): its
 * sizes, its sharing and rounding, its RTL; without its lines past the first or its caches, the most of
 * a key's layout while typing (design 060-perf-cands).
 */
class CandidateRowLayoutManager : RecyclerView.LayoutManager() {

    /** The least width of a candidate, px. */
    var minWidth = 0

    /** Whether the [Int] candidates shown share the leftover width. */
    var grows: (Int) -> Boolean = { true }

    /** Called with the count shown after each layout. */
    var onLaidOut: (Int) -> Unit = {}

    // a child's decorations, read in each layout
    private val decor = Rect()

    override fun generateDefaultLayoutParams() =
        RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)

    // as FlexboxLayoutManager: a bar not of an exact width is as wide as its row
    override fun isAutoMeasureEnabled() = true

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        detachAndScrapAttachedViews(recycler)
        if (state.itemCount == 0) return
        val padH = paddingLeft + paddingRight
        var used = padH
        var shown = 0
        for (i in 0 until state.itemCount) {
            val view = recycler.getViewForPosition(i)
            val lp = view.layoutParams as RecyclerView.LayoutParams
            val margins = lp.leftMargin + lp.rightMargin
            view.measure(getChildMeasureSpec(width, widthMode, padH + margins, lp.width, false), heightSpec(lp))
            if (view.measuredWidth < minWidth) {
                view.measure(exactly(minWidth), exactly(view.measuredHeight))
            }
            calculateItemDecorationsForChild(view, decor)
            val w = view.measuredWidth + margins + decor.left + decor.right
            // the first always shown, however wide; no width to fit (UNSPECIFIED): all in the row
            if (shown > 0 && widthMode != MeasureSpec.UNSPECIFIED && width < used + w) {
                recycler.recycleView(view)
                break
            }
            addView(view)
            used += w
            shown++
        }
        val main = when (widthMode) {
            MeasureSpec.EXACTLY -> width
            MeasureSpec.UNSPECIFIED -> used
            else -> min(used, width)
        }
        if (used < main && grows(shown)) share(main, used, shown, grow = true)
        // a lone word wider than the bar shrunk to it
        else if (used > main) share(main, used, shown, grow = false)
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        var start = if (rtl) width - paddingRight else paddingLeft
        for (i in 0 until shown) {
            val view = getChildAt(i)!!
            val lp = view.layoutParams as RecyclerView.LayoutParams
            val top = paddingTop + getTopDecorationHeight(view) + lp.topMargin
            val w = view.measuredWidth
            if (rtl) {
                start -= lp.rightMargin + getRightDecorationWidth(view)
                view.layout(start - w, top, start, top + view.measuredHeight)
                start -= w + lp.leftMargin + getLeftDecorationWidth(view)
            } else {
                start += lp.leftMargin + getLeftDecorationWidth(view)
                view.layout(start, top, start + w, top + view.measuredHeight)
                start += w + lp.rightMargin + getRightDecorationWidth(view)
            }
        }
    }

    /**
     * As FlexboxHelper.expandFlexItems / shrinkFlexItems with every flexGrow and flexShrink 1: each its
     * float share of the leftover or the overflow, the rounding carried, the last taking the rest; a word
     * shrunk below [minWidth] held there and the rest shrunk again.
     */
    private fun share(main: Int, used: Int, shown: Int, grow: Boolean) {
        val frozen = BooleanArray(shown)
        var line = used
        var free = shown
        while (free > 0 && (grow || main <= line)) {
            val before = line
            val unit = (if (grow) main - line else line - main) / free.toFloat()
            var again = false
            var carried = 0f
            for (i in 0 until shown) {
                val view = getChildAt(i)!!
                if (frozen[i]) continue
                line -= view.measuredWidth
                var raw = if (grow) view.measuredWidth + unit else view.measuredWidth - unit
                if (i == shown - 1) {
                    raw += carried
                    carried = 0f
                }
                var w = raw.roundToInt()
                if (!grow && w < minWidth) {
                    again = true
                    w = minWidth
                    frozen[i] = true
                    free--
                } else {
                    carried += raw - w
                    val carry = if (carried > 1.0) 1 else if (carried < -1.0) -1 else 0
                    w += carry
                    carried -= carry
                }
                view.measure(exactly(w), heightSpec(view.layoutParams as RecyclerView.LayoutParams))
                line += view.measuredWidth
            }
            if (!again || before == line) return
        }
    }

    override fun onLayoutCompleted(state: RecyclerView.State?) {
        super.onLayoutCompleted(state)
        onLaidOut(childCount)
    }

    private fun heightSpec(lp: RecyclerView.LayoutParams) =
        getChildMeasureSpec(height, heightMode, paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin, lp.height, false)

    private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
}
