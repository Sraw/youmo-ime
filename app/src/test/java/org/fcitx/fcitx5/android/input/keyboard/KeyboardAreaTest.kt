/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Point
import android.os.Build
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.scene.theme.Twin
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.dimensions.dp
import org.fcitx.fcitx5.android.scene.theme.KeyboardSizes as ThemeSizes

/**
 * The keyboard's size as the settings and the theme in use make it (060-render-p3 T5): a theme's
 * followed by default, a level picked over it, the cap ([KeyboardHeightCap]) last either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KeyboardAreaTest {

    @get:Rule
    val prefs = PrefsRule()

    private val ctx get() = RuntimeEnvironment.getApplication()
    private val keyboard get() = AppPrefs.getInstance().keyboard
    private val was = KeyboardArea.themeSizes
    private val realSize = KeyboardArea.realSizeOf

    // the display asked anew: one a test before kept is no answer of this test's qualifiers
    @Before
    fun noDisplayKept() {
        KeyboardArea.forgetDisplay()
    }

    @After
    fun theThemesOwn() {
        KeyboardArea.themeSizes = was
        KeyboardArea.realSizeOf = realSize
        KeyboardArea.forgetDisplay()
    }

    private fun theme(sizes: ThemeSizes) {
        KeyboardArea.themeSizes = { sizes }
    }

    @Test
    fun theBuiltInThemeSetsNoneTodaysLook() {
        // the default theme's: none, the settings' defaults (标准, no padding)
        assertEquals(ThemeSizes(), was())
        val screen = ctx.resources.displayMetrics.heightPixels
        assertEquals(screen * 30 / 100, KeyboardArea.requestedHeight(ctx))
        assertEquals(0, KeyboardArea.sidePadding(ctx))
        assertEquals(0, KeyboardArea.bottomPadding(ctx))
        assertFalse(keyboardCompact())
    }

    @Test
    fun theThemeSetsAHeight() {
        theme(ThemeSizes(height = Twin(40.0, 55.0), sidePadding = Twin(12.0, 40.0), bottomPadding = Twin(6.0, 2.0)))
        val screen = ctx.resources.displayMetrics.heightPixels
        assertEquals((screen * 0.40).toInt(), KeyboardArea.requestedHeight(ctx))
        assertEquals(ctx.dp(12), KeyboardArea.sidePadding(ctx))
        assertEquals(ctx.dp(6), KeyboardArea.bottomPadding(ctx))
        assertEquals(ctx.resources.displayMetrics.widthPixels - 2 * ctx.dp(12), KeyboardArea.keysWidth(ctx))
        // under the cap upright: the keys the height asked for
        assertEquals(KeyboardArea.requestedHeight(ctx), KeyboardArea.keysHeight(ctx, 0))
        // a level picked: the setting's, over the theme's
        prefs.set(keyboard.keyboardHeightFollowTheme, false)
        prefs.set(keyboard.keyboardHeightPercent, 20)
        prefs.set(keyboard.keyboardHeightPercentLandscape, 34)
        assertEquals(screen * 20 / 100, KeyboardArea.requestedHeight(ctx))
        assertTrue(keyboardCompact())
        // the paddings still the theme's
        assertEquals(ctx.dp(12), KeyboardArea.sidePadding(ctx))
    }

    @Test
    fun theKeyboardsWidthIsLessTheSidePaddingFollowed() {
        // 393dp across: no padding, the setting's default
        assertEquals(393, TextKeyboard.widthDp(ctx))
        // the theme's, a fraction of a dp cut as KeyboardArea.sidePadding's: 393 - 9.5
        theme(ThemeSizes(sidePadding = Twin(4.75, 30.0)))
        assertEquals(383, TextKeyboard.widthDp(ctx))
        // a level picked: the setting's
        prefs.set(keyboard.keyboardSidePaddingFollowTheme, false)
        prefs.set(keyboard.keyboardSidePadding, 8)
        assertEquals(377, TextKeyboard.widthDp(ctx))
    }

    @Test
    fun aThemesHeightPastTheCapIsCut() {
        theme(ThemeSizes(height = Twin(90.0, 90.0), bottomPadding = Twin(10.0, 10.0)))
        val screen = ctx.resources.displayMetrics.heightPixels
        val bars = ctx.dp(KawaiiBarComponent.heightDp())
        // 90% asked: the app keeps 120 dp of the room predicted, the bars and the padding in it (061-landscape-height §5.2)
        val room = KeyboardArea.predictedRoom(KeyboardArea.screen(ctx))
        val keys = KeyboardArea.keysHeight(ctx, 0)
        assertEquals(ctx.dp(KeyboardHeightCap.MIN_APP_DP), room - bars - ctx.dp(10) - keys)
        assertTrue(keys < KeyboardArea.requestedHeight(ctx))
        assertTrue(room < screen)
    }

    @Test
    fun theRoomPredictedLeavesTheStatusBarAndTheInputMethodsBarOut() {
        val s = KeyboardArea.screen(ctx)
        // the display whole as held, its status bar, and gestures' 48 dp under the input method (or the buttons')
        assertEquals(s.realHeightPx - s.statusBarPx - s.imeNavPx, KeyboardArea.predictedRoom(s))
        assertTrue(s.statusBarPx > 0)
        assertEquals(SHORT_SIDE_DP, s.shortSideDp)
        assertFalse(s.large)
        // 贴底 under 全部: nothing kept for the bar; below API 35 a background of 无 fits the window above it all the same
        prefs.set(keyboard.keyboardBottomFlush, 1)
        assertEquals(s.realHeightPx - s.statusBarPx, KeyboardArea.predictedRoom(s))
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun aDisplayNotYetTurnedIsNotKeptForTheTurn() {
        // just turned the window manager may still say the size upright: not kept for the configuration, asked again
        var asked = 0
        var upright = true
        KeyboardArea.realSizeOf = { asked++; if (upright) Point(1080, 2340) else Point(2340, 1080) }
        assertEquals(SHORT_SIDE_DP, KeyboardArea.screen(ctx).shortSideDp)
        KeyboardArea.screen(ctx)
        assertEquals(2, asked)
        upright = false
        KeyboardArea.screen(ctx)
        KeyboardArea.screen(ctx)
        assertEquals("as turned now: kept", 3, asked)
    }

    @Test
    @Uat("LS-49")
    @Config(qualifiers = "w601dp-h962dp-port-mdpi")
    fun aTabletAShortSideOver600IsLargeWhicheverWayHeld() {
        // its bar may leave the window under 600 across: the display's short side is what says large
        val s = KeyboardArea.screen(ctx)
        assertEquals(601, s.shortSideDp)
        assertTrue(s.large)
        val (up, turned) = KeyboardArea.screens(ctx)
        assertTrue(up.large && turned.large)
    }

    @Test
    @Uat("PR-61")
    @Config(qualifiers = "w851dp-h393dp-land-440dpi")
    fun aPhoneTurnedAsksForFourRowsOf48() {
        // 标准 turned: 4 × 48 dp, not 49% of the height (061-landscape-height §5.1, the user's Q1)
        assertEquals(ctx.dp(4 * 48), KeyboardArea.requestedHeight(ctx))
        // floating keeps 0.6.0's base: 49% of the screen's height
        val s = KeyboardArea.screen(ctx)
        assertEquals(s.heightPx * 49 / 100, KeyboardArea.floatingBase(ctx))
    }

    @Test
    fun aThemesBottomPaddingFollowedIsNoFlush() {
        prefs.set(keyboard.keyboardBottomFlush, 1)
        assertTrue(KeyboardArea.bottomFlush())
        theme(ThemeSizes(bottomPadding = Twin(4.0, 4.0)))
        assertFalse(KeyboardArea.bottomFlush())
        // 贴底 picked: not following, flush
        prefs.set(keyboard.keyboardBottomPaddingFollowTheme, false)
        assertTrue(KeyboardArea.bottomFlush())
        assertEquals(0, KeyboardArea.bottomPadding(ctx))
    }

    private companion object {
        // 1080 px at 2.75: the configuration rounds its dp from API 30 (393), cuts them below (392), as KeyboardArea does
        val SHORT_SIDE_DP = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) 393 else 392
    }
}
