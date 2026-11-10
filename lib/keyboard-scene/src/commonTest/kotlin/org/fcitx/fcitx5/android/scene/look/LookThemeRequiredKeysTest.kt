/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.look

import org.fcitx.fcitx5.android.scene.Fixtures
import org.fcitx.fcitx5.android.scene.look.LookThemeTest.Companion.palette
import org.fcitx.fcitx5.android.scene.look.LookThemeTest.Companion.themeFile
import org.fcitx.fcitx5.android.scene.theme.BlankKeys
import kotlin.test.Test
import kotlin.test.assertEquals

// a 3.0 theme the app took converts to one it imports: a required key hidden behind its own image keeps its words
// wherever its face is no image, pressed too
class LookThemeRequiredKeysTest {
    private val material by lazy { LookDefaults.look(palette(themeFile("material-light.xml"))) }

    private fun slice(file: String) = NineSlice("/data/themes/t/$file", Insets(12, 12, 12, 12), 3)

    @Test
    fun aRequiredKeyHiddenBehindAnImageIsNotBlankPressed() {
        // Return's image hides its words; pressed a plain colour (its own, or its kind's where it names none): the
        // pressed face is no image, so the words come back there, else the converted theme is refused
        for (pressed in listOf(KeyPressed.Color(0xff112233.toInt()), null)) {
            val look = material.copy(keys = material.keys.copy(overrides = mapOf("*/enter" to KeyOverride(KeyFill.Image(slice("enter.png")), pressed, KeyLabel.Hide))))
            val theme = LookTheme.theme(look, "t", false)
            assertEquals(emptyList<BlankKeys.Blank>(), BlankKeys.find(theme, listOf(BlankKeys.Board(Fixtures.qwerty)), limit = 10), "$pressed")
        }
    }
}
