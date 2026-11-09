/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.scene.theme.Twin
import org.fcitx.fcitx5.android.uat.PrefsRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @After
    fun theThemesOwn() {
        KeyboardArea.themeSizes = was
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
        // 90% asked, the cap (70% of the screen, the bars and the padding in it) what is given
        assertEquals((screen * KeyboardHeightCap.MAX_FRACTION).toInt() - bars - ctx.dp(10), KeyboardArea.keysHeight(ctx, 0))
        assertTrue(KeyboardArea.keysHeight(ctx, 0) < KeyboardArea.requestedHeight(ctx))
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
}
