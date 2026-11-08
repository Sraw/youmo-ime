/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.utils.windowManager
import splitties.dimensions.dp

/**
 * The docked keyboard's keys' area as the size settings make it, px: what InputView sizes the keys to,
 * and what a settings page's key caps are made at (KeyCaps), so a cap is the keyboard's key.
 */
object KeyboardArea {

    private val landscape: Context.() -> Boolean = { resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }

    /** The keyboard's height asked for (键盘高度, over the screen height its base says). */
    fun requestedHeight(ctx: Context): Int {
        val keyboard = AppPrefs.getInstance().keyboard
        val base = when (AppPrefs.getInstance().advanced.keyboardHeightPercentBase.getValue()) {
            KeyboardHeightPercentBase.DisplayMetrics -> ctx.resources.displayMetrics.heightPixels
            KeyboardHeightPercentBase.RealSize -> Point().also {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ctx.display
                } else {
                    ctx.windowManager.defaultDisplay
                }!!.getRealSize(it)
            }.y
        }
        val percent = (if (ctx.landscape()) keyboard.keyboardHeightPercentLandscape else keyboard.keyboardHeightPercent).getValue()
        return base * percent / 100
    }

    fun sidePadding(ctx: Context): Int {
        val keyboard = AppPrefs.getInstance().keyboard
        return ctx.dp((if (ctx.landscape()) keyboard.keyboardSidePaddingLandscape else keyboard.keyboardSidePadding).getValue())
    }

    fun bottomPadding(ctx: Context): Int {
        val keyboard = AppPrefs.getInstance().keyboard
        return ctx.dp((if (ctx.landscape()) keyboard.keyboardBottomPaddingLandscape else keyboard.keyboardBottomPadding).getValue())
    }

    /** The keys' height under the bar and a code row of [codeRowDp], [handle] a docked drag handle: capped ([KeyboardHeightCap]). */
    fun keysHeight(ctx: Context, codeRowDp: Int, handle: Int = 0): Int = KeyboardHeightCap.keysHeight(
        requestedHeight(ctx),
        ctx.resources.displayMetrics.heightPixels,
        ctx.dp(KawaiiBarComponent.heightDp() + codeRowDp),
        bottomPadding(ctx),
        handleHeight = handle,
    )

    /** The keys' width docked, between the side paddings. */
    fun keysWidth(ctx: Context): Int = ctx.resources.displayMetrics.widthPixels - 2 * sidePadding(ctx)
}
