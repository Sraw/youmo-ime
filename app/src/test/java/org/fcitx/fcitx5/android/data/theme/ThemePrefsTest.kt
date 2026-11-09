/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.Context
import androidx.core.content.edit
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceUi
import org.fcitx.fcitx5.android.input.theme.LegacyLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val lists = listOf("keyboard_height_level", "keyboard_side_padding_level", "keyboard_bottom_padding_level")
        // untouched: 跟随主题, first (060-render-p3 T5)
        assertEquals(listOf(0, 0, 0), lists.map { prefs.level(it) })
        // not following: standard height (after 跟随主题, 紧凑 and 矮), no padding; 贴底 is before 无
        sp.edit {
            listOf("keyboard_height_follow_theme", "keyboard_side_padding_follow_theme", "keyboard_bottom_padding_follow_theme")
                .forEach { putBoolean(it, false) }
        }
        assertEquals(listOf(3, 1, 2), lists.map { prefs.level(it) })
    }

    // 跟随主题 picked: set, the numbers back to their defaults (today's look where the theme sets none);
    // a level picked: no more following
    @Test
    fun followingTheThemeIsPickedAndLeft() {
        val list = ThemePrefs(sp).managedPreferencesUi.filterIsInstance<ManagedPreferenceUi.Levels>()
            .single { it.key == "keyboard_side_padding_level" }
        list.pick(3)
        assertFalse(sp.getBoolean("keyboard_side_padding_follow_theme", true))
        assertEquals(24 to 120, sp.getInt("keyboard_side_padding", -1) to sp.getInt("keyboard_side_padding_landscape", -1))
        assertEquals(3, list.current())
        list.pick(0)
        assertTrue(sp.getBoolean("keyboard_side_padding_follow_theme", false))
        assertFalse(sp.contains("keyboard_side_padding"))
        assertFalse(sp.contains("keyboard_side_padding_landscape"))
        assertEquals(0, list.current())
        // 无 is the defaults, yet a choice of its own: not following
        list.pick(1)
        assertFalse(sp.getBoolean("keyboard_side_padding_follow_theme", true))
        assertEquals(1, list.current())
        assertFalse(list.pick(4))
    }

    // 紧凑 the lowest, first, each of its numbers ime-core's: the keyboard reads 紧凑 by those
    @Test
    @Config(qualifiers = "zh-rCN")
    fun theHeightLevelsStartAtCompact() {
        val list = ThemePrefs(sp).managedPreferencesUi.filterIsInstance<ManagedPreferenceUi.Levels>()
            .single { it.key == "keyboard_height_level" }
        val app = RuntimeEnvironment.getApplication()
        assertEquals(listOf("跟随主题", "紧凑", "矮", "标准", "高", "很高"), list.createUi(app).entries.map { it.toString() })
        // ThemePrefs(sp) keeps them in sp
        val held = { sp.getInt("keyboard_height_percent", -1) to sp.getInt("keyboard_height_percent_landscape", -1) }
        list.pick(1)
        assertEquals(20 to 34, held())
        assertEquals(1, list.current())
        list.pick(2)
        assertEquals(26 to 42, held())
    }

    // 贴底 differs from 无 only in the flush key: each is told apart, and picking one sets it
    @Test
    fun theBottomPaddingLevelsTellFlushFromNone() {
        val list = ThemePrefs(sp).managedPreferencesUi.filterIsInstance<ManagedPreferenceUi.Levels>()
            .single { it.key == "keyboard_bottom_padding_level" }
        val held = { listOf("keyboard_bottom_padding", "keyboard_bottom_padding_landscape", "keyboard_bottom_flush").map { sp.getInt(it, -1) } }
        list.pick(1)
        assertEquals(listOf(0, 0, 1), held())
        assertEquals(1, list.current())
        list.pick(2)
        assertEquals(listOf(0, 0, 0), held())
        assertEquals(2, list.current())
        list.pick(4)
        assertEquals(listOf(20, 12, 0), held())
        assertEquals(4, list.current())
    }

    // the key style settings before 3.0 are no preferences now, only what ThemeLookMigration reads: the
    // reset of the hidden settings as ThemeManager registers ThemePrefs leaves them for it
    @Test
    fun theOldKeyStyleSettingsOutliveTheResetOfTheHiddenOnes() {
        val prefs = ThemePrefs(sp)
        sp.edit(commit = true) {
            putBoolean("key_border", false)
            putInt("key_radius", 9)
        }
        sp.edit { prefs.resetHidden(this) }
        assertEquals(LegacyLook(keyBorder = false, keyRadius = 9), prefs.legacyLook())
    }

    // the three over the theme, shown, then the line on following it; 0.5.0's keys are not set here (the migration's)
    @Test
    fun theKeyStyleSectionIsItsThreeAndALine() {
        val keys = ThemePrefs(sp).managedPreferencesUi.map { it.key }
        val note = keys.indexOf("key_style_note")
        assertTrue(note >= 0)
        for (shown in listOf("key_border_choice", "key_spacing_choice", "key_radius_choice")) {
            assertTrue(shown, keys.indexOf(shown) in 0 until note)
        }
        for (gone in listOf("key_border", "key_spacing_level", "key_radius_level")) assertFalse(gone, gone in keys)
    }
}
