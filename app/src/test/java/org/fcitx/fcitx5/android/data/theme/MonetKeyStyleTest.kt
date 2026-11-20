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
import org.fcitx.fcitx5.android.scene.look.LegacyLook
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** A Monet theme's pre-3.0 key style survives restarts, a switch away and back, and export (its colours as they are). */
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
        // what an upgrade finds
        sp.edit(commit = true) {
            putBoolean("key_border", false); putInt("key_radius", 10); putInt("key_vertical_margin", 2)
        }
        assertEquals(old, ThemeManager.prefs.legacyLook())
        val monet = ThemeManager.builtin("monet#light")
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

    private fun monet() = ThemeManager.builtin("monet#light")

    @Test
    @Uat("ST-35")
    fun itLastsARestart() {
        // a new process: nothing in memory
        ThemeLookMigration.monetLook = null
        ThemeLookMigration.monetLook = ThemeManager.prefs.monetLook()
        assertEquals(old, ThemeLookMigration.monetLook)
        // the next start's settling leaves it
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
        ThemeManager.setNormalModeTheme(TestThemes.PixelLight)
        assertTrue(ThemeManager.activeTheme.look.keys.blocks)
        assertEquals(old, ThemeManager.prefs.monetLook())
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
        val fixed = monet.toCustom()
        fixed.styled()
        ThemeFilesManager.exportTheme(fixed, bytes).getOrThrow()
        // gone from this app since: the file keeps it as a 4.0 theme
        ThemeLookMigration.monetLook = null
        val opened = ThemeFilesManager.openTheme(ByteArrayInputStream(bytes.toByteArray())).getOrThrow() as ThemeFilesManager.Opened.Pack
        val imported = opened.commit().getOrThrow().single()
        assertEquals(ThemePackFiles.of(fixed.look, fixed.name, fixed.isDark).first, File(imported.dir, ThemePackFiles.THEME_XML).readText())
        assertNotEquals(ThemePackFiles.of(LookDefaults.look(fixed.look.palette), fixed.name, fixed.isDark).first, File(imported.dir, ThemePackFiles.THEME_XML).readText())
        assertEquals(monet.look.palette, imported.look.palette)
        ThemeManager.refreshThemes()
        ThemeManager.deleteTheme(imported.name)
        assertEquals(emptyList<Theme.Pack>(), ThemeManager.Packs)
    }

    // not a managed preference, so not in device-protected storage: before unlock it was drawn without it
    @Test
    fun theKeyStyleIsCopiedToTheStoreReadBeforeTheFirstUnlock() {
        val locked = PreferenceManager.getDefaultSharedPreferences(appContext.createDeviceProtectedStorageContext())
        ThemeManager.syncToDeviceEncryptedStorage()
        assertEquals(old, ThemeLookMigration.monet(locked))
        sp.edit(commit = true) { remove(ThemeLookMigration.MONET_KEY) }
        ThemeManager.syncToDeviceEncryptedStorage()
        assertEquals(null, ThemeLookMigration.monet(locked))
    }
}
