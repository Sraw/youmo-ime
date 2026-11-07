/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams

// out of BaseKeyboard (060-render-p2 S4: its scene's part made it too large a class), as it was there

/**
 * A row narrower than the keyboard: its outermost keys shown take the room left at the edges,
 * their labels where they were. Only keys shown count, so a key hidden (the 双拼 `;`) leaves
 * the row's ends widened again. [widens]: whether it may (expandKeypressArea, and no split row,
 * which fills the keyboard by itself: SplitKeyboard.row).
 */
internal fun fitRow(keyViews: List<View>, widths: List<Float>, widens: Boolean) {
    val shown = keyViews.indices.filter { keyViews[it].visibility != View.GONE }.ifEmpty { return }
    // back to their own widths first: fitted before, they may no longer be at an end
    keyViews.forEachIndexed { i, view ->
        if (view !is KeyView) return@forEachIndexed
        view.updateLayoutParams<ConstraintLayout.LayoutParams> { matchConstraintPercentWidth = widths[i] }
        view.layoutMarginLeft = 0f
        view.layoutMarginRight = 0f
    }
    // 0f means fill remaining space, thus does not need expanding
    val totalWidth = shown.sumOf { (widths[it].takeIf { w -> w != 0f } ?: 1f).toDouble() }.toFloat()
    if (!widens || totalWidth >= 1f) return
    val free = (1f - totalWidth) / 2f
    val first = shown.first()
    val last = shown.last()
    // a gap at an edge is no key to widen
    (keyViews[first] as? KeyView)?.apply {
        updateLayoutParams<ConstraintLayout.LayoutParams> { matchConstraintPercentWidth += free }
        layoutMarginLeft = free / (widths[first] + free)
    }
    (keyViews[last] as? KeyView)?.apply {
        updateLayoutParams<ConstraintLayout.LayoutParams> { matchConstraintPercentWidth += free }
        layoutMarginRight = free / (widths[last] + free)
    }
}
