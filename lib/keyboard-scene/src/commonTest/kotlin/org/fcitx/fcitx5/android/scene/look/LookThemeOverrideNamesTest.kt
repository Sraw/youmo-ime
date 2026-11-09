/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.look

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.look.LookThemeTest.Companion.palette
import org.fcitx.fcitx5.android.scene.look.LookThemeTest.Companion.themeFile
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A 3.0 single key's name theme 4 has no selector for (`*` and a key that is no role, a key named
 * longer than any key id) is kept in the 3.0 file but not written: written, it made the whole 4.0
 * theme unreadable, and nothing was drawn.
 */
class LookThemeOverrideNamesTest {

    @Test
    fun aStarKeyThatIsNoRoleAndAnOverlongNameAreLeftOutAndTheThemeReads() {
        val material = LookDefaults.look(palette(themeFile("material-light.xml")))
        val over = KeyOverride(KeyFill.Color, null, KeyLabel.Hide)
        val look = material.copy(
            keys = material.keys.copy(
                overrides = mapOf(
                    "*/Shift" to over,
                    "*/," to over,
                    "*/f1" to over,
                    "qwerty/" + "q".repeat(33) to over,
                    "*/shift" to over,
                ),
            ),
        )
        val text = LookTheme.xml(look, "names", false)
        ThemeReader.theme(text).ok()
        assertTrue("*/shift" in text, text)
        assertFalse("*/Shift" in text || "*/f1" in text || "q".repeat(33) in text, text)
    }
}
