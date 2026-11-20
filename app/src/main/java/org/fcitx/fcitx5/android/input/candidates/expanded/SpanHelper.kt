/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import androidx.recyclerview.widget.GridLayoutManager
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class SpanHelper(
    private val adapter: GridPagingCandidateViewAdapter,
    private val manager: GridLayoutManager
) : GridLayoutManager.SpanSizeLookup() {

    data class ItemLayout(val spanIndex: Int, val spanSize: Int, val groupIndex: Int)

    private val layout = ArrayList<ItemLayout>()

    private fun getMinSpanSize(position: Int) = min(
        // approximately three characters or one Chinese characters per span
        // at least one span for each word, in case measureWidth got zero due to whatever font issues
        max(1, ceil(adapter.measureWidth(position) / 1.5).toInt()), manager.spanCount
    )

    private fun invalidate() {
        layout.clear()
    }

    override fun invalidateSpanIndexCache() {
        invalidate()
        super.invalidateSpanIndexCache()
    }

    private fun layoutItem(position: Int): ItemLayout {
        if (layout.size > position) return layout[position]
        val spanCount = manager.spanCount
        var span = 0
        var group = 0
        layout.lastOrNull()?.also { last ->
            val lastSpan = last.spanIndex + last.spanSize
            if (lastSpan == spanCount) {
                // the last known item is at the end of its group
                group = last.groupIndex + 1
            } else {
                span = lastSpan
                group = last.groupIndex
            }
        }
        val itemCount = adapter.itemCount
        // layout a row of items each time
        val batchEnd = min(spanCount * (position / spanCount + 1), adapter.itemCount)
        for (i in layout.size until batchEnd) {
            var size = getMinSpanSize(i)
            val nextSize = if (i + 1 < itemCount) getMinSpanSize(i + 1) else null
            // we still have rest span spaces,
            // but it can't hold the next item
            if (nextSize != null && span + size + nextSize > spanCount) {
                // stretch this item to fill the line
                size = spanCount - span
            }
            layout.add(ItemLayout(span, size, group))
            span += size
            if (span == spanCount) {
                span = 0
                group++
            }
        }
        return layout[position]
    }

    override fun getSpanIndex(position: Int, spanCount: Int): Int {
        return layoutItem(position).spanIndex
    }

    override fun getSpanGroupIndex(adapterPosition: Int, spanCount: Int): Int {
        return layoutItem(adapterPosition).groupIndex
    }

    override fun getSpanSize(position: Int): Int {
        return layoutItem(position).spanSize
    }
}