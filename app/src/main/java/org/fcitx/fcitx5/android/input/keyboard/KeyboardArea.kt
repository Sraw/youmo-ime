/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemes
import org.fcitx.fcitx5.android.scene.theme.KeyboardSizes as ThemeSizes
import org.fcitx.fcitx5.android.utils.windowManager
import splitties.dimensions.dp

/**
 * The docked keyboard's keys' area as the size settings and the theme make it ([KeyboardSizes]), px:
 * what InputView sizes the keys to, and what a settings page's key caps are made at (KeyCaps), so a
 * cap is the keyboard's key.
 */
object KeyboardArea {

    /**
     * The sizes the theme in use sets (`keyboard { height; side-padding; bottom-padding }`, 060-render-p3
     * T5); a test's own. A theme 3.0's and the built-in themes' set none, so they look as they did.
     */
    @VisibleForTesting
    internal var themeSizes: () -> ThemeSizes = {
        val theme = ThemeManager.activeTheme
        SceneThemes.of(ThemeManager.shownLook(theme), theme.name, theme.isDark)?.keyboardSizes ?: ThemeSizes()
    }

    /** The three settings, each following the theme or not, and the theme's sizes. */
    fun sizes(): KeyboardSizes {
        // first: the theme manager's preferences moved the sizes to following (ThemePrefs.migrateSizeFollow)
        val theme = themeSizes()
        val keyboard = AppPrefs.getInstance().keyboard
        fun setting(follow: ManagedPreference.PBool, portrait: ManagedPreference.PInt, landscape: ManagedPreference.PInt) =
            KeyboardSizes.Setting(follow.getValue(), portrait.getValue(), landscape.getValue())
        return KeyboardSizes(
            setting(keyboard.keyboardHeightFollowTheme, keyboard.keyboardHeightPercent, keyboard.keyboardHeightPercentLandscape),
            setting(keyboard.keyboardSidePaddingFollowTheme, keyboard.keyboardSidePadding, keyboard.keyboardSidePaddingLandscape),
            setting(keyboard.keyboardBottomPaddingFollowTheme, keyboard.keyboardBottomPadding, keyboard.keyboardBottomPaddingLandscape),
            keyboard.bottomFlush,
            theme,
        )
    }

    private val landscape: Context.() -> Boolean = { resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }

    /** The keyboard's height asked for (键盘高度, over the screen height its base says). */
    fun requestedHeight(ctx: Context): Int {
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
        return sizes().heightPx(base, ctx.landscape())
    }

    fun sidePadding(ctx: Context): Int = ctx.dp(sizes().sidePaddingDp(ctx.landscape()).toFloat()).toInt()

    fun bottomPadding(ctx: Context): Int = ctx.dp(sizes().bottomPaddingDp(ctx.landscape()).toFloat()).toInt()

    /** 贴底: no room left for the navigation bar ([KeyboardSizes.flush]). */
    fun bottomFlush(): Boolean = sizes().flush

    /** The keys' height under the bar and a code row of [codeRowDp], [handle] a docked drag handle: capped ([KeyboardHeightCap]). */
    fun keysHeight(ctx: Context, codeRowDp: Int, handle: Int = 0): Int = KeyboardHeightCap.keysHeight(
        requestedHeight(ctx),
        ctx.resources.displayMetrics.heightPixels,
        ctx.dp(KawaiiBarComponent.heightDp(ctx) + codeRowDp),
        bottomPadding(ctx),
        handleHeight = handle,
    )

    /** The keys' width docked, between the side paddings. */
    fun keysWidth(ctx: Context): Int = ctx.resources.displayMetrics.widthPixels - 2 * sidePadding(ctx)
}
