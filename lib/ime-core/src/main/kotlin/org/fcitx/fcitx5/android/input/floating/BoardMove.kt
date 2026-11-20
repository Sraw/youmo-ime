/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard

/** The docked board moved by its setting (单手模式, 分体): slid; back to full width, widened from where it was (slid, a left board would not move). Windows and layouts never animate. */
object BoardMove {

    enum class Kind { None, Slide, Widen }

    /** [now] null: the whole width. */
    fun kind(bySetting: Boolean, placed: Boolean, was: SplitKeyboard.Dock?, now: SplitKeyboard.Dock?): Kind = when {
        !bySetting || !placed || was == now -> Kind.None
        now == null -> Kind.Widen
        else -> Kind.Slide
    }

    /** Mid-move, a move from there. */
    fun drawnLeft(left: Float, translationX: Float, pivotX: Float, scaleX: Float): Float = left + translationX + pivotX * (1f - scaleX)

    fun drawnWidth(width: Float, scaleX: Float): Float = width * scaleX
}
