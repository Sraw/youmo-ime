/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.utils.alpha
import splitties.dimensions.dp
import kotlin.math.max

/**
 * The picker's items in a list scrolled up and down: the emoji a section for each category, the
 * symbol panel the one category picked beside it. It replaced pages swiped sideways, which said
 * nothing of there being more: the list shows a sliver of the row below, fades at its edges and
 * has a scroll bar.
 */
@SuppressLint("ViewConstructor")
class PickerGridView(context: Context, val theme: Theme, val density: Density) : RecyclerView(context) {

    enum class Density(
        val columnCount: Int,
        val rowCount: Int,
        val textSize: Float,
        val autoScale: Boolean,
        /** a header over each category's items; the symbol panel names them at its side */
        val headers: Boolean = true
    ) {
        // the symbol panel: four keys a row and three rows, as Sogou's; six by four were crowded,
        // and four by four too flat
        Panel(4, 3, 24f, false, headers = false),

        // emoji
        Medium(7, 3, 23.7f, false),

        // emoticon
        Low(4, 3, 19f, true);

        /** how many recently used ones are kept: their section is one screen of the list */
        val recentLimit get() = columnCount * rowCount
    }

    val gridLayoutManager = GridLayoutManager(context, density.columnCount)

    val headerHeight = dp(24)

    /** [Density.rowCount] rows (under a header, if any), and a sliver of the next one */
    var rowHeight = 0
        private set

    init {
        layoutManager = gridLayoutManager
        itemAnimator = null
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(12))
        addItemDecoration(ScrollBar())
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // at least a pixel: a height of -1 or -2 would read as match or wrap
        val header = if (density.headers) headerHeight else 0
        val row = ((h - header) / (density.rowCount + SLIVER)).toInt().coerceAtLeast(1)
        if (row == rowHeight) return
        rowHeight = row
        // keys bound before, at another height (or none, in a measure pass before the first size)
        post { adapter?.notifyDataSetChanged() }
    }

    /** Drawn by hand: a View made in code gets no scroll bar drawable from the theme */
    private inner class ScrollBar : ItemDecoration() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.keyTextColor.alpha(0.25f) }
        private val thickness = dp(3f)
        private val inset = dp(2f)
        private val minLength = dp(24f)
        private val rect = RectF()

        override fun onDrawOver(c: Canvas, parent: RecyclerView, state: State) {
            val range = computeVerticalScrollRange()
            val extent = computeVerticalScrollExtent()
            if (range <= extent) return
            val length = max(height.toFloat() * extent / range, minLength)
            val top = (height - length) * computeVerticalScrollOffset() / (range - extent)
            rect.set(width - inset - thickness, top, width - inset, top + length)
            c.drawRoundRect(rect, thickness / 2, thickness / 2, paint)
        }
    }

    companion object {
        private const val SLIVER = 0.3f
    }
}
