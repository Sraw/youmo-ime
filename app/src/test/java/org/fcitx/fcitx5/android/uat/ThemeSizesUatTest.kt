/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.keyboardCompact
import org.fcitx.fcitx5.android.scene.theme.Twin
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.fcitx.fcitx5.android.scene.theme.KeyboardSizes as ThemeSizes

/**
 * The keyboard's size following the theme, as it changes (060-render-p3 T5): 跟随主题 picked or left
 * alone (a level whose numbers are stored already changes only it), and another theme in the preview.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class ThemeSizesUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var h: KeyboardHarness? = null
    private var settings: SettingsHarness? = null
    private val was = KeyboardArea.themeSizes

    @After
    fun close() {
        h?.close()
        settings?.close()
        KeyboardArea.themeSizes = was
    }

    private val keyboard get() = AppPrefs.getInstance().keyboard

    // a theme of a 紧凑 height
    private fun compactTheme() {
        KeyboardArea.themeSizes = { ThemeSizes(height = Twin(20.0, 34.0)) }
    }

    // 标准's numbers stored, 跟随主题 picked after: picking 标准 again writes the flag alone
    private fun standardStoredFollowing() {
        prefs.set(keyboard.keyboardHeightPercent, 30)
        prefs.set(keyboard.keyboardHeightPercentLandscape, 49)
        prefs.set(keyboard.keyboardHeightFollowTheme, true)
    }

    @Test
    fun leavingTheThemesHeightMakesTheKeysAnew() {
        standardStoredFollowing()
        compactTheme()
        val h = KeyboardHarness().also { h = it }
        h.start()
        assertTrue(keyboardCompact())
        val small = h.keyboard
        prefs.set(keyboard.keyboardHeightFollowTheme, false)
        h.idle()
        assertFalse(keyboardCompact())
        assertNotSame("out of 紧凑 by the flag alone", small, h.keyboard)
    }

    // the preview's q, as the engine drew it
    private fun q(st: SettingsHarness) = KeyboardHarness.keysOf(st.activity.window.decorView) { it.isShown }.first { it.label == "q" }

    @Test
    fun thePreviewFollowsTheFlagAlone() {
        standardStoredFollowing()
        compactTheme()
        val st = SettingsHarness().also { settings = it }
        st.open(SettingsRoute.Theme)
        val small = q(st)
        val size = small.textSize(small.label)!!
        val height = small.height
        prefs.set(keyboard.keyboardHeightFollowTheme, false)
        st.idle()
        // 标准's keys: taller, their text no longer 紧凑's 0.9
        assertTrue("${q(st).height} of $height", q(st).height > height)
        assertEquals(size / 0.9f, q(st).let { it.textSize(it.label)!! }, 0.5f)
    }

    @Test
    fun anotherThemesHeightIsThePreviews() {
        ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
        ThemeManager.setNormalModeTheme(TestThemes.PixelLight)
        prefs.later {
            ThemeManager.setNormalModeTheme(TestThemes.PixelLight)
            ThemeManager.prefs.followSystemDayNightTheme.setValue(ThemeManager.prefs.followSystemDayNightTheme.defaultValue)
        }
        // the dark one's keyboard taller, the light one's none
        KeyboardArea.themeSizes = {
            if (ThemeManager.activeTheme.name == TestThemes.PixelDark.name) ThemeSizes(height = Twin(45.0, 60.0)) else ThemeSizes()
        }
        val st = SettingsHarness().also { settings = it }
        st.open(SettingsRoute.Theme)
        val height = q(st).height
        ThemeManager.setNormalModeTheme(TestThemes.PixelDark)
        st.idle()
        // 45% over 30%: the rows half as tall again, the bar aside
        assertTrue("${q(st).height} of $height", q(st).height > height * 1.3f)
    }
}
