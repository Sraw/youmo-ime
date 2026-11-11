/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Point
import android.os.Build
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemes
import org.fcitx.fcitx5.android.scene.theme.KeyboardSizes as ThemeSizes
import org.fcitx.fcitx5.android.utils.windowManager

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
        SceneThemes.of(theme, ThemeManager.shownLook(theme))?.keyboardSizes ?: ThemeSizes()
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

    /**
     * The screen the keyboard is laid out on, px: the input method window's, which is the display's whatever an
     * activity's window is (multi-window, split screen). [widthDp] the width as its configuration says it (the
     * split and floating widths read it); [realHeightPx] the display's whole height, 键盘高度's RealSize base.
     */
    data class Screen(
        val widthPx: Int,
        val heightPx: Int,
        val widthDp: Int,
        val density: Float,
        val landscape: Boolean,
        val realHeightPx: Int,
    ) {
        // as splitties' dp: in floats, truncated
        fun dp(value: Double): Int = (value.toFloat() * density).toInt()
    }

    /** The screen as [ctx] has it now: the input method's own (InputView), its resources the display's. */
    fun screen(ctx: Context): Screen {
        val m = ctx.resources.displayMetrics
        val config = ctx.resources.configuration
        return Screen(m.widthPixels, m.heightPixels, config.screenWidthDp, m.density, config.orientation == Configuration.ORIENTATION_LANDSCAPE, realSize(ctx).y)
    }

    /**
     * The screen the input method has on this display, upright (portrait) and turned, as InputView would see it (measured on
     * the API 29 and 36 emulators): from API 30 the display's whole (its maximum window, the bars drawn over); on 29
     * the display less the navigation bar, at the bottom upright and where it moves turned. Not [ctx]'s window: a
     * settings page in a split screen tells of the keyboard as it is shown, full width.
     */
    fun screens(ctx: Context): Pair<Screen, Screen> {
        val density = ctx.resources.displayMetrics.density
        val real = realSize(ctx)
        val portrait = Point(minOf(real.x, real.y), maxOf(real.x, real.y))
        fun screen(landscape: Boolean): Screen {
            val w = if (landscape) portrait.y else portrait.x
            val h = if (landscape) portrait.x else portrait.y
            val bar = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) NavBar.NONE else NavBar.of(landscape, portrait, density)
            val width = w - bar.side
            return Screen(width, h - bar.bottom, (width / density).toInt(), density, landscape, h)
        }
        return screen(false) to screen(true)
    }

    // the navigation bar the screen leaves out on API 29 (DisplayPolicy's of Android 10): at the bottom upright and,
    // on a phone that has one that moves, at the side turned; none where the device shows none
    private class NavBar(val bottom: Int, val side: Int) {
        companion object {
            val NONE = NavBar(0, 0)

            fun of(landscape: Boolean, portrait: Point, density: Float): NavBar {
                val res = Resources.getSystem()
                fun bool(name: String) = res.getIdentifier(name, "bool", "android").takeIf { it != 0 }?.let(res::getBoolean)
                fun dimen(name: String) = res.getIdentifier(name, "dimen", "android").takeIf { it != 0 }?.let(res::getDimensionPixelSize) ?: 0
                if (bool("config_showNavigationBar") == false) return NONE
                if (!landscape) return NavBar(dimen("navigation_bar_height"), 0)
                val moves = bool("config_navBarCanMove") != false && portrait.x / density < TABLET_DP
                return if (moves) NavBar(0, dimen("navigation_bar_width")) else NavBar(dimen("navigation_bar_height_landscape"), 0)
            }

            // a display this short side or wider keeps its bar at the bottom (DisplayPolicy: shortSizeDp < 600)
            private const val TABLET_DP = 600
        }
    }

    private fun realSize(ctx: Context): Point = Point().also {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ctx.windowManager.maximumWindowMetrics.bounds.let { b -> it.set(b.width(), b.height()) }
        } else {
            ctx.windowManager.defaultDisplay.getRealSize(it)
        }
    }

    /** The keyboard's height asked for (键盘高度, over the screen height its base says). */
    fun requestedHeight(ctx: Context): Int = requestedHeight(screen(ctx))

    fun requestedHeight(s: Screen): Int {
        val base = when (AppPrefs.getInstance().advanced.keyboardHeightPercentBase.getValue()) {
            KeyboardHeightPercentBase.DisplayMetrics -> s.heightPx
            KeyboardHeightPercentBase.RealSize -> s.realHeightPx
        }
        return sizes().heightPx(base, s.landscape)
    }

    fun sidePadding(ctx: Context): Int = sidePadding(screen(ctx))

    fun sidePadding(s: Screen): Int = s.dp(sizes().sidePaddingDp(s.landscape))

    fun bottomPadding(ctx: Context): Int = bottomPadding(screen(ctx))

    fun bottomPadding(s: Screen): Int = s.dp(sizes().bottomPaddingDp(s.landscape))

    /** 贴底: no room left for the navigation bar ([KeyboardSizes.flush]). */
    fun bottomFlush(): Boolean = sizes().flush

    /** The keys' height under the bar and a code row of [codeRowDp], [handle] a docked drag handle: capped ([KeyboardHeightCap]). */
    fun keysHeight(ctx: Context, codeRowDp: Int, handle: Int = 0): Int = keysHeight(screen(ctx), codeRowDp, handle, KawaiiBarComponent.heightDp(ctx))

    /** [keysHeight] on [s], the bar [barDp] high as it is there ([barDp] of [s]: its scenes' height). */
    fun keysHeight(s: Screen, codeRowDp: Int, handle: Int = 0, barDp: Int = barDp(s)): Int = KeyboardHeightCap.keysHeight(
        requestedHeight(s),
        s.heightPx,
        s.dp((barDp + codeRowDp).toDouble()),
        bottomPadding(s),
        handleHeight = handle,
    )

    /** The bar's height on [s], dp (KawaiiBarComponent.heightDp in its window's scenes). */
    fun barDp(s: Screen): Int = KawaiiBarComponent.heightDp(percent = SceneBar.heightPercent(s.landscape))

    /** The keys' width docked, between the side paddings. */
    fun keysWidth(ctx: Context): Int = keysWidth(screen(ctx))

    fun keysWidth(s: Screen): Int = s.widthPx - 2 * sidePadding(s)

    /** The keyboard's width, dp: the screen's less its side padding (InputView's), or floating its own (TextKeyboard.widthDp). */
    fun widthDp(s: Screen, floating: Boolean = ThemeManager.prefs.floatingKeyboard.getValue()): Int {
        if (floating) return FloatingKeyboard.widthDp(s.widthDp)
        // the theme's where the setting follows it (sidePadding): a fraction of a dp cut, as there
        return (s.widthDp - 2 * sizes().sidePaddingDp(s.landscape)).toInt()
    }

    /** Whether the letters are split on [s]: as the setting is, the screen as wide as it is; floating, never (TextKeyboard.splits). */
    fun splits(s: Screen, floating: Boolean = ThemeManager.prefs.floatingKeyboard.getValue()): Boolean =
        !floating && SplitKeyboard.splits(ThemeManager.prefs.splitKeyboard.getValue().mode, s.landscape, s.widthDp)
}
