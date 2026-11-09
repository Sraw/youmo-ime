/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.res.Configuration
import android.os.Build
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeMonet
import org.fcitx.fcitx5.android.scene.theme.SystemColors
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The system's dark mode switched (060-render-p3 T3), the built-in themes' versions in the light and dark
 * slots: the keyboard made anew with the slot's version, a pair's (`pixel#light`) and Monet's
 * (`monet#dark`), whose colours are the system's from Android 12 and its fallbacks before (at 29, the
 * colours 0.5 had there). Run at 35 and at 29 (testDebugUnitTestAt29).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class DarkModeThemeTest {

    private val h = KeyboardHarness()

    private val prefs = ThemeManager.prefs

    @After
    fun tearDown() {
        FcitxApplication.getInstance().onConfigurationChanged(night(false))
        prefs.lightModeTheme.setValue(prefs.lightModeTheme.defaultValue)
        prefs.darkModeTheme.setValue(prefs.darkModeTheme.defaultValue)
        prefs.followSystemDayNightTheme.setValue(prefs.followSystemDayNightTheme.defaultValue)
        h.close()
    }

    private fun night(on: Boolean) = Configuration(appContext.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (on) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
    }

    private fun switch(on: Boolean) {
        val config = night(on)
        FcitxApplication.getInstance().onConfigurationChanged(config)
        h.service.onConfigurationChanged(config)
        h.idle()
    }

    // what the keyboard is drawn with: the input view's theme, which its bar, keyboard and panels are made with
    private fun shown(): Theme = h.inputView.theme

    @Test
    fun theKeyboardFollowsTheSystemsDarkMode() {
        prefs.followSystemDayNightTheme.setValue(true)
        prefs.lightModeTheme.setValue(ThemeManager.builtin("pixel#light"))
        prefs.darkModeTheme.setValue(ThemeManager.builtin("monet#dark"))
        h.start()
        switch(false)
        assertEquals("pixel#light", shown().name)
        switch(true)
        val dark = shown() as Theme.Builtin
        assertEquals("monet#dark", dark.name)
        assertTrue(dark.isDark && dark.followsSystem)
        assertSame(ThemeManager.activeTheme, dark)
        // read again as the configuration changed, with the system's colours as they are now
        assertEquals(ThemeMonet.systemColors(appContext).dark["primary"]?.value ?: 0xffaac7ff.toInt(), dark.accentKeyBackgroundColor)
        switch(false)
        assertEquals("pixel#light", shown().name)
    }

    @Test
    fun monetIsTheSystemsColoursFromAndroid12AndItsFallbacksBefore() {
        val light = ThemeManager.builtin("monet#light")
        val dark = ThemeManager.builtin("monet#dark")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            assertEquals(SystemColors.NONE, ThemeMonet.systemColors(appContext))
            // 0.5's colours below Android 12 (MD3 of #769CDF): monet.xml's fallbacks
            assertEquals(0xffededf4.toInt(), light.backgroundColor)
            assertEquals(0xff415f91.toInt(), light.accentKeyBackgroundColor)
            assertEquals(0xff1d2024.toInt(), dark.backgroundColor)
            assertEquals(0xffaac7ff.toInt(), dark.accentKeyBackgroundColor)
        } else {
            // the roles (Android 14 on; 12 and 13 the palette's tones for them)
            fun color(id: Int) = appContext.getColor(id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                assertEquals(color(android.R.color.system_surface_container_light), light.backgroundColor)
                assertEquals(color(android.R.color.system_surface_container_highest_light), light.barColor)
                assertEquals(color(android.R.color.system_primary_light), light.accentKeyBackgroundColor)
                assertEquals(color(android.R.color.system_surface_container_dark), dark.backgroundColor)
                assertEquals(color(android.R.color.system_surface_container_high_dark), dark.barColor)
                assertEquals(color(android.R.color.system_primary_dark), dark.accentKeyBackgroundColor)
            }
            // the system's, not the fallbacks
            assertNotEquals(0xff415f91.toInt(), light.accentKeyBackgroundColor)
        }
        // the press highlight the text's at 12% (light) and 20% (dark), as 0.5 had it
        assertEquals(light.keyTextColor and 0xffffff or (0x1f shl 24), light.keyPressHighlightColor)
        assertEquals(dark.keyTextColor and 0xffffff or (0x33 shl 24), dark.keyPressHighlightColor)
    }
}
