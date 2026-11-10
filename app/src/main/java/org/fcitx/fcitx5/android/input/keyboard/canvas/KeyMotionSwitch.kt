/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.scene.theme.Transition

/**
 * Whether a theme's keys may move this moment (060-render-p3 §4.1, A1): 键盘动效 on, 禁用动画 off,
 * the system's battery saver off and its animations not removed ([ValueAnimator.areAnimatorsEnabled]).
 * Asked at each press of a key that has motion, so a change takes at once; battery saver is kept from
 * its broadcast (PowerManager's is a call into the system: not on a keystroke).
 */
object KeyMotionSwitch {
    @Volatile
    private var powerSave = false
    // the application watched (one a process; a test's each its own)
    private var watching: Context? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            powerSave = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        }
    }

    fun on(context: Context): Boolean {
        val app = context.applicationContext
        if (watching !== app) watch(app)
        val prefs = AppPrefs.getInstance().advanced
        return prefs.keyboardMotion.getValue() && !prefs.disableAnimation.getValue() && !powerSave && ValueAnimator.areAnimatorsEnabled()
    }

    private fun watch(app: Context) {
        watching = app
        powerSave = app.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        ContextCompat.registerReceiver(app, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // CSS's curves (cubic-bezier), made once
    private val curves = mapOf(
        "linear" to LinearInterpolator(),
        "ease" to PathInterpolator(0.25f, 0.1f, 0.25f, 1f),
        "ease-in" to PathInterpolator(0.42f, 0f, 1f, 1f),
        "ease-out" to PathInterpolator(0f, 0f, 0.58f, 1f),
        "ease-in-out" to PathInterpolator(0.42f, 0f, 0.58f, 1f),
    )

    fun curve(t: Transition): TimeInterpolator = curves[t.easing] ?: curves.getValue("linear")
}
