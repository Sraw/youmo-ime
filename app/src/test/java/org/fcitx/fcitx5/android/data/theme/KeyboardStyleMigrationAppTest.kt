/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 0.6.0's 分体键盘, 单手模式 and 悬浮键盘 as the two shapes' 键盘样式 (061-wide-keyboard-style §6), as stored:
 * the table itself is KeyboardStyleTest's (ime-core). Once, the old keys kept for 0.6.0, before the first
 * unlock as well.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class KeyboardStyleMigrationAppTest {

    private val sp = RuntimeEnvironment.getApplication().getSharedPreferences("keyboard-style-migration-test", Context.MODE_PRIVATE)

    private val keys = listOf("keyboard_style_portrait", "keyboard_style_portrait_under", "keyboard_style_landscape", "keyboard_style_landscape_under")

    private fun stored(on: android.content.SharedPreferences = sp) = keys.map { if (on.contains(it)) on.getString(it, null) else null }

    @Before
    fun empty() {
        sp.edit(commit = true) { clear() }
    }

    @Test
    @Uat("PR-59")
    fun onTheWideScreenTheLandscapeOneIsNotWritten() {
        // 屏幕够宽时 (0.6.0's default, not stored) and 单手 at the left
        sp.edit(commit = true) { putString("one_handed", "Left") }
        val prefs = ThemePrefs(sp)
        prefs.migrateKeyboardStyle()
        assertEquals(listOf("ONE_HANDED", "NORMAL", null, null), stored())
        assertFalse(sp.contains("keyboard_style_landscape"))
        assertEquals("Left", sp.getString("one_handed_side", null))
        assertTrue(prefs.keyboardStyleMigrated)
        // 0.6.0's kept, for a backup of this version brought back into it
        assertEquals("Left", sp.getString("one_handed", null))
    }

    @Test
    @Uat("PR-59")
    fun alwaysSplitFloatingIsFloatingOverSplitBothWays() {
        sp.edit(commit = true) {
            putString("split_keyboard", "Always")
            putString("one_handed", "Right")
            putBoolean("floating_keyboard", true)
        }
        ThemePrefs(sp).migrateKeyboardStyle()
        assertEquals(listOf("FLOATING", "SPLIT", "FLOATING", "SPLIT"), stored())
        assertTrue(sp.getBoolean("floating_keyboard", false))
    }

    @Test
    fun aNewInstallStoresNoStyle() {
        ThemePrefs(sp).migrateKeyboardStyle()
        assertEquals(listOf<String?>(null, null, null, null), stored())
        assertTrue(sp.getBoolean(ThemePrefs.KEYBOARD_STYLE_MIGRATED, false))
        assertNull(sp.getString("one_handed_side", null))
    }

    @Test
    @Uat("PR-59")
    fun onlyOnceAndAStyleStoredIsKept() {
        sp.edit(commit = true) { putString("split_keyboard", "Off") }
        val prefs = ThemePrefs(sp)
        prefs.migrateKeyboardStyle()
        assertEquals(listOf("NORMAL", "NORMAL", "NORMAL", "NORMAL"), stored())
        // the user then picks 悬浮 turned; 0.6.0's three as they were: not moved again
        sp.edit(commit = true) { putString("keyboard_style_landscape", "FLOATING") }
        prefs.migrateKeyboardStyle()
        assertEquals("FLOATING", sp.getString("keyboard_style_landscape", null))
        // no mark (a damaged file) but a shape's style there: that shape kept, the other moved
        sp.edit(commit = true) { remove(ThemePrefs.KEYBOARD_STYLE_MIGRATED); remove("keyboard_style_portrait"); remove("keyboard_style_portrait_under") }
        prefs.migrateKeyboardStyle()
        assertEquals(listOf("NORMAL", "NORMAL", "FLOATING", "NORMAL"), stored())
    }

    @Test
    fun anOldValueUnreadableIsItsDefault() {
        // 单手模式 as a number, 悬浮 as a string: 0.6.0's defaults, off; 分体键盘 of no name it had, 屏幕够宽时
        sp.edit(commit = true) {
            putInt("one_handed", 1)
            putString("floating_keyboard", "yes")
            putString("split_keyboard", "Sometimes")
        }
        ThemePrefs(sp).migrateKeyboardStyle()
        assertEquals(listOf("NORMAL", "NORMAL", null, null), stored())
    }

    @Test
    fun aStyleKeyUnreadableIsNone() {
        sp.edit(commit = true) { putInt("keyboard_style_portrait", 3) }
        assertEquals("", ThemePrefs(sp).keyboardStylePortrait.getValue())
    }

    @Test
    fun beforeTheFirstUnlockTheCopyHasTheStylesAndTheMark() {
        val app = RuntimeEnvironment.getApplication()
        val device = PreferenceManager.getDefaultSharedPreferences(app.createDeviceProtectedStorageContext())
        val prefs = ThemeManager.prefs
        assertTrue("moved as it started", prefs.keyboardStyleMigrated)
        // 0.6.0's copy: the old keys only, a style of before there
        device.edit(commit = true) {
            remove(ThemePrefs.KEYBOARD_STYLE_MIGRATED)
            putString("split_keyboard", "Always")
            putString("keyboard_style_landscape", "SPLIT")
        }
        // ThemeManager's prefs outlive the test: both styles put back as they were (R26 F8400)
        val portraitWas = prefs.keyboardStylePortrait.getValue()
        val landscapeWas = prefs.keyboardStyleLandscape.getValue()
        prefs.keyboardStylePortrait.setValue("FLOATING")
        prefs.keyboardStyleLandscape.setValue("")
        try {
            ThemeManager.syncToDeviceEncryptedStorage()
            assertTrue(device.getBoolean(ThemePrefs.KEYBOARD_STYLE_MIGRATED, false))
            assertEquals("FLOATING", device.getString("keyboard_style_portrait", null))
            // not stored here, not stored there: its default by the width before the unlock too
            assertFalse(device.contains("keyboard_style_landscape"))
        } finally {
            prefs.keyboardStylePortrait.setValue(portraitWas)
            prefs.keyboardStyleLandscape.setValue(landscapeWas)
        }
    }
}
