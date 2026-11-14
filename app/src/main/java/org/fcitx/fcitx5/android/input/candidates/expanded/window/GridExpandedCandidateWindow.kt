/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded.window

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.expanded.GridPagingCandidateViewAdapter
import org.fcitx.fcitx5.android.input.candidates.expanded.SpanHelper
import org.fcitx.fcitx5.android.input.candidates.expanded.decoration.GridDecoration
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea

class GridExpandedCandidateWindow :
    BaseExpandedCandidateWindow<GridExpandedCandidateWindow>() {

    private val gridSpanCount by lazy { spanCount(context) }

    companion object {
        /** The grid's columns: 横屏's setting where [context]'s window is 横屏 (KeyboardArea), a wide one upright too. */
        fun spanCount(context: Context): Int = AppPrefs.getInstance().keyboard.run {
            if (KeyboardArea.landscape(context))
                expandedCandidateGridSpanCountLandscape
            else
                expandedCandidateGridSpanCount
        }.getValue()
    }

    override val adapter by lazy {
        object : GridPagingCandidateViewAdapter(theme) {
            override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                bindCandidateUiViewHolder(holder)
            }

            override fun onViewRecycled(holder: CandidateViewHolder) {
                recycleCandidateViewHolder(holder)
                super.onViewRecycled(holder)
            }
        }
    }

    override val layoutManager by lazy {
        GridLayoutManager(context, gridSpanCount).apply {
            spanSizeLookup = SpanHelper(adapter, this)
        }
    }

    override fun newDivider() = GridDecoration(dividerDrawable)

    override fun firstFullyVisible() = layoutManager.findFirstCompletelyVisibleItemPosition()

    override fun lastFullyVisible() = layoutManager.findLastCompletelyVisibleItemPosition()
}
