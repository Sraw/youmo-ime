/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.view.View
import org.fcitx.fcitx5.android.input.motion.Motion

/**
 * A small thing coming up from where the finger is (a long press's characters, a candidate's menu):
 * grown from [POP_FROM] of its size about [pivotX], [pivotY] (its own pixels) and faded in, in
 * [Motion.Short]. Nothing with animation off. Its going is not animated: what was picked is in already.
 */
fun View.popIn(pivotX: Float, pivotY: Float, settings: Motion.Settings = motionSettings()) {
    val ms = Motion.Short.enter(settings)
    if (ms <= 0L) return
    this.pivotX = pivotX
    this.pivotY = pivotY
    alpha = 0f
    scaleX = POP_FROM
    scaleY = POP_FROM
    animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(ms).setInterpolator(Motion.Curve.Enter.interpolator).start()
}

private const val POP_FROM = 0.9f
