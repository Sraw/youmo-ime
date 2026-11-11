/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeFileNameTest {

    @Test
    fun onlyTheThemeExtensionIsATheme() {
        assertTrue(ThemeFileName.isThemeFile("a.theme"))
        assertTrue(ThemeFileName.isThemeFile("A.THEME"))
        assertTrue(ThemeFileName.isThemeFile("my theme (1).theme"))
        // an older export, one renamed by a provider, none, nothing
        for (name in listOf("a.zip", "a.theme.zip", "a", "", ".theme", null)) assertFalse(name, ThemeFileName.isThemeFile(name))
    }

    @Test
    fun skinsByTheirExtensions() {
        for (name in listOf("a.bds", "B.BDI", "x y.ssf", "c.flex", "d.trime.yaml", "e.YML", "f.zip", "a.ssf.zip")) {
            assertTrue(name, ThemeFileName.isSkinFile(name))
        }
        for (name in listOf("a.theme", ".ssf", "ssf", "a.json", "", null)) assertFalse(name.toString(), ThemeFileName.isSkinFile(name))
        assertTrue(ThemeFileName.isYaml("星天.trime.yaml"))
        assertTrue(ThemeFileName.isYaml("a.YML"))
        assertFalse(ThemeFileName.isYaml("a.yaml.zip"))
        assertFalse(ThemeFileName.isYaml(".yaml"))
        assertFalse(ThemeFileName.isYaml(null))
        assertTrue(ThemeFileName.isZip("a.ZIP"))
        assertFalse(ThemeFileName.isZip("a.flex"))
        assertFalse(ThemeFileName.isZip(null))
        assertEquals("海风", ThemeFileName.base("海风.bds"))
        assertEquals("a.b", ThemeFileName.base("dir/a.b.ssf"))
        assertEquals(".ssf", ThemeFileName.base(".ssf"))
    }

    @Test
    fun anExportIsOfferedAsATheme() {
        assertEquals("夜.theme", ThemeFileName.of("夜"))
    }
}
