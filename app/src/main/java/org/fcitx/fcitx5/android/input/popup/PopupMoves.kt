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

/**
 * A theme's popups moving (060-render-p3 A2): a popup, a bubble or a menu coming up as its `appear`
 * says, a popup or a bubble going in its `transition`'s fade. The view's own properties alone
 * (alpha, scale, translation: its RenderNode's, nothing drawn again), by its [View.animate].
 */
internal object PopupMoves {
    /** `scale`'s size it grows from; `rise`'s way up it comes, dp. */
    const val SCALE_FROM = 0.6f
    const val RISE_DP = 12

    /**
     * [view] coming up as [a] says, about [pivotX], [pivotY] (the key's middle, the view's pixels):
     * faded in, and grown from [SCALE_FROM] or risen [RISE_DP].
     */
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

    /** [view] faded out in [t], then [gone]; [gone] at once where it may not move now. */
    fun go(view: View, t: Transition, gone: () -> Unit) {
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(t.ms.toLong()).setInterpolator(KeyMotionSwitch.curve(t)).withEndAction(gone).start()
    }

    /** [view] still and whole: what a move left of it undone (a bubble taken again from those kept). */
    fun settle(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationY = 0f
    }
}
