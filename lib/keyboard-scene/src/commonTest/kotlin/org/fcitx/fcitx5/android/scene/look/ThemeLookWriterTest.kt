/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.look

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.testData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeLookWriterTest {

    private fun again(file: ThemeLookFile) = ThemeLookReader.read(ThemeLookWriter.write(file)).ok()

    @Test
    fun whatIsWrittenReadsAsItWas() {
        // the theme maker's test theme: every kind of value once; its file, the app's, written so but indented
        val source = testData("look-3.0.json")
        val full = ThemeLookReader.read(source).ok()
        assertEquals(full, again(full))
        assertEquals(source.lines().joinToString("") { it.trim() }.replace("\": ", "\":"), ThemeLookWriter.write(full))
        // the defaults (no picture, no font: nulls), each other bar and the ripple
        val defaults = ThemeLookFile("defaults", false, LookDefaults.look(full.look.palette))
        assertEquals(defaults, again(defaults))
        for (bar in listOf(BarBase.Transparent, BarBase.Color)) {
            val other = defaults.copy(look = defaults.look.copy(bar = bar, keys = defaults.look.keys.copy(press = PressEffect.Ripple)))
            assertEquals(other, again(other))
        }
    }

    @Test
    fun aNameIsWrittenAsJsonHasIt() {
        val name = "a \"b\" \\ c\td\u0001 主题"
        val file = ThemeLookFile(name, true, LookDefaults.look(PALETTE))
        val text = ThemeLookWriter.write(file)
        assertTrue(text.startsWith("{\"version\":\"3.0\",\"name\":\"a \\\"b\\\" \\\\ c\\u0009d\\u0001 主题\""), text)
        assertEquals(name, again(file).name)
    }

    private companion object {
        val PALETTE = Palette(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
        )
    }
}
