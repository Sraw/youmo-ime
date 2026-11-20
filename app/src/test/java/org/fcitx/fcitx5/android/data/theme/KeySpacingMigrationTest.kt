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

/** The old single 按键间距 becomes both 横向间距 and 纵向间距 at the same level, also from a restored old backup. */
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
        // the same drawing: the old 宽松 was 5 across and 9 (6) up and down
        sp.edit(commit = true) { clear(); putString(ThemePrefs.KEY_SPACING_052, "Loose") }
        val choice = migrated().keyStyleChoice()
        assertEquals(KeyStyleChoice.Spacing(5, 5) to KeyStyleChoice.Spacing(9, 6), choice.hGap to choice.vGap)
    }

    // the theme manager's first read already sees the two moved
    @Test
    fun theThemeManagerMovesTheOneBeforeItReadsTheKeyStyle() {
        sp.edit(commit = true) { putString(ThemePrefs.KEY_SPACING_052, "Loose") }
        val choice = ThemeManager.startKeyStyle(ThemePrefs(sp))
        assertEquals(KeyStyleChoice.Spacing(5, 5) to KeyStyleChoice.Spacing(9, 6), choice.hGap to choice.vGap)
        assertFalse(sp.contains(ThemePrefs.KEY_SPACING_052))
    }

    // stored by name, so inserting 很紧凑 changes no stored meaning; listed first after following
    @Uat("ST-160")
    @Test
    fun theUpAndDownLevelsAreStoredByName() {
        assertEquals(
            listOf("FollowTheme", "VeryCompact", "Compact", "Standard", "Loose"),
            KeyVSpacingChoice.entries.map { it.name },
        )
        val spacing = mapOf(
            "VeryCompact" to KeyStyleChoice.Spacing(2, 2),
            "Compact" to KeyStyleChoice.Spacing(5, 3),
            "Standard" to KeyStyleChoice.Spacing(7, 4),
            "Loose" to KeyStyleChoice.Spacing(9, 6),
        )
        for ((name, gap) in spacing) {
            sp.edit(commit = true) { clear(); putString(ThemePrefs.KEY_V_SPACING, name) }
            val prefs = migrated()
            assertEquals(name, KeyVSpacingChoice.valueOf(name), prefs.keyVSpacingChoice.getValue())
            assertEquals(name, gap, prefs.keyStyleChoice().vGap)
        }
        ThemePrefs(sp).keyVSpacingChoice.setValue(KeyVSpacingChoice.VeryCompact)
        assertEquals("VeryCompact", sp.getString(ThemePrefs.KEY_V_SPACING, null))
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
        // a damaged backup or hand edit: never a crash at every start
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
