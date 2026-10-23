/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded.window

import android.view.ViewGroup
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.CandidateBarHeight
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.expanded.PagingCandidateViewAdapter
import org.fcitx.fcitx5.android.input.candidates.expanded.decoration.FlexboxHorizontalDecoration
import splitties.dimensions.dp
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

// rows as tall as the bar: 候选字号 sizes the words here too (design §6)
private val rowDp get() = CandidateBarHeight.dp(ThemeManager.prefs.candidateTextSize.getValue())

class FlexboxExpandedCandidateWindow :
    BaseExpandedCandidateWindow<FlexboxExpandedCandidateWindow>() {

    override val adapter by lazy {
        object : PagingCandidateViewAdapter(theme) {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
                return super.onCreateViewHolder(parent, viewType).apply {
                    itemView.apply {
                        minimumWidth = dp(40)
                        setPaddingDp(10, 0, 10, 0)
                        layoutParams = FlexboxLayoutManager.LayoutParams(wrapContent, dp(rowDp))
                            .apply { flexGrow = 1f }
                    }
                }
            }

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
        FlexboxLayoutManager(context).apply {
            justifyContent = JustifyContent.SPACE_AROUND
            alignItems = AlignItems.FLEX_START
        }
    }

    override fun newDivider() = FlexboxHorizontalDecoration(dividerDrawable)

    override fun firstFullyVisible() = layoutManager.findFirstCompletelyVisibleItemPosition()

    override fun lastFullyVisible() = layoutManager.findLastCompletelyVisibleItemPosition()
}
