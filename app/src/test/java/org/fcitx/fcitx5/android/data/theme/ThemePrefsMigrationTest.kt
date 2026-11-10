/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.FcitxApplication
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
 * 0.6.0's 跟随主题 of the keyboard's size (060-render-p3 T5): an upgrade's size at its default follows
 * the theme, one set stays, once; a backup of an older version brought back is moved again. And the
 * themes the settings name (T3): a built-in one by its 0.5 name names it as it is now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemePrefsMigrationTest {

    private val sp = RuntimeEnvironment.getApplication().getSharedPreferences("theme-prefs-migration-test", Context.MODE_PRIVATE)

    private val follows = listOf("keyboard_height_follow_theme", "keyboard_side_padding_follow_theme", "keyboard_bottom_padding_follow_theme")

    private fun following() = follows.map { sp.getBoolean(it, true) }

    @Before
    fun empty() {
        sp.edit(commit = true) { clear() }
    }

    @Test
    fun aSizeAtItsDefaultFollowsTheTheme() {
        // an older version's, untouched: nothing of the sizes stored, or each at its default
        sp.edit(commit = true) {
            putInt("keyboard_height_percent", 30)
            putInt("keyboard_bottom_flush", 0)
        }
        ThemePrefs(sp).migrateSizeFollow()
        assertEquals(listOf(true, true, true), following())
        // written, not left to the defaults: a later default changed would not undo it
        assertTrue(follows.all { sp.contains(it) })
        assertTrue(sp.getBoolean(ThemePrefs.SIZES_FOLLOW_MIGRATED, false))
    }

    @Test
    fun aSizeSetIsKept() {
        sp.edit(commit = true) {
            // 很高, as 0.5 set it; the side padding untouched; 贴底, its numbers the defaults but the flush not
            putInt("keyboard_height_percent", 38)
            putInt("keyboard_height_percent_landscape", 60)
            putInt("keyboard_bottom_flush", 1)
        }
        ThemePrefs(sp).migrateSizeFollow()
        assertEquals(listOf(false, true, false), following())
        assertEquals(38, sp.getInt("keyboard_height_percent", -1))
        assertEquals(60, sp.getInt("keyboard_height_percent_landscape", -1))
        assertEquals(1, sp.getInt("keyboard_bottom_flush", -1))
        // only the landscape number set is set too
        sp.edit(commit = true) { clear(); putInt("keyboard_side_padding_landscape", 48) }
        ThemePrefs(sp).migrateSizeFollow()
        assertEquals(listOf(true, false, true), following())
    }

    @Test
    fun onlyOnce() {
        val prefs = ThemePrefs(sp)
        prefs.migrateSizeFollow()
        assertEquals(listOf(true, true, true), following())
        // the user then picks 很高 (following no more) and back to 跟随主题 of the side padding after picking 宽
        sp.edit(commit = true) {
            putBoolean("keyboard_height_follow_theme", false)
            putInt("keyboard_height_percent", 38)
            putBoolean("keyboard_side_padding_follow_theme", true)
        }
        // the next start's: as the user left it, not moved again (the height not following, though set)
        ThemePrefs(sp).migrateSizeFollow()
        assertEquals(listOf(false, true, true), following())
        // nor a size at its default not following made to follow
        sp.edit(commit = true) { putBoolean("keyboard_bottom_padding_follow_theme", false) }
        ThemePrefs(sp).migrateSizeFollow()
        assertEquals(listOf(false, true, false), following())
    }

    @Test
    fun beforeTheFirstUnlockADefaultLevelPickedIsKept() {
        // the device's copy (ThemeManager.syncToDeviceEncryptedStorage) of a version that did not copy the
        // mark: 标准 picked, at the defaults' numbers, not following; the start before the unlock reads it
        sp.edit(commit = true) {
            putBoolean("keyboard_height_follow_theme", false)
            putInt("keyboard_height_percent", 30)
            putInt("keyboard_height_percent_landscape", 49)
            putBoolean("keyboard_side_padding_follow_theme", true)
            putBoolean("keyboard_bottom_padding_follow_theme", true)
        }
        ThemePrefs(sp).migrateSizeFollow()
        assertEquals(listOf(false, true, true), following())
        assertTrue(sp.getBoolean(ThemePrefs.SIZES_FOLLOW_MIGRATED, false))
    }

    @Test
    fun theMarkIsCopiedForBeforeTheFirstUnlock() {
        // the app's own, moved as it started
        assertTrue(ThemeManager.prefs.sizeFollowMigrated)
        val app = RuntimeEnvironment.getApplication()
        val device = PreferenceManager.getDefaultSharedPreferences(app.createDeviceProtectedStorageContext())
        device.edit(commit = true) { remove(ThemePrefs.SIZES_FOLLOW_MIGRATED) }
        ThemeManager.syncToDeviceEncryptedStorage()
        assertTrue(device.getBoolean(ThemePrefs.SIZES_FOLLOW_MIGRATED, false))
    }

    // region the themes' references (T3)

    private val slots = listOf("normal_mode_theme", "light_mode_theme", "dark_mode_theme")

    private fun stored(on: android.content.SharedPreferences = sp) = slots.map { on.getString(it, null) }

    private fun store(vararg names: String?, follow: Boolean? = null, on: android.content.SharedPreferences = sp) = on.edit(commit = true) {
        slots.zip(names).forEach { (key, name) -> if (name == null) remove(key) else putString(key, name) }
        if (follow != null) putBoolean("follow_system_dark_mode", follow)
    }

    @Test
    fun aBuiltInThemeNotFollowingTheSystemIsTheVersionItWas() {
        store("PixelLight", follow = false)
        val prefs = ThemePrefs(sp)
        prefs.migrateThemeRefs(emptySet())
        assertEquals(listOf("pixel#light", null, null), stored())
        assertFalse(prefs.followSystemDayNightTheme.getValue())
        // the same colours as 0.5's PixelLight: the file's light version
        assertEquals(TestThemes.PixelLight, prefs.normalModeTheme.getValue())
    }

    @Test
    fun theLightAndDarkSlotsFollowingTheSystemAreTheVersionsTheyWere() {
        store("Monokai", "MaterialLight", "NordDark", follow = true)
        val prefs = ThemePrefs(sp)
        prefs.migrateThemeRefs(emptySet())
        assertEquals(listOf("monokai", "material#light", "nord#dark"), stored())
        assertTrue(prefs.followSystemDayNightTheme.getValue())
        assertEquals(TestThemes.MaterialLight, prefs.lightModeTheme.getValue())
        assertEquals(TestThemes.NordDark, prefs.darkModeTheme.getValue())
        assertEquals(TestThemes.Monokai, prefs.normalModeTheme.getValue())
    }

    @Test
    fun monetsTwoAreMonetsVersions() {
        store("MonetDark", "MonetLight", "MonetDark", follow = true)
        val prefs = ThemePrefs(sp)
        prefs.migrateThemeRefs(emptySet())
        assertEquals(listOf("monet#dark", "monet#light", "monet#dark"), stored())
        val light = prefs.lightModeTheme.getValue() as Theme.Builtin
        val dark = prefs.darkModeTheme.getValue() as Theme.Builtin
        assertTrue(light.followsSystem && !light.isDark)
        assertTrue(dark.followsSystem && dark.isDark)
        assertEquals(ThemeManager.builtin("monet#dark"), prefs.normalModeTheme.getValue())
    }

    @Test
    fun everyOldNameIsABuiltInThemeOfItsLightness() {
        for ((old, ref) in BuiltinThemeFiles.LEGACY) {
            val theme = ThemeManager.builtin(ref)
            assertEquals(old, !old.endsWith("Light"), theme.isDark)
            assertEquals(old, old.startsWith("Monet"), theme.followsSystem)
            assertEquals(old, BuiltinThemeFiles.legacyName(ref))
        }
        // each built-in theme had one: every user's is one of them
        assertEquals(ThemeManager.BuiltinThemes.map { it.name }.toSet(), BuiltinThemeFiles.LEGACY.values.toSet())
    }

    @Test
    fun aDeletedThemesNameIsLeftAndReadAsTheDefault() {
        store("0f6c2a4e-deleted", "MaterialLight", null, follow = true)
        val prefs = ThemePrefs(sp)
        prefs.migrateThemeRefs(emptySet())
        assertEquals(listOf("0f6c2a4e-deleted", "material#light", null), stored())
        assertEquals(ThemeManager.DefaultTheme, prefs.normalModeTheme.getValue())
        assertEquals(prefs.darkModeTheme.defaultValue, prefs.darkModeTheme.getValue())
    }

    @Test
    fun aThemeOfTheUsersOfAnOldNameIsTheUsers() {
        // the user's own named so (imported as no built-in one had the name, MonetLight's say): theirs, as before
        store("MonetLight", "PixelLight")
        ThemePrefs(sp).migrateThemeRefs(setOf("MonetLight"))
        assertEquals(listOf("MonetLight", "pixel#light", null), stored())
    }

    @Test
    fun anOldNameNotMovedYetIsReadAsTheBuiltInTheme() {
        // left as a theme of the user's may have it (its json unread, the directory unread: ThemeManager's start)
        store("MonetLight", "PixelLight", "AMOLEDBlack")
        val prefs = ThemePrefs(sp)
        prefs.migrateThemeRefs(setOf("MonetLight", "PixelLight", "AMOLEDBlack"))
        assertEquals(listOf("MonetLight", "PixelLight", "AMOLEDBlack"), stored())
        assertEquals(ThemeManager.builtin("monet#light"), prefs.normalModeTheme.getValue())
        assertEquals(ThemeManager.builtin("pixel#light"), prefs.lightModeTheme.getValue())
        assertEquals(ThemeManager.builtin("amoled-black"), prefs.darkModeTheme.getValue())
    }

    @Test
    fun movedAtEveryStartNothingTwice() {
        store("DeepBlue", "nord#light", "AMOLEDBlack")
        val prefs = ThemePrefs(sp)
        prefs.migrateThemeRefs(emptySet())
        assertEquals(listOf("deep-blue", "nord#light", "amoled-black"), stored())
        prefs.migrateThemeRefs(emptySet())
        assertEquals(listOf("deep-blue", "nord#light", "amoled-black"), stored())
        // a backup of an older version brought back: moved at the next start
        store("PixelDark", "PixelLight", "PixelDark")
        prefs.migrateThemeRefs(emptySet())
        assertEquals(listOf("pixel#dark", "pixel#light", "pixel#dark"), stored())
        // a value of a damaged file, no string: left, read as the default
        sp.edit(commit = true) { putInt("normal_mode_theme", 3) }
        prefs.migrateThemeRefs(emptySet())
        assertEquals(3, sp.getInt("normal_mode_theme", 0))
    }

    @Test
    fun beforeTheFirstUnlockTheCopyIsMovedAsItIsRead() {
        val app = RuntimeEnvironment.getApplication()
        val device = PreferenceManager.getDefaultSharedPreferences(app.createDeviceProtectedStorageContext())
        // the copy an older version made: its names; read so before the first unlock (no theme of the user's listed)
        store("NordLight", "MonetLight", "MonetDark", follow = true, on = device)
        ThemePrefs(device).migrateThemeRefs(emptySet())
        assertEquals(listOf("nord#light", "monet#light", "monet#dark"), stored(device))
        // the app's own, copied over it: as it names them now
        val prefs = ThemeManager.prefs
        val before = slots.map { PreferenceManager.getDefaultSharedPreferences(app).getString(it, null) }
        try {
            store("MaterialDark", "MonokAI-unknown", "PixelDark", on = PreferenceManager.getDefaultSharedPreferences(app))
            prefs.migrateThemeRefs(emptySet())
            ThemeManager.syncToDeviceEncryptedStorage()
            assertEquals(listOf("material#dark", prefs.lightModeTheme.defaultValue.name, "pixel#dark"), stored(device))
        } finally {
            store(*before.toTypedArray(), on = PreferenceManager.getDefaultSharedPreferences(app))
        }
    }

    // endregion

    @Test
    fun aNumberNotAnIntIsTakenAsSet() {
        // a damaged file's: no crash at the start, the height kept as it is, not following
        sp.edit(commit = true) {
            putString("keyboard_height_percent", "38")
            putBoolean("keyboard_side_padding_landscape", true)
        }
        ThemePrefs(sp).migrateSizeFollow()
        assertEquals(listOf(false, false, true), following())
        assertEquals("38", sp.getString("keyboard_height_percent", null))
        assertTrue(sp.getBoolean(ThemePrefs.SIZES_FOLLOW_MIGRATED, false))
    }

    @Test
    fun aBackupOfAnOlderVersionBroughtBackIsMovedAgain() {
        ThemePrefs(sp).migrateSizeFollow()
        assertEquals(listOf(true, true, true), following())
        // the import puts the backup's file in place of this one (UserDataManager): an older version's has
        // no mark and no 跟随主题, a side padding set
        sp.edit(commit = true) {
            clear()
            putInt("keyboard_side_padding", 8)
            putInt("keyboard_side_padding_landscape", 48)
        }
        ThemePrefs(sp).migrateSizeFollow()
        assertEquals(listOf(true, false, true), following())
        assertEquals(8, sp.getInt("keyboard_side_padding", -1))
        assertTrue(sp.getBoolean(ThemePrefs.SIZES_FOLLOW_MIGRATED, false))
        // a backup of this version's brought back keeps what it says
        sp.edit(commit = true) {
            clear()
            putBoolean(ThemePrefs.SIZES_FOLLOW_MIGRATED, true)
            putBoolean("keyboard_height_follow_theme", false)
        }
        ThemePrefs(sp).migrateSizeFollow()
        assertEquals(listOf(false, true, true), following())
    }
}
