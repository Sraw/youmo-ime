/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.look

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.Forms
import org.fcitx.fcitx5.android.scene.SceneText
import org.fcitx.fcitx5.android.scene.testData
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The built-in themes (src/commonMain/themes, 060-render-p3 T3) draw as the app draws them: each
 * version's colours as a 3.0 palette with [LookDefaults] ([LookTheme], what the app's `Theme.Builtin`
 * is drawn by), every board in every form ([Forms]) and its popups, the scenes' text alike to the
 * character. So the web, which reads the files, shows what the app shows.
 *
 * Until `ThemePreset` went, the app's BuiltinThemesPresetTest (commit bb317aba3) held each version's
 * colours to its preset's (Monet's to `Theme.Monet` of the same roles, its fallbacks to ThemeMonet's
 * colours below Android 12); with this, the files drew as the presets did. Now this holds the files
 * to the app's drawing, and T0's record (ThemeBaselineCompareTest) them to 0.5's pixels. Transparent's
 * two, test data now, are held here too: T0's record draws them.
 */
class BuiltinThemeEquivalenceTest {

    @Test
    fun everyBuiltInThemeDrawsAsItsColoursWithTheDefaultLook() {
        for (file in FILES) {
            val read = ThemeReader.theme(testData(file)).ok()
            for (dark in read.variants) {
                val theme = read.variant(dark)
                val look = LookDefaults.look(LookThemeTest.palette(theme))
                val converted = ThemeReader.theme(LookTheme.xml(look, theme.name, dark), maxStyles = LookTheme.MAX_STYLES).ok()
                same(theme, converted, "$file ${if (dark) "dark" else "light"}")
            }
        }
    }

    @Test
    fun theFilesAreTheirNamesAndVersions() {
        val variants = FILES.associate { f -> f.removeSuffix(".xml") to ThemeReader.theme(testData(f)).ok().let { it.name to it.variants } }
        val both = setOf(false, true)
        assertEquals(
            mapOf(
                "material" to ("material" to both), "pixel" to ("pixel" to both), "nord" to ("nord" to both),
                "deep-blue" to ("deep-blue" to setOf(true)), "monokai" to ("monokai" to setOf(true)),
                "amoled-black" to ("amoled-black" to setOf(true)), "monet" to ("monet" to both),
                "transparent" to ("transparent" to both),
            ),
            variants,
        )
    }

    // every board in every form, then its popups in each form, the scene of [expected] and of [actual] alike
    private fun same(expected: Theme, actual: Theme, what: String) {
        for ((form, env) in Forms.all) {
            val e = env.copy(dark = expected.dark)
            for (board in Forms.boards) {
                val boardEnv = Forms.env(board, e)
                assertEquals(
                    SceneText.of(Forms.scene(board, boardEnv, expected)),
                    SceneText.of(Forms.scene(board, boardEnv, actual)),
                    "$what $board $form",
                )
            }
            val popups = Forms.popups(e, actual).toMap()
            for ((name, make) in Forms.popups(e, expected)) {
                assertEquals(SceneText.of(make()), SceneText.of(popups.getValue(name)()), "$what popup $name $form")
            }
        }
    }

    private companion object {
        // the app's, as it lists them, and Transparent's (test data)
        val FILES = listOf("monet.xml", "material.xml", "pixel.xml", "nord.xml", "deep-blue.xml", "monokai.xml", "amoled-black.xml", "transparent.xml")
    }
}
