/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import org.fcitx.fcitx5.android.input.keyboard.SplitKeyboard

/**
 * The docked board moving across by its setting (单手模式, 分体时其他面板, from the status area or
 * their arrows): slid to a side or across; back to the whole width, widened from where it was (slid,
 * a board at the left would not move at all, one at the right leave a gap). A window or a layout
 * that puts it at a side is never animated: they switch on every key, as layouts do.
 */
object BoardMove {

    enum class Kind { None, Slide, Widen }

    /** Moved [bySetting] from [was] to [now] (null the whole width), [placed] before at all. */
    fun kind(bySetting: Boolean, placed: Boolean, was: SplitKeyboard.Dock?, now: SplitKeyboard.Dock?): Kind = when {
        !bySetting || !placed || was == now -> Kind.None
        now == null -> Kind.Widen
        else -> Kind.Slide
    }

    /** Where a view [left] is drawn along x, moved by [translationX] and scaled by [scaleX] about [pivotX]: mid-move, a move from there. */
    fun drawnLeft(left: Float, translationX: Float, pivotX: Float, scaleX: Float): Float = left + translationX + pivotX * (1f - scaleX)

    /** How wide a view [width] is drawn, scaled by [scaleX]. */
    fun drawnWidth(width: Float, scaleX: Float): Float = width * scaleX
}
