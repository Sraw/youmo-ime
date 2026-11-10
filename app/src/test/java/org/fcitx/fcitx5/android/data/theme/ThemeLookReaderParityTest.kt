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
 * contract: one theme file format read by two readers, and the theme maker's test data (ThemeMakerTestdata).
 *
 * The engine's reader of theme file 3.0 (keyboard-scene's ThemeLookReader, for the web) reads every
 * file as the app's kotlinx one (CustomThemeSerializer, ThemeLookJson) does, field by field: the five
 * sample themes light and dark, every built-in theme exported, the theme maker's files. Text no maker
 * writes the two read apart, as [textNoMakerWritesReadAsHeld] holds.
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
        // every one the app has, a new one too
        val presets = ThemeManager.BuiltinThemes
        assertTrue(presets.size >= 11)
        for (p in presets) same(exported(Theme.Custom(p.name, p.isDark, p.look)), p.name)
        // Transparent's two, test data since Monet's took their places (R20 F6638): their translucent colours
        // read alike too, which no built-in one has
        for (p in listOf(TestThemes.TransparentLight, TestThemes.TransparentDark)) same(exported(Theme.Custom(p.name, p.isDark, p.look)), p.name)
    }

    // what is no file of the form: whether each reads it (true) or refuses it, the app's then the engine's
    private fun probe(): Map<String, Pair<Boolean, Boolean>> {
        val source = ThemeMakerTestdata.file("theme.json").readText()
        fun edited(from: String, to: String) = source.replaceFirst(from, to).also { check(it != source) { from } }
        val cases = mapOf(
            "duplicate key" to edited("\"isDark\": true", "\"isDark\": false, \"isDark\": true"),
            "quoted number" to edited("\"radiusDp\": 8", "\"radiusDp\": \"8\""),
            "quoted boolean" to edited("\"isDark\": true", "\"isDark\": \"true\""),
            "byte order mark" to "\uFEFF" + source,
            "control character" to edited("\"full\"", "\"fu\u0001ll\""),
            "raw tab" to edited("\"full\"", "\"fu\tll\""),
            "decimal int" to edited("\"radiusDp\": 8", "\"radiusDp\": 8.0"),
            "exponent int" to edited("\"radiusDp\": 8", "\"radiusDp\": 8e0"),
        )
        return cases.mapValues { (_, json) ->
            val app = runCatching { Json.decodeFromString(CustomThemeSerializer, json) }.isSuccess
            val engine = ThemeLookReader.read(json) is Parsed.Ok
            app to engine
        }
    }

    /**
     * Text no theme maker nor app writes (kotlinx's encoder writes none of it), each read or refused by
     * the app's reader and the engine's: the two differ, and on purpose. The engine's is strict JSON (RFC
     * 8259), where kotlinx's default Json takes a key twice (the last), a number or boolean in quotes, a
     * control character unescaped, an Int as `8e0`; and it skips a byte order mark an editor saves, which
     * kotlinx refuses. A file the web takes and the app does not, or the other way, is one written by hand.
     */
    @Test
    fun textNoMakerWritesReadAsHeld() {
        val appOnly = true to false
        assertEquals(
            mapOf(
                "duplicate key" to appOnly,
                "quoted number" to appOnly,
                "quoted boolean" to appOnly,
                "byte order mark" to (false to true),
                "control character" to appOnly,
                "raw tab" to appOnly,
                "decimal int" to (false to false),
                "exponent int" to appOnly,
            ),
            probe(),
        )
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
