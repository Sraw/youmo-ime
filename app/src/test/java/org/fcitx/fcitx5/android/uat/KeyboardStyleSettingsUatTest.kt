/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import androidx.preference.ListPreference
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyle
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyles
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "w393dp-h851dp-port-440dpi")
class KeyboardStyleSettingsUatTest {

    @get:Rule
    val prefs = PrefsRule()

    private var settings: SettingsHarness? = null

    @After
    fun close() {
        settings?.close()
    }

    private fun s(id: Int) = appContext.getString(id)

    private fun opened() = SettingsHarness().also { settings = it }.apply {
        open(SettingsRoute.Theme)
        tab(1)
    }

    private fun ListPreference.pick(style: KeyboardStyle) {
        val v = style.ordinal.toString()
        if (callChangeListener(v)) value = v
    }

    @Test
    @Uat("ST-140")
    fun theTwoStylesInPlaceOfTheThree() {
        prefs.setStyle(KeyboardStyle.NORMAL)
        val st = opened()
        val titles = st.titles()
        assertTrue(titles.toString(), s(R.string.keyboard_style_portrait) in titles && s(R.string.keyboard_style_landscape) in titles)
        assertFalse(titles.toString(), s(R.string.one_handed) in titles || s(R.string.floating_keyboard) in titles)
        assertFalse(st.pref(R.string.split_dock).isEnabled)
        val turned = st.pref(R.string.keyboard_style_landscape) as ListPreference
        turned.pick(KeyboardStyle.SPLIT)
        turned.pick(KeyboardStyle.FLOATING)
        st.idle()
        assertEquals("FLOATING" to "SPLIT", ThemeManager.prefs.keyboardStyleLandscape.getValue() to ThemeManager.prefs.keyboardStyleUnderLandscape.getValue())
        assertEquals("NORMAL", ThemeManager.prefs.keyboardStylePortrait.getValue())
        assertTrue(st.pref(R.string.split_dock).isEnabled)
    }

    // not set: split as this phone turned shows it; the page opened stores nothing; picked as it is, stored
    @Test
    @Uat("ST-140")
    fun notSetShowsTheTurnedScreensOwn() {
        prefs.setStyleDefault()
        val st = opened()
        st.idle()
        val turned = st.pref(R.string.keyboard_style_landscape) as ListPreference
        assertEquals(s(R.string.keyboard_style_split), turned.summary.toString())
        assertEquals(s(R.string.keyboard_style_normal), st.pref(R.string.keyboard_style_portrait).summary.toString())
        val store = ThemeManager.prefs
        assertEquals("opened, nothing stored", listOf("", "", "", ""), listOf(store.keyboardStylePortrait, store.keyboardStyleUnderPortrait, store.keyboardStyleLandscape, store.keyboardStyleUnderLandscape).map { it.getValue() })
        turned.pick(KeyboardStyle.SPLIT)
        assertEquals("SPLIT", store.keyboardStyleLandscape.getValue())
        assertEquals("", store.keyboardStylePortrait.getValue())
    }

    @Test
    @Uat("ST-140")
    fun aStyleChangedFromTheKeyboardShowsAtOnce() {
        prefs.setStyleDefault()
        val st = opened()
        val turned = st.pref(R.string.keyboard_style_landscape) as ListPreference
        KeyboardStyles.toggleFloating(KeyboardArea.screens(appContext).second)
        st.idle()
        assertEquals(s(R.string.keyboard_style_floating), turned.summary.toString())
        assertEquals(s(R.string.keyboard_style_normal), st.pref(R.string.keyboard_style_portrait).summary.toString())
    }
}
