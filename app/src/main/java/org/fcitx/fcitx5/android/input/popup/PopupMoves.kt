/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.view.View
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyMotionSwitch
import org.fcitx.fcitx5.android.input.motion.Motion
import org.fcitx.fcitx5.android.scene.theme.Appear
import org.fcitx.fcitx5.android.scene.theme.Transition
import org.fcitx.fcitx5.android.utils.interpolator
import splitties.dimensions.dp

/** The view's own properties alone (alpha, scale, translation: its RenderNode's, nothing drawn again). */
internal object PopupMoves {
    /** `rise`'s in dp. */
    const val SCALE_FROM = 0.6f
    const val RISE_DP = 12

    fun appear(view: View, a: Appear, pivotX: Float, pivotY: Float) {
        settle(view)
        view.pivotX = pivotX
        view.pivotY = pivotY
        view.alpha = 0f
        val anim = view.animate().alpha(1f).setDuration(a.ms.toLong()).setInterpolator(Motion.Curve.Enter.interpolator)
        when (a.kind) {
            "scale" -> {
                view.scaleX = SCALE_FROM
                view.scaleY = SCALE_FROM
                anim.scaleX(1f).scaleY(1f)
            }
            "rise" -> {
                view.translationY = view.dp(RISE_DP).toFloat()
                anim.translationY(0f)
            }
        }
        anim.start()
    }

    /** [gone] at once where it may not move now. */
    fun go(view: View, t: Transition, gone: () -> Unit) {
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(t.ms.toLong()).setInterpolator(KeyMotionSwitch.curve(t)).withEndAction(gone).start()
    }

    /** What a move left of it undone (a bubble taken again from those kept). */
    fun settle(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationY = 0f
        view.resetPivot()
    }
}
