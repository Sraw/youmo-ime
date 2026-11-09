/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.scene.theme.SystemColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The built-in themes as the app reads them from its files (060-render-p3 T3): the build's copies are the
 * files, each version referred to by name (`pixel#dark`, `deep-blue`), and what holds for every one.
 */
@RunWith(RobolectricTestRunner::class)
class BuiltinThemeFilesTest {

    private val themes = TestThemes.builtins.map { it.second }

    @Test
    fun theAppsResourcesAreTheFiles() {
        val loaded = BuiltinThemeFiles.load(RuntimeEnvironment.getApplication().resources, SystemColors.NONE)
        assertEquals(themes, loaded)
        assertEquals(BuiltinThemeFiles.FILES.map { it.first }, loaded.map { it.file }.distinct())
    }

    @Test
    fun eachVersionIsNamedByItsFileAndVersion() {
        assertEquals(
            listOf(
                "monet#light", "monet#dark", "material#light", "material#dark", "pixel#light", "pixel#dark",
                "nord#light", "nord#dark", "deep-blue", "monokai", "amoled-black",
            ),
            themes.map { it.name },
        )
        for (t in themes) {
            if ('#' in t.name) assertEquals(t.name, t.name.endsWith("#dark"), t.isDark)
            assertEquals(t.name, t.file == BuiltinThemeFiles.MONET, t.followsSystem)
        }
    }

    @Test
    fun aReferenceFindsItsVersion() {
        assertEquals(TestThemes.PixelDark, BuiltinThemeFiles.find(themes, "pixel#dark"))
        assertEquals(TestThemes.PixelLight, BuiltinThemeFiles.find(themes, "pixel#light"))
        assertEquals(TestThemes.DeepBlue, BuiltinThemeFiles.find(themes, "deep-blue"))
        // a single version by its version too; not the one it lacks, nor a pair's by its name alone
        assertEquals(TestThemes.DeepBlue, BuiltinThemeFiles.find(themes, "deep-blue#dark"))
        assertNull(BuiltinThemeFiles.find(themes, "deep-blue#light"))
        assertNull(BuiltinThemeFiles.find(themes, "pixel"))
        assertNull(BuiltinThemeFiles.find(themes, "pixel#dim"))
        assertNull(BuiltinThemeFiles.find(themes, "PixelDark"))
    }

    /** Text must not be fully transparent, whatever the background does. */
    @Test
    fun foregroundColoursAreNotFullyTransparent() {
        for (t in themes + TestThemes.TransparentLight + TestThemes.TransparentDark) {
            val foregrounds = listOf(
                t.keyTextColor, t.candidateTextColor, t.candidateLabelColor, t.candidateCommentColor,
                t.altKeyTextColor, t.accentKeyTextColor, t.popupTextColor, t.genericActiveForegroundColor,
            )
            for (c in foregrounds) assertTrue("${t.name} has a fully transparent text colour", c ushr 24 > 0)
        }
    }

    @Test
    fun monetExportedIsNamedAs05NamedIt() {
        val custom = TestThemes.MonetDark.toCustom()
        assertEquals("MonetDark#ffaac7ff", custom.name)
        assertTrue(custom.isDark)
        assertEquals(TestThemes.MonetDark.look, custom.look)
    }
}
