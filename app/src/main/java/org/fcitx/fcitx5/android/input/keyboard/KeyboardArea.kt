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
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.WindowInsets
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.input.floating.FloatingHandle
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.floating.FloatingPlacer
import org.fcitx.fcitx5.android.input.floating.FloatingStore
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemes
import org.fcitx.fcitx5.android.scene.rules.ScreenShape
import org.fcitx.fcitx5.android.scene.theme.KeyboardSizes as ThemeSizes
import org.fcitx.fcitx5.android.utils.navbarFrameHeight
import org.fcitx.fcitx5.android.utils.windowManager
import kotlin.math.abs

object KeyboardArea {

    @VisibleForTesting
    internal var themeSizes: () -> ThemeSizes = {
        val theme = ThemeManager.activeTheme
        SceneThemes.of(theme, ThemeManager.shownLook(theme))?.keyboardSizes ?: ThemeSizes()
    }

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
     * [realHeightPx] is the whole height; never [heightPx], which below API 35 already left out the navigation
     * bar. [shortSideDp] includes the bars: a 601 dp tablet whose bar left 553 is no phone.
     */
    data class Screen(
        val widthPx: Int,
        val heightPx: Int,
        val widthDp: Int,
        val density: Float,
        val landscape: Boolean,
        val realHeightPx: Int,
        val shortSideDp: Int = 0,
        val statusBarPx: Int = 0,
        val imeNavPx: Int = 0,
    ) {
        fun dp(value: Double): Int = (value.toFloat() * density).toInt()

        val large: Boolean get() = shortSideDp >= KeyboardHeightLevels.LARGE_SHORT_SIDE_DP
    }

    fun screen(ctx: Context): Screen {
        val m = ctx.resources.displayMetrics
        val config = ctx.resources.configuration
        val d = display(ctx)
        return Screen(
            m.widthPixels, m.heightPixels, config.screenWidthDp, m.density, landscape(ctx), d.height,
            toDp(minOf(d.width, d.height), m.density, CONFIG_ROUNDS_DP), d.statusBar(d.width > d.height), d.imeNav(d.width > d.height),
        )
    }

    /** Asked of the window manager once per configuration ([displayKept]), not each measure pass (a binder call). */
    private class Display(val width: Int, val height: Int, val statusBar: (landscape: Boolean) -> Int, val imeNav: (landscape: Boolean) -> Int)

    private var displayKept: Pair<Configuration, Display>? = null

    private fun display(ctx: Context): Display {
        val now = ctx.resources.configuration
        displayKept?.takeIf { it.first == now }?.let { return it.second }
        val key = Configuration(now)
        val real = realSizeOf(ctx)
        val density = ctx.resources.displayMetrics.density
        val d = Display(real.x, real.y, statusBar(ctx), imeNav(ctx, minOf(real.x, real.y), density))
        // just turned, the window manager can still give the pre-turn size: kept only when it matches the
        // configuration's orientation
        if ((real.x > real.y) == (key.orientation == Configuration.ORIENTATION_LANDSCAPE)) displayKept = key to d
        return d
    }

    @VisibleForTesting
    internal var realSizeOf: (Context) -> Point = ::realSize

    @VisibleForTesting
    internal fun forgetDisplay() {
        displayKept = null
    }

    // from API 30 the window manager's where taller (a notch upright), so a prediction ([ImeRoom]) errs to the app's side
    private fun statusBar(ctx: Context): (Boolean) -> Int {
        val res = Resources.getSystem()
        val dimen = res.getIdentifier("status_bar_height", "dimen", "android").takeIf { it != 0 }?.let(res::getDimensionPixelSize) ?: 0
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return { dimen }
        val m = ctx.windowManager.maximumWindowMetrics
        val measured = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        val nowLandscape = m.bounds.width() > m.bounds.height()
        return { landscape -> if (landscape == nowLandscape) maxOf(dimen, measured) else dimen }
    }

    /**
     * Gestures: the frame's height (48 dp: the IME window gets the bar's own window with its switcher and
     * hide buttons, not the 24 dp strip apps are told of; measured on API 34). Three buttons: theirs at
     * the bottom, none at a side.
     */
    private fun imeNav(ctx: Context, shortSidePx: Int, density: Float): (Boolean) -> Int {
        val res = Resources.getSystem()
        val shown = res.getIdentifier("config_showNavigationBar", "bool", "android").takeIf { it != 0 }?.let(res::getBoolean) != false
        if (!shown) return { 0 }
        if (Settings.Secure.getInt(ctx.contentResolver, NAVIGATION_MODE, 0) == GESTURES) {
            val frame = ctx.navbarFrameHeight()
            return { frame }
        }
        return { landscape -> NavBar.of(landscape, shortSidePx, density).bottom }
    }

    fun landscape(ctx: Context): Boolean = ctx.resources.configuration.let {
        ScreenShape.landscape(it.orientation == Configuration.ORIENTATION_LANDSCAPE, it.screenWidthDp)
    }

    /**
     * As InputView would see it: from API 35 the whole display; below, less the navigation bar and, from API 30,
     * the cutout across the short side. Not [ctx]'s window: a settings page in split screen shows the keyboard full width.
     */
    fun screens(ctx: Context): Pair<Screen, Screen> = held(ctx).first

    fun displayScreen(ctx: Context): Screen = held(ctx).let { (both, turned) -> if (turned) both.second else both.first }

    /**
     * A settings page draws the keyboard in it. Only orientation, screenWidthDp/screenHeightDp and density
     * are the display's: displayMetrics may still be [ctx]'s window's, so nothing made in it reads those.
     */
    fun displayContext(ctx: Context): Context {
        val s = displayScreen(ctx)
        val config = Configuration(ctx.resources.configuration).apply {
            orientation = if (s.widthPx > s.heightPx) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            screenWidthDp = s.widthDp
            screenHeightDp = (s.heightPx / s.density).toInt()
        }
        return ContextThemeWrapper(ctx, ctx.theme).apply { applyOverrideConfiguration(config) }
    }

    private fun held(ctx: Context): Pair<Pair<Screen, Screen>, Boolean> {
        val density = ctx.resources.displayMetrics.density
        // the process's configuration, the display's: not an activity's own window's (split screen)
        val nowWidthDp = ctx.applicationContext.resources.configuration.screenWidthDp
        // the check as is, not through a local: lint's NewApi reads no other (a release build's lintVital failed)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return measured(ctx, density, nowWidthDp)
        @Suppress("DEPRECATION")
        val real = Point().also { ctx.windowManager.defaultDisplay.getRealSize(it) }
        val res = Resources.getSystem()
        val shown = res.getIdentifier("config_showNavigationBar", "bool", "android").takeIf { it != 0 }?.let(res::getBoolean) != false
        val nowLandscape = real.x > real.y
        // Q's navigation_mode 2: the strip is at the bottom either way, never a side bar
        val gestures = Settings.Secure.getInt(ctx.contentResolver, NAVIGATION_MODE, 0) == GESTURES
        fun bar(landscape: Boolean) = if (shown) NavBar.of(landscape && !gestures, minOf(real.x, real.y), density) else NavBar.NONE
        val bars = display(ctx)
        return screensOf(
            real.x, real.y, density, bar(nowLandscape), bar(!nowLandscape), 0, roundDp = false, nowWidthDp, bars.statusBar, bars.imeNav,
        ) to nowLandscape
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun measured(ctx: Context, density: Float, nowWidthDp: Int): Pair<Pair<Screen, Screen>, Boolean> {
        val m = ctx.windowManager.maximumWindowMetrics
        val w = m.bounds.width()
        val h = m.bounds.height()
        val bars = display(ctx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return screensOf(w, h, density, NavBar.NONE, NavBar.NONE, 0, roundDp = true, nowWidthDp, bars.statusBar, bars.imeNav) to (w > h)
        }
        val n = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
        val c = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
        val nowLandscape = w > h
        val now = NavBar(n.bottom, n.left + n.right)
        val mode = Settings.Secure.getInt(ctx.contentResolver, NAVIGATION_MODE, 0)
        val other = otherBar(now, nowLandscape, density, mode) { NavBar.of(!nowLandscape, minOf(w, h), density) }
        val cutout = if (nowLandscape) c.left + c.right else c.top + c.bottom
        return screensOf(w, h, density, now, other, cutout, roundDp = true, nowWidthDp, bars.statusBar, bars.imeNav) to nowLandscape
    }

    /** [roundDp]: dp rounded from API 30, truncated on 29. */
    @VisibleForTesting
    internal fun screensOf(
        width: Int,
        height: Int,
        density: Float,
        now: NavBar,
        other: NavBar,
        cutout: Int,
        roundDp: Boolean,
        nowWidthDp: Int? = null,
        statusBar: (landscape: Boolean) -> Int = { 0 },
        imeNav: (landscape: Boolean) -> Int = { 0 },
    ): Pair<Screen, Screen> {
        val nowLandscape = width > height
        val shortSide = minOf(width, height)
        val longSide = maxOf(width, height)
        fun screen(landscape: Boolean): Screen {
            val w = if (landscape) longSide else shortSide
            val h = if (landscape) shortSide else longSide
            val bar = if (landscape == nowLandscape) now else other
            val widthPx = w - bar.side - (if (landscape) cutout else 0)
            val worked = toDp(widthPx, density, roundDp)
            val widthDp = nowWidthDp?.takeIf { landscape == nowLandscape && abs(it - worked) <= 1 } ?: worked
            val shape = ScreenShape.landscape(landscape, widthDp)
            return Screen(
                widthPx, h - bar.bottom - (if (landscape) 0 else cutout), widthDp, density, shape, h,
                toDp(shortSide, density, roundDp), statusBar(landscape), imeNav(landscape),
            )
        }
        return screen(false) to screen(true)
    }

    private fun toDp(px: Int, density: Float, round: Boolean): Int = if (round) (px / density + HALF).toInt() else (px / density).toInt()

    private val CONFIG_ROUNDS_DP = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private const val HALF = 0.5f

    /**
     * A bar at the bottom both ways up keeps its height turned: gestures ([mode] 2), a strip shorter than
     * any buttons' bar (a ROM not saying mode 2), or one at the bottom in landscape (tablet, 12L taskbar);
     * DisplayPolicy gives one bottom height for both. Else the buttons' bar by resources, [buttons].
     */
    @VisibleForTesting
    internal fun otherBar(now: NavBar, nowLandscape: Boolean, density: Float, mode: Int, buttons: () -> NavBar): NavBar {
        if (now == NavBar.NONE) return NavBar.NONE
        val bottomBothWays = mode == GESTURES ||
            (nowLandscape && now.bottom > 0) ||
            (!nowLandscape && now.bottom > 0 && now.bottom / density < STRIP_DP)
        return if (bottomBothWays) NavBar(maxOf(now.bottom, now.side), 0) else buttons()
    }

    // shorter than this, dp, is a gesture strip (buttons' bars are 40 dp or more)
    private const val STRIP_DP = 32

    @VisibleForTesting
    internal data class NavBar(val bottom: Int, val side: Int) {
        companion object {
            val NONE = NavBar(0, 0)

            fun of(landscape: Boolean, shortSidePx: Int, density: Float): NavBar {
                val res = Resources.getSystem()
                fun bool(name: String) = res.getIdentifier(name, "bool", "android").takeIf { it != 0 }?.let(res::getBoolean)
                fun dimen(name: String) = res.getIdentifier(name, "dimen", "android").takeIf { it != 0 }?.let(res::getDimensionPixelSize) ?: 0
                if (!landscape) return NavBar(dimen("navigation_bar_height"), 0)
                val moves = bool("config_navBarCanMove") != false && shortSidePx / density < TABLET_DP
                return if (moves) NavBar(0, dimen("navigation_bar_width")) else NavBar(dimen("navigation_bar_height_landscape"), 0)
            }

            // DisplayPolicy's shortSizeDp < 600 rule, not ScreenShape.WIDE_DP's of the width, though both are 600
            private const val TABLET_DP = 600
        }
    }

    // Settings.Secure.NAVIGATION_MODE, hidden: 0 three buttons, 1 two, 2 gestures
    private const val NAVIGATION_MODE = "navigation_mode"
    @VisibleForTesting
    internal const val GESTURES = 2

    private fun realSize(ctx: Context): Point = Point().also {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ctx.windowManager.maximumWindowMetrics.bounds.let { b -> it.set(b.width(), b.height()) }
        } else {
            ctx.windowManager.defaultDisplay.getRealSize(it)
        }
    }

    fun requestedHeight(ctx: Context): Int = requestedHeight(screen(ctx))

    fun requestedHeight(s: Screen): Int =
        sizes().heightPx(s.heightPx, s.landscape, s.density, s.large)

    fun floatingBase(ctx: Context): Int = floatingBase(screen(ctx))

    fun floatingBase(s: Screen): Int = sizes().percentHeightPx(s.heightPx, s.widthPx, s.landscape)

    /**
     * Before the input view is laid out, and on a settings page: errs to the app's side, so a first frame
     * never leaves it less than the last. InputView takes its own height once it has one.
     */
    fun predictedRoom(s: Screen): Int = ImeRoom.available(
        s.realHeightPx,
        s.statusBarPx,
        s.imeNavPx,
        flush = bottomFlush(),
    )

    fun sidePadding(ctx: Context): Int = sidePadding(screen(ctx))

    fun sidePadding(s: Screen): Int = s.dp(sizes().sidePaddingDp(s.landscape))

    fun bottomPadding(ctx: Context): Int = bottomPadding(screen(ctx))

    fun bottomPadding(s: Screen): Int = s.dp(sizes().bottomPaddingDp(s.landscape))

    fun bottomFlush(): Boolean = sizes().flush

    /** [room]: [predictedRoom] until the view has its own. The app keeps its 120 dp where the rows can give it. */
    fun keysHeight(ctx: Context, codeRowDp: Int, handle: Int = 0, room: Int? = null, rows: Double = KeyboardHeightCap.DEFAULT_ROWS): Int =
        screen(ctx).let { keysHeight(it, codeRowDp, handle, KawaiiBarComponent.heightDp(ctx), room ?: predictedRoom(it), rows) }

    fun keysHeight(
        s: Screen,
        codeRowDp: Int,
        handle: Int = 0,
        barDp: Int = barDp(s),
        room: Int = predictedRoom(s),
        rows: Double = KeyboardHeightCap.DEFAULT_ROWS,
    ): Int = KeyboardHeightCap.keysHeight(
        requestedHeight(s),
        room,
        s.dp((barDp + codeRowDp).toDouble()),
        bottomPadding(s),
        handleHeight = handle,
        rows = rows,
        density = s.density,
    )

    fun barDp(s: Screen): Int = KawaiiBarComponent.heightDp(percent = SceneBar.heightPercent(s.landscape))

    fun keysWidth(ctx: Context): Int = keysWidth(screen(ctx))

    fun keysWidth(s: Screen): Int = s.widthPx - 2 * sidePadding(s)

    fun widthDp(s: Screen, floating: Boolean = KeyboardStyles.of(s).floating): Int {
        if (floating) return floatingSize(s).widthDp
        return (s.widthDp - 2 * sizes().sidePaddingDp(s.landscape)).toInt()
    }

    /** A pure function of [s] and the settings: known before layout (the insets only move it, FloatingPlacer). */
    fun floatingSize(s: Screen): FloatingKeyboard.Size =
        FloatingKeyboard.size(FloatingStore.widthDp(s.landscape), s.widthDp, floatingBase(s), floatingBounds(s))

    fun floatingBounds(s: Screen): FloatingKeyboard.Bounds {
        val codeRow = if (KawaiiBarComponent.codeRowFloats()) 0 else CodeRowHeight.STRIP_DP
        val bars = s.dp((barDp(s) + FloatingHandle.HEIGHT_DP + codeRow).toDouble())
        val room = s.realHeightPx - s.statusBarPx - s.imeNavPx - s.dp(FloatingPlacer.DOCK_MARK_DP.toDouble())
        return FloatingKeyboard.bounds(s.widthDp, widthDp(s, floating = false), floatingBase(s), bars, room)
    }

    fun splits(s: Screen): Boolean = KeyboardStyles.of(s).splits
}
