/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.Context
import androidx.core.content.edit
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The theme page's level lists read the keys its preferences write, and what they set is not put
 * back to its default with the settings that have no UI.
 */
@RunWith(RobolectricTestRunner::class)
// the real application: the keyboard's size is AppPrefs', and a theme preference reaches the theme manager
@Config(application = FcitxApplication::class)
class ThemePrefsTest {

    private val sp = RuntimeEnvironment.getApplication().getSharedPreferences("theme-prefs-test", Context.MODE_PRIVATE)

    @Before
    fun empty() {
        sp.edit { clear() }
    }

    private fun ThemePrefs.level(key: String) =
        managedPreferencesUi.filterIsInstance<ManagedPreferenceUi.Levels>().single { it.key == key }.current()

    @Test
    fun aLevelListShowsTheLevelOfWhatItsPreferencesHold() {
        val prefs = ThemePrefs(sp)
        // untouched: standard height, no padding, standard spacing, rounded keys
        val lists = listOf(
            "keyboard_height_level", "keyboard_side_padding_level", "keyboard_bottom_padding_level",
            "key_spacing_level", "key_radius_level",
        )
        // 贴底 is first, before 无
        assertEquals(listOf(1, 0, 1, 1, 1), lists.map { prefs.level(it) })
        prefs.keyRadius.setValue(10)
        listOf(prefs.keyHorizontalMargin, prefs.keyHorizontalMarginLandscape).forEach { it.setValue(5) }
        prefs.keyVerticalMargin.setValue(9)
        prefs.keyVerticalMarginLandscape.setValue(6)
        assertEquals(2, prefs.level("key_radius_level"))
        assertEquals(2, prefs.level("key_spacing_level"))
    }

    // 贴底 differs from 无 only in the flush key: each is told apart, and picking one sets it
    @Test
    fun theBottomPaddingLevelsTellFlushFromNone() {
        val list = ThemePrefs(sp).managedPreferencesUi.filterIsInstance<ManagedPreferenceUi.Levels>()
            .single { it.key == "keyboard_bottom_padding_level" }
        val held = { listOf("keyboard_bottom_padding", "keyboard_bottom_padding_landscape", "keyboard_bottom_flush").map { sp.getInt(it, -1) } }
        list.pick(0)
        assertEquals(listOf(0, 0, 1), held())
        assertEquals(0, list.current())
        list.pick(1)
        assertEquals(listOf(0, 0, 0), held())
        assertEquals(1, list.current())
        list.pick(3)
        assertEquals(listOf(20, 12, 0), held())
        assertEquals(3, list.current())
    }

    // AppPrefs.registerProvider resets these hidden settings as ThemeManager registers ThemePrefs, after the reset
    // at the start (AppPrefsTest.aCategoryRegisteredAfterTheResetIsResetAsItRegisters): what a level sets must outlive it
    @Test
    fun whatALevelSetsIsKeptWhenTheHiddenSettingsGoBackToTheirDefaults() {
        val prefs = ThemePrefs(sp)
        val set = listOf(
            prefs.keyHorizontalMargin, prefs.keyHorizontalMarginLandscape,
            prefs.keyVerticalMargin, prefs.keyVerticalMarginLandscape, prefs.keyRadius,
        )
        set.forEach { it.setValue(9) }
        prefs.keyRippleEffect.setValue(true)
        sp.edit { prefs.resetHidden(this) }
        assertEquals(List(set.size) { 9 }, set.map { it.getValue() })
        assertFalse("not a level's", prefs.keyRippleEffect.getValue())
    }
}
