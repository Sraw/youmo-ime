/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.look

import org.fcitx.fcitx5.android.scene.FakeMeasurer
import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.Forms
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.SceneText
import org.fcitx.fcitx5.android.scene.Uat
import org.fcitx.fcitx5.android.scene.engine.MenuCell
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.testData
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    // the edit menu's cell in focus told from the others at a glance (0.6.1: all were Return's colour, the one in
    // focus 12–20% darker or lighter, 1.05–1.44 to 1): its face against theirs, each over white (light) or black (dark)
    @Test
    @Uat("KB-105")
    fun theMenusCellInFocusStandsOutInEveryBuiltInTheme() {
        val cells = listOf("select-all", "cut", "copy", "paste", "undo").map { MenuCell(it, it) }
        for (file in FILES) {
            val read = ThemeReader.theme(testData(file)).ok()
            for (dark in read.variants) {
                val theme = read.variant(dark)
                val env = Forms.all.values.first().copy(dark = dark)
                val menu = SceneBuilder(theme, env, FakeMeasurer).menu(cells, cells.indices.toList(), selected = 2, disabled = emptySet()).ok()
                val ground = if (dark) Argb(BLACK) else Argb(WHITE)
                val faces = menu.keys.associate { k -> k.id to face(k.faces.getValue(k.states), ground) }
                val focus = faces.getValue("menu/2")
                for ((id, other) in faces - "menu/2") {
                    val ratio = contrast(focus, other)
                    assertTrue(ratio >= MIN_CONTRAST, "$file ${if (dark) "dark" else "light"}: $id $other, in focus $focus, $ratio to 1")
                }
            }
        }
    }

    // a cell's face: its fills, the first the face, over [ground] in turn (the cell in focus's overlay among them). Only
    // plain fills are read (R26 F8305): a gradient, a picture or a nine-slice face, or a depth's band (a fill [Op.Fill.less]
    // the face), would be taken for what it is not, so a theme drawing one fails here until this reads it
    private fun face(ops: List<Op>, ground: Argb): Argb {
        val unread = ops.filter { it is Op.Gradient || it is Op.NineSlice || it is Op.Picture || it is Op.Fill && it.less != null }
        assertTrue(unread.isEmpty(), "a face not of plain fills: $unread")
        val fills = ops.filterIsInstance<Op.Fill>()
        assertTrue(fills.isNotEmpty(), "a face of no fill: $ops")
        return fills.fold(ground) { under, fill -> over(fill.color, under) }
    }

    private fun over(top: Argb, under: Argb): Argb {
        val a = top.alpha / FULL
        fun mix(t: Int, u: Int) = (t * a + u * (1 - a)).toInt()
        return Argb(OPAQUE or (mix(top.red, under.red) shl RED) or (mix(top.green, under.green) shl GREEN) or mix(top.blue, under.blue))
    }

    // WCAG 2's contrast ratio
    private fun contrast(a: Argb, b: Argb): Double {
        fun channel(c: Int): Double = (c / FULL).let { if (it <= LINEAR) it / LINEAR_SLOPE else ((it + GAMMA_OFFSET) / (1 + GAMMA_OFFSET)).pow(GAMMA) }
        fun luminance(c: Argb) = R_WEIGHT * channel(c.red) + G_WEIGHT * channel(c.green) + B_WEIGHT * channel(c.blue)
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + FLARE) / (lo + FLARE)
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
        // the least a cell in focus differs from the others by (WCAG's 3 to 1 is for what must be read; a fill's edge, less)
        const val MIN_CONTRAST = 1.6
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val OPAQUE = 0xFF shl 24
        const val RED = 16
        const val GREEN = 8
        const val FULL = 255.0
        const val LINEAR = 0.03928
        const val LINEAR_SLOPE = 12.92
        const val GAMMA_OFFSET = 0.055
        const val GAMMA = 2.4
        const val R_WEIGHT = 0.2126
        const val G_WEIGHT = 0.7152
        const val B_WEIGHT = 0.0722
        const val FLARE = 0.05
        val FILES = listOf("monet.xml", "material.xml", "pixel.xml", "nord.xml", "deep-blue.xml", "monokai.xml", "amoled-black.xml", "transparent.xml")
    }
}
