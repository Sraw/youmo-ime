/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.Context
import androidx.core.content.edit
import org.fcitx.fcitx5.android.input.theme.LegacyLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ThemeLookMigrationTest {

    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("t", Context.MODE_PRIVATE)
        .apply { edit(commit = true) { clear() } }

    @Test
    fun noSettingsNoLegacy() {
        assertNull(ThemeLookMigration.legacy(prefs))
        prefs.edit(commit = true) { putString("other", "x") }
        assertNull(ThemeLookMigration.legacy(prefs))
    }

    @Test
    fun theSettingsAreReadEachAsItWas() {
        // the levels alone set: the values were written beside them, so the defaults
        prefs.edit(commit = true) { putString("key_spacing_level", "1") }
        assertTrue(ThemeLookMigration.legacy(prefs)!!.isDefault)
        prefs.edit(commit = true) {
            putBoolean("key_border", false)
            putBoolean("key_ripple_effect", true)
            putInt("key_radius", 0)
            putInt("key_vertical_margin_landscape", 2)
        }
        assertEquals(
            LegacyLook(keyBorder = false, keyRippleEffect = true, keyRadius = 0, vMarginLandscape = 2),
            ThemeLookMigration.legacy(prefs),
        )
    }

    private class Slots(vararg themes: Theme) {
        val chosen = themes.toMutableList()
        val slots = chosen.indices.map { i -> ThemeLookMigration.Slot({ chosen[i] }, { chosen[i] = it }) }
        val saved = mutableListOf<Theme.Custom>()
    }

    @Test
    fun aBuiltInThemeInUseIsCopiedWithTheSettingsOnceAndTheSettingsGo() {
        prefs.edit(commit = true) { putBoolean("key_border", false); putInt("key_radius", 10) }
        val mine = ThemePreset.PixelDark.deriveCustomNoBackground("mine")
        // light and dark the same preset: one copy for both
        val s = Slots(ThemePreset.PixelLight, ThemePreset.PixelLight, mine)
        assertTrue(ThemeLookMigration.settle(prefs, s.slots, s.saved::add))
        val copy = s.saved.single()
        assertSame(copy, s.chosen[0])
        assertSame(copy, s.chosen[1])
        assertSame(mine, s.chosen[2])
        assertFalse(copy.look.keys.blocks)
        assertEquals(10, copy.look.keys.radiusDp)
        assertEquals(ThemePreset.PixelLight.look.palette, copy.look.palette)
        assertEquals(ThemePreset.PixelLight.isDark, copy.isDark)
        assertTrue(ThemeLookMigration.KEYS.none(prefs::contains))
        // again: nothing to do, nobody told
        assertFalse(ThemeLookMigration.settle(prefs, s.slots, s.saved::add))
        assertEquals(1, s.saved.size)
    }

    @Test
    fun defaultSettingsCopyNothingButAreRemoved() {
        prefs.edit(commit = true) { putBoolean("key_border", true); putInt("text_editing_button_radius", 4) }
        val s = Slots(ThemePreset.PixelLight)
        assertFalse(ThemeLookMigration.settle(prefs, s.slots, s.saved::add))
        assertEquals(emptyList<Theme.Custom>(), s.saved)
        assertSame(ThemePreset.PixelLight, s.chosen[0])
        assertTrue(ThemeLookMigration.KEYS.none(prefs::contains))
    }

    @Test
    fun aCopyNotSavedKeepsTheSettingsForTheNextStart() {
        prefs.edit(commit = true) { putInt("key_radius", 0) }
        val s = Slots(ThemePreset.PixelLight, ThemePreset.PixelDark)
        // the disk full for the second copy: the first in its slot, the second slot as it was
        var saves = 0
        assertFalse(ThemeLookMigration.settle(prefs, s.slots) { saves++ == 0 })
        assertTrue(s.chosen[0] is Theme.Custom)
        assertSame(ThemePreset.PixelDark, s.chosen[1])
        assertTrue(prefs.contains("key_radius"))
        // the next start: the copy is the user's now, the other copied
        assertTrue(ThemeLookMigration.settle(prefs, s.slots, s.saved::add))
        assertEquals(0, (s.chosen[1] as Theme.Custom).look.keys.radiusDp)
        assertEquals(1, s.saved.size)
        assertTrue(ThemeLookMigration.KEYS.none(prefs::contains))
    }

    @Test
    fun aMonetThemeInUseIsNotCopiedButKeepsTheSettingsForItsKeys() {
        ThemeLookMigration.monetLook = null
        prefs.edit(commit = true) { putBoolean("key_border", false); putBoolean("key_ripple_effect", true) }
        val monet = ThemeMonet.getLight()
        val s = Slots(monet)
        assertTrue(ThemeLookMigration.settle(prefs, s.slots, s.saved::add))
        assertSame(monet, s.chosen[0])
        assertEquals(emptyList<Theme.Custom>(), s.saved)
        val kept = LegacyLook(keyBorder = false, keyRippleEffect = true)
        assertEquals(kept, ThemeLookMigration.monet(prefs))
        assertEquals(kept, ThemeLookMigration.monetLook)
        // a Monet theme made now draws its keys so, its colours the wallpaper's still
        val drawn = ThemeMonet.getLight().look
        assertFalse(drawn.keys.blocks)
        assertEquals(monet.look.palette, drawn.palette)
        assertTrue(ThemeLookMigration.KEYS.none(prefs::contains))
        ThemeLookMigration.monetLook = null
    }

    @Test
    fun whatMonetKeepsReadsBackAndANoteGarbledIsNone() {
        val l = LegacyLook(false, true, true, 7, 1, 2, 3, 4)
        assertEquals(l, ThemeLookMigration.decode(ThemeLookMigration.encode(l)))
        assertNull(ThemeLookMigration.decode("true,1"))
        assertNull(ThemeLookMigration.decode("x,false,false,1,1,1,1,1"))
    }
}
