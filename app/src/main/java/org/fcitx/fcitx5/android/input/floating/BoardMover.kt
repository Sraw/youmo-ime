/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.floating

import android.view.View
import androidx.core.view.doOnPreDraw
import org.fcitx.fcitx5.android.input.motion.Motion
import org.fcitx.fcitx5.android.utils.interpolator
import org.fcitx.fcitx5.android.utils.motionSettings

/** From where they are drawn now, a move under way too. Hidden, or animation off, they are just there. */
class BoardMover(private val keyboard: View, private val views: List<View>) {

    /** Called before the views are laid out anew for the board's new place. */
    fun move(kind: BoardMove.Kind) {
        if (kind == BoardMove.Kind.None) return
        val ms = Motion.Long.enter(motionSettings())
        if (ms == 0L || !keyboard.isLaidOut || !keyboard.isShown) return
        val wasLeft = views.map { BoardMove.drawnLeft(it.left.toFloat(), it.translationX, it.pivotX, it.scaleX) }
        val wasWidth = views.map { BoardMove.drawnWidth(it.width.toFloat(), it.scaleX) }
        keyboard.doOnPreDraw {
            views.forEachIndexed { i, v ->
                v.animate().cancel()
                if (kind == BoardMove.Kind.Widen) {
                    v.translationX = 0f
                    v.scaleX = FloatingKeyboard.startScale(wasWidth[i], v.width.toFloat())
                    v.pivotX = FloatingKeyboard.pivot(wasLeft[i], wasWidth[i], v.left.toFloat(), v.width.toFloat())
                } else {
                    v.scaleX = 1f
                    v.translationX = wasLeft[i] - v.left
                }
                v.animate().translationX(0f).scaleX(1f).setDuration(ms).setInterpolator(Motion.Curve.Move.interpolator).start()
            }
        }
    }
}
