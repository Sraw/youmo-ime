/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import org.fcitx.fcitx5.android.scene.Icons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

// contract: the icon names a theme file may use, as the theme reader lets them by
/** The icons a theme may name (keyboard-scene's [Icons], what its reader lets by) are the ones the app draws. */
class SceneIconsTest {
    @Test
    fun theThemesIconsAreTheAppsAlike() {
        assertEquals(Icons.ALL, SceneIcons.names)
        assertEquals(Icons.RETURN.toSet(), Icons.RETURN.filter { SceneIcons.returnName(SceneIcons.of(it)!!) == it }.toSet())
        assertNull(SceneIcons.of("rocket"))
    }

    @Test
    fun theSignsInkIsItsDrawablesPaths() {
        // the engine keeps the sign's two lines apart from the letter, not its square: its paths' bounds, h/v/H only
        val paths = Regex("pathData=\"([^\"]+)\"").findAll(File("src/main/res/drawable/ic_baseline_short_text_24.xml").readText()).map { it.groupValues[1] }
        val xs = mutableListOf<Double>()
        val ys = mutableListOf<Double>()
        for (d in paths) {
            var x = 0.0
            var y = 0.0
            val commands = Regex("([MhvHz])([-\\d.,]*)").findAll(d).toList()
            // a command it does not know (a curve, a relative move) would leave its ink out: the matches are the whole of d
            assertEquals(d, commands.joinToString("") { it.value })
            commands.forEach { m ->
                val n = m.groupValues[2].split(',').filter { it.isNotEmpty() }.map { it.toDouble() }
                when (m.groupValues[1]) {
                    "M" -> { x = n[0]; y = n[1] }
                    "h" -> x += n[0]
                    "v" -> y += n[0]
                    "H" -> x = n[0]
                }
                xs += x
                ys += y
            }
        }
        assertEquals(Icons.SHORT_TEXT_INK, Icons.Ink(xs.min(), ys.min(), xs.max(), ys.max()))
    }
}
