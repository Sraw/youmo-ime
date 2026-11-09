/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.SystemColors
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.utils.alpha
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import org.fcitx.fcitx5.android.scene.theme.Theme as SceneTheme

/**
 * The built-in themes' 4.0 files (keyboard-scene src/commonMain/themes, 060-render-p3 T3) have each
 * version's colours as [ThemePreset] has them, Monet's as [Theme.Monet] makes them of the system's
 * roles, its fallbacks ThemeMonet's colours below Android 12. With keyboard-scene's
 * BuiltinThemeEquivalenceTest (a file draws as its colours with LookDefaults), each file draws as its
 * presets did: what lets ThemePreset go.
 */
class BuiltinThemesPresetTest {

    private val src = File(System.getProperty("scene.src") ?: "../lib/keyboard-scene/src")

    private fun read(path: String, system: SystemColors = SystemColors.NONE): SceneTheme =
        when (val t = ThemeReader.theme(File(src, path).readText(), system)) {
            is Parsed.Ok -> t.value
            is Parsed.Failed -> error("$path: ${t.problems}")
        }

    private fun colors(t: SceneTheme): List<Pair<String, Int>> = ThemeReader.REQUIRED_COLORS.map { it to t.colors.getValue(it).value }

    private fun colors(t: Theme): List<Pair<String, Int>> = LookTheme.colors(t.look.palette)

    @Test
    fun eachVersionHasItsPresetsColours() {
        val presets = mapOf(
            "material" to listOf(ThemePreset.MaterialLight, ThemePreset.MaterialDark),
            "pixel" to listOf(ThemePreset.PixelLight, ThemePreset.PixelDark),
            "nord" to listOf(ThemePreset.NordLight, ThemePreset.NordDark),
            "deep-blue" to listOf(ThemePreset.DeepBlue),
            "monokai" to listOf(ThemePreset.Monokai),
            "amoled-black" to listOf(ThemePreset.AMOLEDBlack),
        )
        for ((file, themes) in presets) {
            val read = read("commonMain/themes/$file.xml")
            assertEquals(file, themes.map { it.isDark }.toSet(), read.variants)
            for (t in themes) assertEquals("$file ${t.name}", colors(t), colors(read.variant(t.isDark)))
        }
        val transparent = read("commonTest/data/transparent.xml")
        for (t in listOf(ThemePreset.TransparentLight, ThemePreset.TransparentDark)) {
            assertEquals(t.name, colors(t), colors(transparent.variant(t.isDark)))
        }
    }

    // ThemeMonet's colours below Android 12 (static MD3, based on #769CDF)
    @Test
    fun monetsFallbacksAreThoseOfBeforeAndroid12() {
        val monet = read("commonMain/themes/monet.xml")
        val light = Theme.Monet(false, 0xffededf4.toInt(), 0xffe7e8ee.toInt(), 0xfff9f9ff.toInt(), 0xff191c20.toInt(), 0xff415f91.toInt(), 0xffffffff.toInt(), 0xffdae2f9.toInt(), 0xff44474e.toInt())
        val dark = Theme.Monet(true, 0xff1d2024.toInt(), 0xff282a2f.toInt(), 0xff37393e.toInt(), 0xffe2e2e9.toInt(), 0xffaac7ff.toInt(), 0xff0a305f.toInt(), 0xff3e4759.toInt(), 0xffc4c6d0.toInt())
        assertEquals(colors(light), colors(monet.variant(false)))
        assertEquals(colors(dark), colors(monet.variant(true)))
    }

    // the system's roles, each its own colour (translucent ones too: alpha() sets it as Int.alpha did), as ThemeMonet gave them on Android 14
    @Test
    fun monetTakesTheSystemsRolesAsThemeMonetDid() {
        val roles = listOf("surface-container", "surface-container-high", "surface-container-highest", "surface-bright", "on-surface", "primary", "on-primary", "secondary-container", "on-surface-variant")
        fun of(base: Int) = roles.withIndex().associate { (i, r) -> r to Argb(base + i * 0x11020304) }
        val system = SystemColors(of(0x10203040), of(0x21324354))
        val monet = read("commonMain/themes/monet.xml", system)
        fun monet(dark: Boolean): Theme.Monet {
            val r = system.of(dark).mapValues { it.value.value }
            // ThemeMonet: the light bar the highest container, the dark one the high
            return Theme.Monet(
                dark, r.getValue("surface-container"), r.getValue(if (dark) "surface-container-high" else "surface-container-highest"),
                r.getValue("surface-bright"), r.getValue("on-surface"), r.getValue("primary"), r.getValue("on-primary"),
                r.getValue("secondary-container"), r.getValue("on-surface-variant"),
            )
        }
        for (dark in listOf(false, true)) {
            assertEquals("dark $dark", colors(monet(dark)), colors(monet.variant(dark)))
        }
        // the press highlight's alpha as Int.alpha rounds it: 12% of 255 is 31, not 30
        assertEquals(0x1f, monet.variant(false).colors.getValue("keyPressHighlight").alpha)
        assertEquals(system.light.getValue("on-surface").value.alpha(0.12f), monet.variant(false).colors.getValue("keyPressHighlight").value)
    }
}
