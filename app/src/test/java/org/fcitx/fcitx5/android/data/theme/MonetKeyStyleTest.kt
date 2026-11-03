/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.input.theme.LegacyLook
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The key style a Monet theme in use kept from the settings before theme file 3.0 (not copied: its
 * colours follow the wallpaper): there after a restart, after a switch to another theme and back, and
 * in what it exports, which is a copy of its colours as they are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class MonetKeyStyleTest {

    // what AppPrefs, so ThemeManager.prefs, was made with (FcitxApplication)
    private val sp get() = PreferenceManager.getDefaultSharedPreferences(appContext)

    private val old = LegacyLook(keyBorder = false, keyRadius = 10, vMargin = 2)

    @Before
    fun settled() {
        phoneStorage()
        ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
        // the old settings, a Monet theme in use: what an upgrade finds
        sp.edit(commit = true) {
            putBoolean("key_border", false); putInt("key_radius", 10); putInt("key_vertical_margin", 2)
        }
        assertEquals(old, ThemeManager.prefs.legacyLook())
        val monet = ThemeMonet.getLight()
        val slot = ThemeLookMigration.Slot({ monet }, { error("a Monet theme is not copied") })
        assertTrue(ThemeManager.prefs.settleLook(listOf(slot), ThemeManager::saveTheme))
    }

    @After
    fun none() {
        sp.edit(commit = true) { remove(ThemeLookMigration.MONET_KEY) }
        ThemeLookMigration.monetLook = null
        ThemeManager.onSystemPlatteChange(appContext.resources.configuration)
        ThemeManager.setNormalModeTheme(ThemeManager.DefaultTheme)
        ThemeManager.prefs.followSystemDayNightTheme.setValue(ThemeManager.prefs.followSystemDayNightTheme.defaultValue)
    }

    private fun Theme.styled() {
        assertFalse(name, look.keys.blocks)
        assertEquals(name, 10, look.keys.radiusDp)
        assertEquals(name, 2, look.keys.gap.v)
    }

    private fun monet() = ThemeManager.getAllThemes().first { it is Theme.Monet && !it.isDark } as Theme.Monet

    @Test
    @Uat("ST-35")
    fun itLastsARestart() {
        // a new process: nothing in memory, what ThemeManager reads before the first Monet theme is made
        ThemeLookMigration.monetLook = null
        ThemeLookMigration.monetLook = ThemeManager.prefs.monetLook()
        assertEquals(old, ThemeLookMigration.monetLook)
        // the next start's settling finds nothing to do and leaves it
        val slot = ThemeLookMigration.Slot({ monet() }, { error("a Monet theme is not copied") })
        assertFalse(ThemeManager.prefs.settleLook(listOf(slot), ThemeManager::saveTheme))
        assertEquals(old, ThemeManager.prefs.monetLook())
        ThemeManager.onSystemPlatteChange(appContext.resources.configuration)
        monet().styled()
    }

    @Test
    @Uat("ST-35")
    fun itLastsASwitchToAnotherThemeAndBack() {
        ThemeManager.onSystemPlatteChange(appContext.resources.configuration)
        ThemeManager.setNormalModeTheme(monet())
        ThemeManager.activeTheme.styled()
        ThemeManager.setNormalModeTheme(ThemePreset.PixelLight)
        assertTrue(ThemeManager.activeTheme.look.keys.blocks)
        assertEquals(old, ThemeManager.prefs.monetLook())
        // back by the list, and as the setting reads it at a start
        ThemeManager.setNormalModeTheme(monet())
        ThemeManager.activeTheme.styled()
        ThemeManager.prefs.normalModeTheme.getValue().styled()
    }

    @Test
    @Uat("ST-35")
    fun itsExportHasItWithItsColoursAsTheyAre() {
        ThemeManager.onSystemPlatteChange(appContext.resources.configuration)
        val monet = monet()
        val bytes = ByteArrayOutputStream()
        ThemeFilesManager.exportTheme(monet.toCustom(), bytes).getOrThrow()
        // gone from this app since: the file keeps it
        ThemeLookMigration.monetLook = null
        val (_, imported, _) = ThemeFilesManager.importTheme(ByteArrayInputStream(bytes.toByteArray())).getOrThrow()
        imported.styled()
        assertEquals(monet.look.palette, imported.look.palette)
        ThemeManager.deleteTheme(imported.name)
    }
}
