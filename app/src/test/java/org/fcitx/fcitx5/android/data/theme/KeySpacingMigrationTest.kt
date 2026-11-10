/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.Context
import androidx.core.content.edit
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.KeyHSpacingChoice
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.KeyVSpacingChoice
import org.fcitx.fcitx5.android.input.theme.KeyStyleChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 0.5.2's one 按键间距 (`key_spacing_choice`) moved into 0.6.0's two, 横向间距 and 纵向间距, each the same
 * level; a backup of 0.5.2 or 0.6.0-beta1 brought back the same at the next start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class KeySpacingMigrationTest {

    private val sp = RuntimeEnvironment.getApplication().getSharedPreferences("key-spacing-migration-test", Context.MODE_PRIVATE)

    @Before
    fun empty() {
        sp.edit(commit = true) { clear() }
    }

    private fun migrated(): ThemePrefs = ThemePrefs(sp).also { it.migrateKeySpacing() }

    @Uat("ST-95")
    @Test
    fun eachLevelOfTheOneIsTheSameLevelOfBoth() {
        for (level in listOf("FollowTheme", "Compact", "Standard", "Loose")) {
            sp.edit(commit = true) { clear(); putString(ThemePrefs.KEY_SPACING_052, level) }
            val prefs = migrated()
            assertEquals(level, KeyHSpacingChoice.valueOf(level), prefs.keyHSpacingChoice.getValue())
            assertEquals(level, KeyVSpacingChoice.valueOf(level), prefs.keyVSpacingChoice.getValue())
            assertFalse(level, sp.contains(ThemePrefs.KEY_SPACING_052))
        }
        // what it drew before, it draws now: 0.5.2's 宽松 was 5 across and 9 (6) up and down
        sp.edit(commit = true) { clear(); putString(ThemePrefs.KEY_SPACING_052, "Loose") }
        val choice = migrated().keyStyleChoice()
        assertEquals(KeyStyleChoice.Spacing(5, 5) to KeyStyleChoice.Spacing(9, 6), choice.hGap to choice.vGap)
    }

    // the theme manager's first read of the key style (a start after an upgrade, a restored 0.5.2 backup's
    // restart) is of the two moved already: 0.5.2's 宽松, not following the theme
    @Test
    fun theThemeManagerMovesTheOneBeforeItReadsTheKeyStyle() {
        sp.edit(commit = true) { putString(ThemePrefs.KEY_SPACING_052, "Loose") }
        val choice = ThemeManager.startKeyStyle(ThemePrefs(sp))
        assertEquals(KeyStyleChoice.Spacing(5, 5) to KeyStyleChoice.Spacing(9, 6), choice.hGap to choice.vGap)
        assertFalse(sp.contains(ThemePrefs.KEY_SPACING_052))
    }

    @Test
    fun oneOfTheTwoStoredAlreadyIsKept() {
        sp.edit(commit = true) {
            putString(ThemePrefs.KEY_SPACING_052, "Loose")
            putString(ThemePrefs.KEY_V_SPACING, "Compact")
        }
        val prefs = migrated()
        assertEquals(KeyHSpacingChoice.Loose, prefs.keyHSpacingChoice.getValue())
        assertEquals(KeyVSpacingChoice.Compact, prefs.keyVSpacingChoice.getValue())
        assertFalse(sp.contains(ThemePrefs.KEY_SPACING_052))
    }

    @Test
    fun aValueNotALevelIsFollowingAndDropped() {
        // a damaged backup, a hand edit: not a level's name, or not a string at all; never a crash at every start
        sp.edit(commit = true) { putString(ThemePrefs.KEY_SPACING_052, "Huge") }
        migrated().let {
            assertEquals(KeyHSpacingChoice.FollowTheme, it.keyHSpacingChoice.getValue())
            assertEquals(KeyVSpacingChoice.FollowTheme, it.keyVSpacingChoice.getValue())
        }
        sp.edit(commit = true) { clear(); putInt(ThemePrefs.KEY_SPACING_052, 2) }
        migrated().let { assertEquals(KeyStyleChoice.FOLLOW, it.keyStyleChoice()) }
        assertFalse(sp.contains(ThemePrefs.KEY_SPACING_052))
    }

    @Test
    fun withoutTheOneNothingIsWritten() {
        sp.edit(commit = true) { putString(ThemePrefs.KEY_H_SPACING, "Standard") }
        migrated()
        assertEquals(setOf(ThemePrefs.KEY_H_SPACING), sp.all.keys)
    }
}
