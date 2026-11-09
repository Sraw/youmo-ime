/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.expanded.decoration

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import splitties.dimensions.dp

/**
 * Room for a divider on the item's trailing side, as the layout direction has it, and [gap] px more (a
 * theme's room between two candidates, T6a), the divider in its middle.
 */
internal fun Rect.setTrailingDivider(parent: RecyclerView, drawable: Drawable, gap: Int = 0) {
    when (parent.layoutDirection) {
        View.LAYOUT_DIRECTION_LTR -> right = drawable.intrinsicWidth + gap
        View.LAYOUT_DIRECTION_RTL -> left = drawable.intrinsicWidth + gap
        // should not reach here
        else -> set(0, 0, 0, 0)
    }
}

/** Draws [drawable] on [view]'s trailing side, shorter than the item, in the middle of the room [setTrailingDivider] keeps. */
internal fun drawTrailingDivider(c: Canvas, parent: RecyclerView, view: View, drawable: Drawable, gap: Int = 0) {
    val lp = view.layoutParams as RecyclerView.LayoutParams
    val left: Int
    val right: Int
    when (parent.layoutDirection) {
        View.LAYOUT_DIRECTION_LTR -> {
            left = view.right + lp.rightMargin + gap / 2
            right = left + drawable.intrinsicWidth
        }
        View.LAYOUT_DIRECTION_RTL -> {
            right = view.left + lp.leftMargin - gap / 2
            left = right - drawable.intrinsicWidth
        }
        else -> {
            // should not reach here
            left = view.left
            right = left + drawable.intrinsicWidth
        }
    }
    val top = view.top - lp.topMargin
    val bottom = view.bottom + lp.bottomMargin
    // make the divider shorter
    val vInset = parent.dp(8)
    drawable.setBounds(left, top + vInset, right, bottom - vInset)
    drawable.draw(c)
}
