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

/** Old 分体键盘, 单手模式 and 悬浮键盘 become 键盘样式 once (also before unlock); the table itself is KeyboardStyleTest's. */
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
        // 屏幕够宽时 (the old default, unstored) and 单手 at the left
        sp.edit(commit = true) { putString("one_handed", "Left") }
        val prefs = ThemePrefs(sp)
        prefs.migrateKeyboardStyle()
        assertEquals(listOf("ONE_HANDED", "NORMAL", null, null), stored())
        assertFalse(sp.contains("keyboard_style_landscape"))
        assertEquals("Left", sp.getString("one_handed_side", null))
        assertTrue(prefs.keyboardStyleMigrated)
        // the old keys kept, for a backup brought back to an old version
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
        // a later pick is not moved again
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
        // 单手模式 as a number, 悬浮 as a string, 分体键盘 an unknown name
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
        // the old keys only
        device.edit(commit = true) {
            remove(ThemePrefs.KEYBOARD_STYLE_MIGRATED)
            putString("split_keyboard", "Always")
            putString("keyboard_style_landscape", "SPLIT")
        }
        // ThemeManager's prefs outlive the test: put back
        val portraitWas = prefs.keyboardStylePortrait.getValue()
        val landscapeWas = prefs.keyboardStyleLandscape.getValue()
        prefs.keyboardStylePortrait.setValue("FLOATING")
        prefs.keyboardStyleLandscape.setValue("")
        try {
            ThemeManager.syncToDeviceEncryptedStorage()
            assertTrue(device.getBoolean(ThemePrefs.KEYBOARD_STYLE_MIGRATED, false))
            assertEquals("FLOATING", device.getString("keyboard_style_portrait", null))
            // its default by the width before the unlock too
            assertFalse(device.contains("keyboard_style_landscape"))
        } finally {
            prefs.keyboardStylePortrait.setValue(portraitWas)
            prefs.keyboardStyleLandscape.setValue(landscapeWas)
        }
    }
}
