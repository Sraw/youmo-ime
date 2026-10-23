/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded.window

import android.content.res.Configuration
import androidx.recyclerview.widget.GridLayoutManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.expanded.GridPagingCandidateViewAdapter
import org.fcitx.fcitx5.android.input.candidates.expanded.SpanHelper
import org.fcitx.fcitx5.android.input.candidates.expanded.decoration.GridDecoration

class GridExpandedCandidateWindow :
    BaseExpandedCandidateWindow<GridExpandedCandidateWindow>() {

    private val gridSpanCount by lazy {
        AppPrefs.getInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                expandedCandidateGridSpanCount
            else
                expandedCandidateGridSpanCountLandscape
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
