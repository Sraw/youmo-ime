/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import splitties.dimensions.dp
import kotlin.math.max

class ResponsiveThemeListView(context: Context) : RecyclerView(context) {

    var itemWidth = dp(128)
    // the card 92 dp, its name under it (ThemeNames.ROW_DP)
    var itemHeight = dp(92 + ThemeNames.ROW_DP)
    var minMargin = dp(16)

    private lateinit var grid: GridLayoutManager

    private var spacing: ThemeListItemDecoration? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        val spanCount = max(1, estimateSpanCount(w))
        if (!this::grid.isInitialized) {
            grid = object : GridLayoutManager(context, spanCount) {
                override fun generateDefaultLayoutParams() = LayoutParams(itemWidth, itemHeight)
            }
            layoutManager = grid
        } else {
            grid.spanCount = spanCount
        }
        // the spacing is worked out for a number of columns: a new one for the new count
        if (spacing?.spanCount != spanCount) {
            spacing?.let { removeItemDecoration(it) }
            spacing = ThemeListItemDecoration(itemWidth, spanCount).also { addItemDecoration(it) }
        } else {
            invalidateItemDecorations()
        }
    }

    private fun estimateSpanCount(width: Int) = (width - minMargin) / (itemWidth + minMargin)
}
