/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import androidx.core.content.edit
import org.fcitx.fcitx5.android.input.keyboard.LangSwitchBehavior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A setting moved out of the settings page kept what an older version or a backup set, with
 * nothing left to change it back; one a level still sets must keep it.
 */
@RunWith(RobolectricTestRunner::class)
class AppPrefsTest {

    private val sp = RuntimeEnvironment.getApplication().getSharedPreferences("app-prefs-test", Context.MODE_PRIVATE)

    @Before
    fun empty() {
        sp.edit { clear() }
    }

    @Test
    fun settingsWithoutAUiGoBackToTheirDefaultsOnceAndAgainAfterAnImport() {
        sp.edit {
            putBoolean("ignore_system_cursor", true)
            putString("lang_switch_key_behavior", LangSwitchBehavior.ToggleActivate.name)
            putInt("candidates_window_radius", 12)
        }
        val prefs = AppPrefs(sp)
        prefs.resetHiddenSettings()
        assertFalse(prefs.advanced.ignoreSystemCursor.getValue())
        assertEquals(LangSwitchBehavior.Enumerate, prefs.keyboard.langSwitchKeyBehavior.getValue())
        assertEquals(0, prefs.candidates.windowRadius.getValue())
        sp.edit { putBoolean("ignore_system_cursor", true) }
        prefs.resetHiddenSettings()
        assertTrue("once", prefs.advanced.ignoreSystemCursor.getValue())
        prefs.resetHiddenSettings(again = true)
        assertFalse(prefs.advanced.ignoreSystemCursor.getValue())
    }

    @Test
    fun whatTheSettingsPageStillSetsIsKept() {
        // each a level's: the keyboard's size on the theme page, the long press, the vibration, the sound
        val levels = mapOf(
            "keyboard_height_percent" to 38, "keyboard_height_percent_landscape" to 60,
            "keyboard_side_padding" to 8, "keyboard_side_padding_landscape" to 48,
            "keyboard_bottom_padding" to 20, "keyboard_bottom_padding_landscape" to 12,
            "keyboard_long_press_delay" to 450,
            "button_vibration_press_milliseconds" to 35, "button_vibration_long_press_milliseconds" to 45,
            "button_vibration_press_amplitude" to 220, "button_vibration_long_press_amplitude" to 255,
            "button_sound_volume" to 90,
        )
        sp.edit {
            levels.forEach { (key, value) -> putInt(key, value) }
            putBoolean("popup_on_key_press", false)
        }
        val prefs = AppPrefs(sp)
        prefs.resetHiddenSettings()
        assertEquals(levels, levels.mapValues { sp.getInt(it.key, -1) })
        assertFalse("shown", prefs.keyboard.popupOnKeyPress.getValue())
    }
}
