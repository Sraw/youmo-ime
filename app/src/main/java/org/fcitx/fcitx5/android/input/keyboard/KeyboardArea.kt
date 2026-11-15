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
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.NavbarBackground
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
     * split and floating widths read it); [realHeightPx] the display's whole height as held, 键盘高度's RealSize base
     * and [ImeRoom]'s (never [heightPx], which below API 35 left the navigation bar out already).
     * [landscape] is 横屏 as [ScreenShape] has it: held sideways, or as wide as a foldable's inner screen or a
     * tablet upright, the one answer every portrait-or-landscape setting and the theme's `@landscape` read.
     * [shortSideDp] the display's short side, whichever way held, the bars not left out: a large screen's rows turned
     * ([KeyboardHeightLevels.LARGE_SHORT_SIDE_DP]; a 601 dp tablet whose bar left 553 is no phone). [statusBarPx] the
     * status bar, [imeNavPx] the navigation bar under the input method's window (none where it is at a side), as
     * [ImeRoom] reads them.
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
        // as splitties' dp: in floats, truncated
        fun dp(value: Double): Int = (value.toFloat() * density).toInt()

        /** A foldable's inner screen, a tablet: 横屏's rows are a large screen's (061-landscape-height §5.1). */
        val large: Boolean get() = shortSideDp >= KeyboardHeightLevels.LARGE_SHORT_SIDE_DP
    }

    /** The screen as [ctx] has it now: the input method's own (InputView), its resources the display's. */
    fun screen(ctx: Context): Screen {
        val m = ctx.resources.displayMetrics
        val config = ctx.resources.configuration
        val d = display(ctx)
        return Screen(
            m.widthPixels, m.heightPixels, config.screenWidthDp, m.density, landscape(ctx), d.height,
            toDp(minOf(d.width, d.height), m.density, CONFIG_ROUNDS_DP), d.statusBar(d.width > d.height), d.imeNav(d.width > d.height),
        )
    }

    /**
     * The display as held, px, its status bar and its navigation bar's height under the input method's window either
     * way held ([imeNav]): asked of the window manager once a configuration ([displayKept]), not each measure pass
     * (each asked it before, a binder call).
     */
    private class Display(val width: Int, val height: Int, val statusBar: (landscape: Boolean) -> Int, val imeNav: (landscape: Boolean) -> Int)

    // the last configuration's display: rotated, unfolded, resized, a new configuration and asked again
    // (a new navigation mode is an overlay, a new configuration too: its assets' sequence)
    private var displayKept: Pair<Configuration, Display>? = null

    private fun display(ctx: Context): Display {
        val key = Configuration(ctx.resources.configuration)
        displayKept?.takeIf { it.first == key }?.let { return it.second }
        val real = realSizeOf(ctx)
        val density = ctx.resources.displayMetrics.density
        val d = Display(real.x, real.y, statusBar(ctx), imeNav(ctx, minOf(real.x, real.y), density))
        // just turned, the window manager can still give the size before the turn: kept only where it is the way the
        // configuration is held, else asked again next time (not a turn's whole configuration long upright)
        if ((real.x > real.y) == (key.orientation == Configuration.ORIENTATION_LANDSCAPE)) displayKept = key to d
        return d
    }

    /** The display's size as held, px (a test's own: one not yet turned). */
    @VisibleForTesting
    internal var realSizeOf: (Context) -> Point = ::realSize

    @VisibleForTesting
    internal fun forgetDisplay() {
        displayKept = null
    }

    // the status bar each way held: the system's resource, and the way held now the window manager's from API 30 where
    // taller (a camera's notch makes it so upright), so a prediction ([ImeRoom]) errs to the app's side
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
     * The navigation bar under the input method's window, held [landscape]: gestures, the frame's height (48 dp: the
     * system gives the input method's window the bar's own window, with its switcher and hide buttons, not the
     * 24 dp strip apps are told of; measured on API 34, 061-landscape-height §2.4); three buttons, theirs where they
     * are at the bottom, none at a side; none where the device shows no bar.
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

    /**
     * 横屏 as [ctx]'s window is ([ScreenShape]): the input method's own in the keyboard; everything there reads this
     * (or [screen]'s), never the orientation itself, so a wide window upright is laid out as turned everywhere alike.
     */
    fun landscape(ctx: Context): Boolean = ctx.resources.configuration.let {
        ScreenShape.landscape(it.orientation == Configuration.ORIENTATION_LANDSCAPE, it.screenWidthDp)
    }

    /**
     * The screen the input method has on this display, upright (portrait) and turned, as InputView would see it (measured on
     * the API 29, 30, 33 and 36 emulators): from API 35 the display's whole (its maximum window, edge to edge, the bars
     * drawn over); below, the display less the navigation bar (as held now, the one measured; turned, gestures' strip as
     * tall, or the buttons' by the system's resources: at the bottom upright, where it moves turned) and, from API 30,
     * less the display cutout across its short side (a camera's notch: at the top upright, at a side turned). Not [ctx]'s
     * window: a settings page in a split screen tells of the keyboard as it is shown, full width.
     */
    fun screens(ctx: Context): Pair<Screen, Screen> = held(ctx).first

    /**
     * The screen of [screens] the display is held as now: what a settings page shows the keyboard on (the theme's
     * preview), not its own window's, which in a split screen is half the display.
     */
    fun displayScreen(ctx: Context): Screen = held(ctx).let { (both, turned) -> if (turned) both.second else both.first }

    /**
     * [ctx] with the configuration of [displayScreen]: the display as held, not [ctx]'s window (half of it in a split
     * screen, upright on a foldable unfolded where the display is 横屏). A settings page draws the keyboard in it, so
     * the keys and the bar ([landscape], [screen]'s width, the split) are as the input method's window has them.
     * Only its configuration's orientation and screenWidthDp/screenHeightDp, and the density, are the display's: its
     * displayMetrics' widthPixels and heightPixels may still be [ctx]'s window's, so nothing made in it reads those.
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

    // the pair, and whether the display is held turned now
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
        // gestures (Q's navigation_mode 2, as R's otherBar reads it): the strip at the bottom held either way, never a side bar
        val gestures = Settings.Secure.getInt(ctx.contentResolver, NAVIGATION_MODE, 0) == GESTURES
        fun bar(landscape: Boolean) = if (shown) NavBar.of(landscape && !gestures, minOf(real.x, real.y), density) else NavBar.NONE
        val bars = display(ctx)
        return screensOf(
            real.x, real.y, density, bar(nowLandscape), bar(!nowLandscape), 0, roundDp = false, nowWidthDp, bars.statusBar, bars.imeNav,
        ) to nowLandscape
    }

    // from API 30: one ask of the window manager, the display as held now, its bars and cutout
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
        // as held now, the bar measured: at the bottom, or at a side
        val now = NavBar(n.bottom, n.left + n.right)
        val mode = Settings.Secure.getInt(ctx.contentResolver, NAVIGATION_MODE, 0)
        val other = otherBar(now, nowLandscape, density, mode) { NavBar.of(!nowLandscape, minOf(w, h), density) }
        val cutout = if (nowLandscape) c.left + c.right else c.top + c.bottom
        return screensOf(w, h, density, now, other, cutout, roundDp = true, nowWidthDp, bars.statusBar, bars.imeNav) to nowLandscape
    }

    /**
     * The screens upright and turned of a display [width]×[height] px as held now: [now] the navigation bar as held
     * now, [other] turned; [cutout] the display cutout across its short side (top and bottom upright, the sides
     * turned); [roundDp] the width in dp rounded, as the configuration has it from API 30 (truncated on 29).
     * [nowWidthDp] the configuration's width as held now, taken for the screen held now where it is a rounding
     * off what is worked out here: on [ScreenShape.WIDE_DP]'s edge (599.5) both say what [screen] says.
     * Each screen 横屏 or not by [ScreenShape]: a foldable's inner screen and a tablet are turned both ways up.
     * [statusBar] and [imeNav] are each screen's ([Screen.statusBarPx], [Screen.imeNavPx]).
     */
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

    // px in dp as the configuration has a width: rounded from API 30, truncated on 29; the short side the same way, so a
    // screen 600 dp wide by its configuration is large by its display too (599.6 dp: both 600 or, on 29, both 599)
    private fun toDp(px: Int, density: Float, round: Boolean): Int = if (round) (px / density + HALF).toInt() else (px / density).toInt()

    private val CONFIG_ROUNDS_DP = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private const val HALF = 0.5f

    /**
     * The navigation bar turned, from [now] the one measured as held now ([nowLandscape]): none if none now. A bar
     * at the bottom both ways up stays as tall turned: gesture navigation ([mode] 2), or a strip upright shorter than
     * any buttons' bar (a ROM not saying mode 2), or a bar at the bottom while landscape (gestures, 3 buttons on a
     * tablet or a display 600 dp or wider, a bar that does not move, the 12L taskbar). Each keeps its height turned
     * (DisplayPolicy gives one bottom height for both, the taskbar is the same strip), so copying it is right.
     * Else the buttons' bar by the system's resources, [buttons].
     */
    @VisibleForTesting
    internal fun otherBar(now: NavBar, nowLandscape: Boolean, density: Float, mode: Int, buttons: () -> NavBar): NavBar {
        if (now == NavBar.NONE) return NavBar.NONE
        val bottomBothWays = mode == GESTURES ||
            (nowLandscape && now.bottom > 0) ||
            (!nowLandscape && now.bottom > 0 && now.bottom / density < STRIP_DP)
        return if (bottomBothWays) NavBar(maxOf(now.bottom, now.side), 0) else buttons()
    }

    // a bar at the bottom upright shorter than this, dp, is a gesture strip (buttons' bars are 40 dp or more)
    private const val STRIP_DP = 32

    /** A navigation bar a screen leaves out, px: at the bottom, or at the sides. */
    @VisibleForTesting
    internal data class NavBar(val bottom: Int, val side: Int) {
        companion object {
            val NONE = NavBar(0, 0)

            // the buttons' bar by the system's resources (DisplayPolicy's): at the bottom upright and, on a phone
            // whose bar moves, at the side turned
            fun of(landscape: Boolean, shortSidePx: Int, density: Float): NavBar {
                val res = Resources.getSystem()
                fun bool(name: String) = res.getIdentifier(name, "bool", "android").takeIf { it != 0 }?.let(res::getBoolean)
                fun dimen(name: String) = res.getIdentifier(name, "dimen", "android").takeIf { it != 0 }?.let(res::getDimensionPixelSize) ?: 0
                if (!landscape) return NavBar(dimen("navigation_bar_height"), 0)
                val moves = bool("config_navBarCanMove") != false && shortSidePx / density < TABLET_DP
                return if (moves) NavBar(0, dimen("navigation_bar_width")) else NavBar(dimen("navigation_bar_height_landscape"), 0)
            }

            // a display this short side or wider keeps its bar at the bottom (DisplayPolicy: shortSizeDp < 600):
            // the system's rule of the short side, not ScreenShape.WIDE_DP's of the width, though both are 600
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

    /** The keyboard's height asked for (键盘高度: upright a share of the screen's height its base says, turned rows of dp). */
    fun requestedHeight(ctx: Context): Int = requestedHeight(screen(ctx))

    fun requestedHeight(s: Screen): Int =
        if (s.landscape && turnedAs060) floatingBase(s) else sizes().heightPx(percentBase(s), s.landscape, s.density, s.large)

    /**
     * 横屏 asks for 0.6.0's height, a share of the screen, not rows of dp: ThemeBaselineCompareTest holds the drawing to
     * 0.6.0's pictures, which a keyboard of another height would make differ everywhere (the heights themselves are
     * the Roborazzi shots' and KeyboardSizesTest's).
     */
    @VisibleForTesting
    internal var turnedAs060 = false

    /**
     * What a floating keyboard is made from, px ([FloatingKeyboard.height]): the docked keys as 0.6.0 had them, a share
     * of the screen either way held; floating takes nothing of the app's and keeps its 0.6.0 size (061-landscape-height §5.4).
     */
    fun floatingBase(ctx: Context): Int = floatingBase(screen(ctx))

    fun floatingBase(s: Screen): Int = sizes().percentHeightPx(percentBase(s), s.widthPx, s.landscape)

    private fun percentBase(s: Screen): Int = when (AppPrefs.getInstance().advanced.keyboardHeightPercentBase.getValue()) {
        KeyboardHeightPercentBase.DisplayMetrics -> s.heightPx
        KeyboardHeightPercentBase.RealSize -> s.realHeightPx
    }

    /**
     * The room the keyboard and the app share on [s] ([ImeRoom]) before the input view is laid out, and on a settings
     * page: the status bar shown and the bar as tall as the input method's, erring to the app's side, so a first frame
     * never leaves it less than the last. InputView takes its own height once it has one (061-landscape-height §5.2).
     */
    fun predictedRoom(s: Screen): Int = ImeRoom.available(
        s.realHeightPx,
        s.statusBarPx,
        s.imeNavPx,
        api35 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM,
        fullBackground = ThemeManager.prefs.navbarBackground.getValue() == NavbarBackground.Full,
        flush = bottomFlush(),
    )

    fun sidePadding(ctx: Context): Int = sidePadding(screen(ctx))

    fun sidePadding(s: Screen): Int = s.dp(sizes().sidePaddingDp(s.landscape))

    fun bottomPadding(ctx: Context): Int = bottomPadding(screen(ctx))

    fun bottomPadding(s: Screen): Int = s.dp(sizes().bottomPaddingDp(s.landscape))

    /** 贴底: no room left for the navigation bar ([KeyboardSizes.flush]). */
    fun bottomFlush(): Boolean = sizes().flush

    /**
     * The keys' height under the bar and a code row of [codeRowDp], [handle] a docked drag handle, in [room] of the
     * window ([predictedRoom] until the view has its own), [rows] the layout's ([KeyboardHeightCap.rowUnits]): the
     * app keeps its 120 dp where the rows can give it ([KeyboardHeightCap]).
     */
    fun keysHeight(ctx: Context, codeRowDp: Int, handle: Int = 0, room: Int? = null, rows: Double = KeyboardHeightCap.DEFAULT_ROWS): Int =
        screen(ctx).let { keysHeight(it, codeRowDp, handle, KawaiiBarComponent.heightDp(ctx), room ?: predictedRoom(it), rows) }

    /** [keysHeight] on [s], the bar [barDp] high as it is there ([barDp] of [s]: its scenes' height). */
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

    /** The bar's height on [s], dp (KawaiiBarComponent.heightDp in its window's scenes). */
    fun barDp(s: Screen): Int = KawaiiBarComponent.heightDp(percent = SceneBar.heightPercent(s.landscape))

    /** The keys' width docked, between the side paddings. */
    fun keysWidth(ctx: Context): Int = keysWidth(screen(ctx))

    fun keysWidth(s: Screen): Int = s.widthPx - 2 * sidePadding(s)

    /** The keyboard's width, dp: the screen's less its side padding (InputView's), or floating its own (TextKeyboard.widthDp). */
    fun widthDp(s: Screen, floating: Boolean = KeyboardStyles.of(s).floating): Int {
        if (floating) return floatingSize(s).widthDp
        // the theme's where the setting follows it (sidePadding): a fraction of a dp cut, as there
        return (s.widthDp - 2 * sizes().sidePaddingDp(s.landscape)).toInt()
    }

    /**
     * The floating keyboard's size on [s] (061-floating-resize §2.3): the one source of its width, its keys' height and
     * their text, the input view's and a settings page's alike, its width as the user resized it on [s]'s shape
     * ([FloatingStore]) within what [floatingBounds] allow there. A pure function of [s] and the settings: known before
     * any view is laid out (the insets only move it, FloatingPlacer).
     */
    fun floatingSize(s: Screen): FloatingKeyboard.Size =
        FloatingKeyboard.size(FloatingStore.widthDp(s.landscape), s.widthDp, floatingBase(s), floatingBounds(s))

    /**
     * How wide the user may resize the floating keyboard on [s]: up to the docked keyboard's width, or as wide as fits
     * in the screen's height less the status bar, the input method's navigation bar and the docking zone's mark, the bar,
     * the handle and the code row embedded at its tallest (the strip's) counted over the keys.
     */
    fun floatingBounds(s: Screen): FloatingKeyboard.Bounds {
        val codeRow = if (KawaiiBarComponent.codeRowFloats()) 0 else CodeRowHeight.STRIP_DP
        val bars = s.dp((barDp(s) + FloatingHandle.HEIGHT_DP + codeRow).toDouble())
        val room = s.realHeightPx - s.statusBarPx - s.imeNavPx - s.dp(FloatingPlacer.DOCK_MARK_DP.toDouble())
        return FloatingKeyboard.bounds(s.widthDp, widthDp(s, floating = false), floatingBase(s), bars, room)
    }

    /** Whether the letters are split on [s]: its shape's 键盘样式 is 分体, set or its default by the width (TextKeyboard.splits). */
    fun splits(s: Screen): Boolean = KeyboardStyles.of(s).splits
}
