/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import androidx.navigation.toRoute
import androidx.navigation.ui.R
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.input.motion.Motion
import splitties.views.bottomPadding

@Suppress("FunctionName")
fun DarkenColorFilter(percent: Int): ColorFilter {
    val value = percent * 255 / 100
    return PorterDuffColorFilter(Color.argb(value, 0, 0, 0), PorterDuff.Mode.SRC_OVER)
}

fun SeekBar.setOnChangeListener(listener: SeekBar.(progress: Int) -> Unit) {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            listener.invoke(seekBar, progress)
        }
    })
}

fun <T : Any> Fragment.navigateWithAnim(route: T) {
    findNavController().navigateWithAnim(route)
}

inline fun <reified T : Any> Fragment.lazyRoute() = lazy {
    findNavController().getBackStackEntry<T>().toRoute<T>()
}

inline fun <reified T : Activity> Context.startActivity(setupIntent: Intent.() -> Unit = {}) {
    startActivity(Intent(this, T::class.java).apply(setupIntent))
}

inline fun <reified T : Activity> Fragment.startActivity(setupIntent: Intent.() -> Unit = {}) {
    requireContext().startActivity<T>(setupIntent)
}

fun <T : Any> NavController.navigateWithAnim(route: T) {
    navigate(route, navOptions {
        anim {
            enter = R.animator.nav_default_enter_anim
            exit = R.animator.nav_default_exit_anim
            popEnter = R.animator.nav_default_pop_enter_anim
            popExit = R.animator.nav_default_pop_exit_anim
        }
    })
}

fun RecyclerView.applyNavBarInsetsBottomPadding() {
    clipToPadding = false
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
        windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars()).also {
            bottomPadding = it.bottom
        }
        windowInsets
    }
    // a list added after insets went out (a ViewPager2 page made on first pick) never gets them: its rows sat under the nav bar
    doOnAttach(ViewCompat::requestApplyInsets)
}

val AlertDialog.positiveButton: Button
    get() = getButton(DialogInterface.BUTTON_POSITIVE)

/**
 * Change positive button listener **AFTER** [AlertDialog.show] has been called.
 *
 * In the listener: `true` to dismiss the dialog; `false` to keep the dialog open.
 */
fun AlertDialog.onPositiveButtonClick(l: AlertDialog.() -> Boolean?): AlertDialog {
    positiveButton.setOnClickListener {
        if (l.invoke(this) == true) dismiss()
    }
    return this
}

/** Grown from [POP_FROM] about the pivot (its own pixels) and faded in; nothing with animation off; leaving is not animated. */
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
