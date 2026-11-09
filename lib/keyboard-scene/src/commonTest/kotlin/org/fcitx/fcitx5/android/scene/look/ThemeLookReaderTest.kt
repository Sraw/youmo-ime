/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.look

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.json.Json
import org.fcitx.fcitx5.android.scene.json.JsonValue
import org.fcitx.fcitx5.android.scene.testData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeLookReaderTest {

    // the theme maker's test theme (web/theme-maker/test/testdata/theme.json): every kind of value once
    private val source = testData("look-3.0.json")

    @Test
    fun aThemeFileReads() {
        val file = ThemeLookReader.read(source).ok()
        assertEquals("full", file.name)
        assertTrue(file.dark)
        val look = file.look
        assertEquals(-13816531, look.palette.backgroundColor)
        assertEquals(Crop("full-landscape.png", Box(0, 900, 1440, 1500), 90), look.picture?.landscape)
        assertEquals(BoardBase.Picture, look.base)
        assertEquals(BarBase.Picture(12, 60), look.bar)
        assertEquals(Gap(3, 7, 3, 4), look.keys.gap)
        assertEquals(KindStyle(KeyFill.Picture(10, 80), KeyPressed.Darken), look.keys.letter)
        assertEquals(KeyPressed.Image(NineSlice("full-enter-down.png", Insets(8, 6, 8, 6), 2)), look.keys.enter.pressed)
        assertEquals(KeyOverride(null, KeyPressed.Color(-1499549), KeyLabel.Show), look.keys.overrides["qwerty/q"])
        assertEquals(KeyLabel.Hide, look.keys.overrides["*/enter"]?.label)
        assertEquals(Text("full-font.ttf", "full-candidates.ttf", 700, 400, 110), look.text)
    }

    @Test
    fun badJsonIsAProblemWithItsLine() {
        val bad = listOf(
            "", "{", "{\"a\":1,}", "[1 2]", "{\"a\" 1}", "\"\\x\"", "\"a\nb\"", "{} x", "01", "-", "1.", "tru", "{\"a\":1,\"a\":2}",
            "\"\\u12\"", "[", "{a:1}", "'a'", "nul",
        )
        for (text in bad) assertIs<Parsed.Failed>(Json.parse(text), text)
        assertEquals(3, Json.parse("{\n\"a\": 1,\n\"a\": 2}").problems().single().line)
        assertEquals(2, ThemeLookReader.read("{\n  \"version\": 3.0,").problems().single().line)
        assertIs<Parsed.Failed>(ThemeLookReader.read(source.dropLast(3)))
        assertIs<Parsed.Failed>(ThemeLookReader.read("[]"))
    }

    @Test
    fun jsonReadsAsWritten() {
        val v = Json.parse("\uFEFF { \"a\" : [ -1.5e3 , true , false , null , \"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00e9\\u4E2D\" ] , \"b\" : {} }").ok()
        val o = assertIs<JsonValue.Object>(v)
        assertEquals(listOf("a", "b"), o.members.keys.toList())
        val items = assertIs<JsonValue.Array>(o.members["a"]).items
        assertEquals("-1.5e3", assertIs<JsonValue.Number>(items[0]).text)
        assertEquals(true, assertIs<JsonValue.Bool>(items[1]).value)
        assertEquals(false, assertIs<JsonValue.Bool>(items[2]).value)
        assertIs<JsonValue.Null>(items[3])
        assertEquals("\"\\/\b\u000C\n\r\té中", assertIs<JsonValue.Str>(items[4]).value)
        assertEquals(emptyMap(), assertIs<JsonValue.Object>(o.members["b"]).members)
        assertIs<JsonValue.Array>(Json.parse("[]").ok())
    }

    @Test
    fun deepNestingIsAProblem() {
        fun nested(n: Int) = "[".repeat(n) + "]".repeat(n)
        assertIs<Parsed.Ok<JsonValue>>(Json.parse(nested(Json.MAX_DEPTH)))
        assertIs<Parsed.Failed>(Json.parse(nested(Json.MAX_DEPTH + 1)))
        assertIs<Parsed.Failed>(Json.parse(nested(100_000)))
        fun objects(n: Int) = "{\"a\":".repeat(n) + "1" + "}".repeat(n)
        assertIs<Parsed.Ok<JsonValue>>(Json.parse(objects(Json.MAX_DEPTH)))
        assertIs<Parsed.Failed>(ThemeLookReader.read(objects(Json.MAX_DEPTH + 1)))
    }

    @Test
    fun overLongIsAProblem() {
        val longest = "a".repeat(Json.MAX_STRING)
        assertEquals(longest, assertIs<JsonValue.Str>(Json.parse("\"$longest\"").ok()).value)
        assertIs<Parsed.Failed>(Json.parse("\"${longest}a\""))
        assertIs<Parsed.Failed>(ThemeLookReader.read(source.replace("\"full\"", "\"${longest}a\"")))
        // an escape counts as the character it is
        assertIs<Parsed.Failed>(Json.parse("\"" + "\\u0041".repeat(Json.MAX_STRING + 1) + "\""))
        assertIs<Parsed.Failed>(Json.parse(" ".repeat(Json.MAX_CHARS) + "1"))
    }

    // a file not of the form, refused as kotlinx's ThemeLookJson refuses it: a field missing, one not known, a value of
    // another kind or out of range (JSON's text itself the two read apart where no maker writes it: the app's
    // ThemeLookReaderParityTest)
    @Test
    fun aFileNotOfTheFormIsAProblem() {
        fun failed(from: String, to: String): List<String> {
            val text = source.replace(from, to)
            check(text != source) { "no $from" }
            return ThemeLookReader.read(text).problems().map { it.message }
        }
        assertTrue(failed("\"version\": \"3.0\"", "\"version\": \"2.1\"").single().contains("2.1"))
        assertTrue(failed("\"version\": \"3.0\",", "").single().contains("no \"version\""))
        assertTrue(failed("\"isDark\": true", "\"isDark\": 1").isNotEmpty())
        assertTrue(failed("\"isDark\": true", "\"isDark\": true, \"colors\": {}").single().contains("\"colors\""))
        assertTrue(failed("\"blocks\": true,", "").single().contains("no \"blocks\""))
        assertTrue(failed("\"blocks\": true", "\"blocks\": null").isNotEmpty())
        assertTrue(failed("\"radiusDp\": 8", "\"radiusDp\": 8.0").isNotEmpty())
        assertTrue(failed("\"radiusDp\": 8", "\"radiusDp\": 99").single().contains("radiusDp"))
        assertTrue(failed("\"backgroundColor\": -13816531", "\"backgroundColor\": 4294967295").isNotEmpty())
        assertTrue(failed("\"press\": \"tint\"", "\"press\": \"glow\"").isNotEmpty())
        assertTrue(failed("\"type\": \"darken\"", "\"type\": \"lighten\"").isNotEmpty())
        assertTrue(failed("\"type\": \"darken\"", "\"type\": \"darken\", \"argb\": 1").isNotEmpty())
        val threeInsets = Regex("\"insets\": \\[\\s*12,\\s*12,\\s*12,\\s*12\\s*\\]").replaceFirst(source, "\"insets\": [12, 12, 12]")
        assertTrue(ThemeLookReader.read(threeInsets).problems().single().message.contains("four"))
        assertTrue(failed("\"label\": \"hide\"", "\"label\": \"shown\"").isNotEmpty())
        assertTrue(failed("\"qwerty/q\"", "\"qwerty\"").isNotEmpty())
        assertTrue(failed("\"rotation\": 90", "\"rotation\": 45").isNotEmpty())
        // a base of the picture with no picture: ThemeLook's own rule
        val noPicture = Regex("\"picture\": \\{\\s*\"src\"[\\s\\S]*?\"blurDp\": 4\\s*\\}").replace(source, "\"picture\": null")
        assertTrue(ThemeLookReader.read(noPicture).problems().single().message.contains("picture"))
        // the line of the field not known
        val unknown = source.replace("\"blocks\": true,", "\"blocks\": true,\n\"extra\": 1,")
        assertEquals(source.substringBefore("\"blocks\"").count { it == '\n' } + 2, ThemeLookReader.read(unknown).problems().single().line)
    }

    @Test
    fun overTheMostSingleKeysIsAProblem() {
        fun with(n: Int): String {
            val keys = (0 until n).joinToString(",") { "\"b$it/k\": {\"fill\": null, \"pressed\": null, \"label\": \"hide\"}" }
            return Regex("\"overrides\": \\{[\\s\\S]*?\n {8}\\}").replace(source, "\"overrides\": {$keys}")
        }
        assertEquals(Keys.MAX_OVERRIDES, ThemeLookReader.read(with(Keys.MAX_OVERRIDES)).ok().look.keys.overrides.size)
        assertIs<Parsed.Failed>(ThemeLookReader.read(with(Keys.MAX_OVERRIDES + 1)))
        assertNull(ThemeLookReader.read(with(Keys.MAX_OVERRIDES + 1)).orNull())
    }
}
