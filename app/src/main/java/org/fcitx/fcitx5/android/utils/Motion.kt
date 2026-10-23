/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.provider.Settings
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.motion.Motion

/**
 * [Motion.Settings] as they are now: Android's 移除动画 and the keyboard's own 关闭动画. Read each
 * time an animation starts, either changing while the keyboard is up.
 */
fun motionSettings(): Motion.Settings = Motion.Settings(
    // unset on many devices, which is 1: getGlobalSettings's 0 would read as 移除动画
    systemScale = Settings.Global.getFloat(appContext.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
    disabled = AppPrefs.getInstance().advanced.disableAnimation.getValue(),
)

private val enterCurve = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
private val exitCurve = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
private val moveCurve = PathInterpolator(0.2f, 0f, 0f, 1f)

/** Material's emphasized decelerate, emphasized accelerate and standard curves. */
val Motion.Curve.interpolator: Interpolator
    get() = when (this) {
        Motion.Curve.Enter -> enterCurve
        Motion.Curve.Exit -> exitCurve
        Motion.Curve.Move -> moveCurve
    }
