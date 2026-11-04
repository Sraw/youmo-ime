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
    fun anExportIsOfferedAsATheme() {
        assertEquals("夜.theme", ThemeFileName.of("夜"))
    }
}
