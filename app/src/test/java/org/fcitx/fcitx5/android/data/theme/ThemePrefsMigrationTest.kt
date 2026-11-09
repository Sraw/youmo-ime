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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 0.6.0's 跟随主题 of the keyboard's size (060-render-p3 T5): an upgrade's size at its default follows
 * the theme, one set stays, once; a backup of an older version brought back is moved again.
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
