/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded

import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.CandidateBarHeight
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent

abstract class GridPagingCandidateViewAdapter(theme: Theme) : PagingCandidateViewAdapter(theme) {

    companion object {
        // 20f here is chosen randomly, since we only care about the ratio
        private const val TEXT_SIZE = 20f
    }

    // 候选字号 scales the words, so the rows and the spans a word takes scale with it (design §6);
    // the input view, and with it this adapter, is made anew when the size changes
    private val textSp = ThemeManager.prefs.candidateTextSize.getValue()
    private val textScale = textSp / CandidateBarHeight.DEFAULT_TEXT_SP.toFloat()

    // cache measureWidth
    private val measuredWidths = object : LruCache<String, Float>(200) {
        private val cachedPaint = Paint().apply { textSize = TEXT_SIZE }
        private val cachedRect = Rect()
        override fun create(key: String): Float {
            cachedPaint.getTextBounds(key, 0, key.length, cachedRect)
            return cachedRect.width() / TEXT_SIZE
        }
    }

    fun measureWidth(position: Int): Float {
        // the span lookup reads ahead of what is shown: getItem would have Paging load for it
        val candidate = peek(position) ?: return 0f
        return measuredWidths[candidate.textWithComment()] * textScale
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        return super.onCreateViewHolder(parent, viewType).apply {
            itemView.apply {
                layoutParams = GridLayoutManager.LayoutParams(matchParent, dp(CandidateBarHeight.dp(textSp)))
            }
        }
    }
}