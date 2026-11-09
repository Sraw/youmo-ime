/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.keyboard.ThemeLookSamples
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.look.ThemeLookReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.zip.ZipInputStream

/**
 * The engine's reader of theme file 3.0 (keyboard-scene's ThemeLookReader, for the web) reads every
 * file as the app's kotlinx one (CustomThemeSerializer, ThemeLookJson) does, field by field: the five
 * sample themes light and dark, every built-in theme exported, the theme maker's files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemeLookReaderParityTest {

    @get:Rule
    val dir = TemporaryFolder()

    // [json] read by both: the same name, darkness and look, each part of the look compared on its own
    private fun same(json: String, what: String) {
        val app = Json.decodeFromString(CustomThemeSerializer, json)
        val engine = when (val r = ThemeLookReader.read(json)) {
            is Parsed.Ok -> r.value
            is Parsed.Failed -> throw AssertionError("$what: ${r.problems}")
        }
        assertEquals("$what: name", app.name, engine.name)
        assertEquals("$what: isDark", app.isDark, engine.dark)
        val a = app.look
        val e = engine.look
        assertEquals("$what: palette", a.palette, e.palette)
        assertEquals("$what: picture", a.picture, e.picture)
        assertEquals("$what: base", a.base, e.base)
        assertEquals("$what: bar", a.bar, e.bar)
        assertEquals("$what: keys", a.keys, e.keys)
        assertEquals("$what: text", a.text, e.text)
        assertEquals(what, a, e)
    }

    private fun exported(theme: Theme.Custom) = Json.encodeToString(CustomThemeSerializer, theme)

    @Test
    fun theSampleThemes() {
        val all = ThemeLookSamples(dir.root).all()
        assertEquals(10, all.size)
        for (t in all) same(exported(t), t.name)
    }

    @Test
    fun everyBuiltInThemeExported() {
        val presets = listOf(
            ThemePreset.MaterialLight, ThemePreset.MaterialDark, ThemePreset.PixelLight, ThemePreset.PixelDark,
            ThemePreset.DeepBlue, ThemePreset.AMOLEDBlack, ThemePreset.NordLight, ThemePreset.NordDark,
            ThemePreset.Monokai, ThemePreset.TransparentDark, ThemePreset.TransparentLight,
        )
        for (p in presets) same(exported(Theme.Custom(p.name, p.isDark, p.look)), p.name)
    }

    // web/theme-maker/test/testdata: its json, and each .theme's; 2.1's as the app migrates it (the engine reads 3.0 only)
    @Test
    fun theThemeMakersFiles() {
        same(ThemeMakerTestdata.file("theme.json").readText(), "theme.json")
        for (name in listOf("from-app.theme", "from-web.theme", "from-app-2.1.theme")) {
            val json = ZipInputStream(ThemeMakerTestdata.file(name).inputStream()).use { z ->
                generateSequence { z.nextEntry }.filter { it.name.endsWith(".json") }.map { z.readBytes().decodeToString() }.single()
            }
            if (name.endsWith("-2.1.theme")) {
                assertTrue(name, ThemeLookReader.read(json) is Parsed.Failed)
                same(exported(Json.decodeFromString(CustomThemeSerializer, json)), name)
            } else {
                same(json, name)
            }
        }
    }
}
