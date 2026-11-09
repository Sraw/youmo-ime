/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.Border
import org.fcitx.fcitx5.android.scene.theme.ColorExpr
import org.fcitx.fcitx5.android.scene.theme.Fill
import org.fcitx.fcitx5.android.scene.theme.Inset
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.Shadow
import org.fcitx.fcitx5.android.scene.theme.ShapePath
import org.fcitx.fcitx5.android.scene.theme.Style
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.ThemeWriter
import org.fcitx.fcitx5.android.scene.theme.Value
import org.fcitx.fcitx5.android.scene.theme.Values
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ThemeWriterTest {

    private fun read(name: String): Theme = ThemeReader.theme(testData(name)).ok()

    // every theme of the tests, the one of every form among them: read back, the theme written
    @Test
    fun aThemeWrittenReadsBackAsItself() {
        for (name in THEMES) {
            val theme = read(name)
            assertSameTheme(theme, rewritten(theme), name)
        }
    }

    // the text is the theme's alone: written again, read back, the same text; the other version picked, the same file
    @Test
    fun theTextIsCanonical() {
        for (name in THEMES) {
            val text = ThemeWriter.write(read(name))
            assertEquals(text, ThemeWriter.write(ThemeReader.theme(text).ok()), name)
            assertEquals(text, ThemeWriter.write(read(name).variant(true)), name)
        }
        // two files of one theme, written apart (attributes, scenes, classes and states in another order, a
        // colour of six digits, a variable for a colour, a zero with its unit): one text
        val a = """
            <theme xmlns="${ThemeReader.NAMESPACE}" name="T" dark="false" version="4.0"><colors>${colors("#eceff1")}</colors>
            <style select="@flat @dark key.function.alt-text:pressed:locked" radius="0dp" fill="#ffffff"/></theme>
        """.trimIndent()
        val b = """
            <theme version="4.0" variants="light" name="T" xmlns="${ThemeReader.NAMESPACE}"><colors>${colors("@keyboard")}</colors>
            <style fill="#ffffffff" radius="0" select="@dark @flat key.alt-text.function:locked:pressed"/></theme>
        """.trimIndent()
        assertEquals(ThemeWriter.write(ThemeReader.theme(a).ok()), ThemeWriter.write(ThemeReader.theme(b).ok()))
    }

    // the 24 colours, `background` as [background], `keyboard` #eceff1
    private fun colors(background: String) = ThemeReader.REQUIRED_COLORS.joinToString("") { name ->
        val value = when (name) {
            "background" -> background
            "keyboard" -> "#eceff1"
            else -> "#000000"
        }
        """<color name="$name" value="$value"/>"""
    }

    // the canonical text itself, held: what an export writes changes only on purpose
    @Test
    fun theCanonicalTextIsTheSnapshots() {
        val failed = listOf("material-light.xml", "writer-every.xml").mapNotNull { name ->
            val text = ThemeWriter.write(read(name))
            val path = "$TEST_DATA/writer/$name"
            val committed = readText(path)
            when {
                recording() -> null.also { writeText(path, text) }
                committed == null -> "no $path: run the JVM tests with -Psnapshots.record=true"
                committed != text -> "$path differs: review, then record anew"
                else -> null
            }
        }
        assertTrue(failed.isEmpty(), failed.joinToString("\n"))
    }

    @Test
    fun aKeysNameHasWhatASelectorReadsEscaped() {
        for (name in listOf(",", ".", ":", "[x", "*", "a b", "\\", "/", ".a", "q", "a*", "x:y.z")) {
            val written = ThemeWriter.selector(Selector(emptySet(), "key", board = "zhuyin", key = name, states = setOf("pressed")))
            val read = Selector.parseList(written)
            assertEquals(listOf(Selector(emptySet(), "key", board = "zhuyin", key = name, states = setOf("pressed"))), read, written)
        }
        assertEquals("zhuyin/\\,", ThemeWriter.selector(Selector.parse("zhuyin/\\,")!!))
        assertEquals("qwerty/*", ThemeWriter.selector(Selector.parse("qwerty/*")!!))
        assertEquals("*/enter:on", ThemeWriter.selector(Selector.parse("*/enter:on")!!))
        assertEquals("@dark @flat key", ThemeWriter.selector(Selector.parse("@flat @dark key")!!))
        assertEquals("@flat @field=password @im=wubi key", ThemeWriter.selector(Selector.parse("@im=wubi @flat @field=password key")!!))
    }

    @Test
    fun numbersArePlainDecimals() {
        assertEquals("4", ThemeWriter.number(4.0))
        assertEquals("0.5", ThemeWriter.number(0.5))
        assertEquals("-1.25", ThemeWriter.number(-1.25))
        assertEquals("0", ThemeWriter.number(0.0))
        assertEquals("0.0001", ThemeWriter.number(0.0001))
        assertEquals("0.00000125", ThemeWriter.number(1.25e-6))
        assertEquals("-0.0000001", ThemeWriter.number(-1e-7))
        assertEquals("1000000000000000000000", ThemeWriter.number(1e21))
        assertEquals("120", ThemeWriter.number(120.0))
    }

    // values a theme read never has but one made may: written as what draws alike
    @Test
    fun valuesMadeNotReadAreWrittenAsWhatTheyDraw() {
        assertEquals("none", ThemeWriter.value("shadow", Value.OfShadow(Shadow(0.0, 1.0, 0.0, null))))
        assertEquals("none", ThemeWriter.value("border", Value.OfBorder(Border(1.0, null))))
        assertEquals("0 0 0 #000000", ThemeWriter.value("shadow", Value.OfShadow(Shadow(0.0, 0.0, 0.0, ColorExpr.Literal(Argb(0xff000000.toInt()))))))
        assertEquals("1dp 2dp 3dp 4dp", ThemeWriter.value("inset", Value.OfInset(Inset(1.0, 2.0, 3.0, 4.0))))
        // a style that sets nothing (a later stage's properties alone) is not written: a file of it would not read
        val t = read("material-light.xml")
        val nothing = Style(listOf(Selector.parse("preview")!!), emptyMap(), t.styles.size, 0)
        val more = Theme(t.name, t.dark, t.version, t.colors, t.styles + nothing, t.fonts, t.icons, t.picture, t.variants, t.baseColors, t.darkColors, t.meta)
        assertEquals(ThemeWriter.write(t), ThemeWriter.write(more))
    }

    // a picture() of the <picture>'s blur and brightness with a colour over it: no text says it (picture() names
    // both, or neither and no colour), and no theme read or made has one; written, it is refused, not guessed
    @Test
    fun aPictureOfTheThemesBlurWithAColourIsRefused() {
        assertEquals("picture", ThemeWriter.value("fill", Value.OfFill(Fill.Picture(null, null))))
        assertEquals("picture(4dp, 90%, @key)", ThemeWriter.value("fill", Value.OfFill(Fill.Picture(4.0, 90.0, ColorExpr.Ref("key")))))
        for (half in listOf(Fill.Picture(null, null, ColorExpr.Ref("key")), Fill.Picture(4.0, null), Fill.Picture(null, 90.0))) {
            assertFailsWith<IllegalArgumentException>("$half") { ThemeWriter.value("fill", Value.OfFill(half)) }
        }
    }

    // a colour near the longest the reader takes (ColorExpr.MAX_LENGTH), functions nested, in its shortest form:
    // written as it is, and read back; one as an author may write it (spaces, `transparent`, an opaque alpha) shorter
    @Test
    fun aLongColourIsWrittenNoLonger() {
        val system = "system(surface-container-highest,#80abcdef)"
        var long = "darker(#80123456,10%)"
        repeat(4) { long = "mix($long,$system,100%)" }
        assertTrue(long.length in ColorExpr.MAX_LENGTH - 30..ColorExpr.MAX_LENGTH, "${long.length}")
        val c = assertNotNull(ColorExpr.parse(long))
        assertEquals(long, ThemeWriter.color(c))
        // in a theme, as a style's fill: written and read back the same
        val t = read("material-light.xml")
        val style = Style(listOf(Selector.parse("key")!!), mapOf("fill" to Value.OfFill(Fill.Solid(c))), t.styles.size, 0)
        val more = Theme(t.name, t.dark, t.version, t.colors, t.styles + style, t.fonts, t.icons, t.picture, t.variants, t.baseColors, t.darkColors, t.meta)
        assertSameTheme(more, rewritten(more), "a long colour")
        val spaced = "mix( darker(#FF123456, 10%) , system(primary, transparent), 50% )"
        val written = ThemeWriter.color(assertNotNull(ColorExpr.parse(spaced)))
        assertEquals("mix(darker(#123456,10%),system(primary,#00000000),50%)", written)
        assertEquals(ColorExpr.parse(spaced), ColorExpr.parse(written))
        assertEquals("#80123456", ThemeWriter.color(ColorExpr.parse("#80123456")!!))
    }

    // T4b's: a path (written absolute), the bubble's size in % and its offset and tail, an inner shadow, a cap's
    // depth and its colour; each written as the reader takes it, and read back the same
    @Test
    fun aCapsAndABubblesValuesAreWrittenAndReadBack() {
        val every = read("writer-every.xml").styles.flatMap { it.values.entries }
        for (p in listOf("inner-shadow", "depth", "depth-color", "tail", "offset")) assertTrue(every.any { it.key == p }, p)
        assertTrue(every.any { it.value is Value.OfPath } && every.any { it.value is Value.Percent })
        val written = listOf(
            "shape" to "path(M0.1 0 L0.9 0 Q1 0 1 0.1 C0 0.05 0.5 0.5 0.1 1 Z L0.3 0.3 Z)",
            "size" to "125.5%", "offset" to "-3.25dp", "offset" to "0", "depth" to "2.5dp", "depth" to "0",
            "inner-shadow" to "0 -1.5dp 2dp #66c0c3c4", "inner-shadow" to "none", "depth-color" to "darker(@key,20%)", "tail" to "false",
        )
        for ((property, raw) in written) {
            val v = assertNotNull(Values.read(property, raw), raw)
            assertEquals(raw, ThemeWriter.value(property, v))
            assertEquals(v, Values.read(property, ThemeWriter.value(property, v)), raw)
        }
        // as an author may write them: relative, H and V, a second Z, digits past six decimals; -0dp
        val path = assertNotNull(Values.read("shape", "path(m.1 0 h.8 q.1 0 .1 .1 Z z L.1234567 .2 v.1 z)"))
        assertEquals("path(M0.1 0 L0.9 0 Q1 0 1 0.1 Z L0.123457 0.2 L0.123457 0.3 Z)", ThemeWriter.value("shape", path))
        assertEquals(path, Values.read("shape", ThemeWriter.value("shape", path)))
        assertEquals("0", ThemeWriter.value("offset", assertNotNull(Values.read("offset", "-0dp"))))
    }

    // T7a's: a handle's grip (dp, a number of a handle alone), the navigation bar's follow; read back the same
    @Test
    fun theWindowsValuesAreWrittenAndReadBack() {
        val theme = read("writer-every.xml")
        val grip = theme.styles.single { s -> s.selectors.all { it.widget == "handle" } }.values
        assertEquals(mapOf("width" to Value.Number(40.5), "height" to Value.Number(0.0)), grip.filterKeys { it == "width" || it == "height" })
        val text = ThemeWriter.write(theme)
        assertTrue("width=\"40.5dp\" height=\"0\"" in text, text)
        assertTrue("<style select=\"navbar\" color=\"follow\"/>" in text, text)
        // read back (the whole theme, as aThemeWrittenReadsBackAsItself's THEMES): the grip's numbers the same
        val back = ThemeReader.theme(text).ok().styles.single { s -> s.selectors.all { it.widget == "handle" } }.values
        assertEquals(grip, back)
        assertEquals(Values.read("offset", "0"), Values.read("offset", "-0dp"))
    }

    // the longest path, its points past six decimals, a Z after every third (after each, it would be lines, no
    // outline): written no longer than a path is read, and read back
    @Test
    fun theLongestPathWrittenReadsBack() {
        val raw = (0 until ShapePath.MAX_POINTS).joinToString(" ") { i ->
            val x = (123_457 + i * 3001).toString()
            val y = (if (i % 2 == 0) "8" else "2") + (12_345 + i * 37).toString()
            "${if (i == 0) 'M' else 'L'}0.${x}9 0.${y}1" + if (i % 3 == 2) " Z" else ""
        }
        val path = assertNotNull(Values.read("shape", "path($raw)"))
        val written = ThemeWriter.value("shape", path)
        assertTrue(written.removeSurrounding("path(", ")").length <= ShapePath.MAX_LENGTH, "${written.length}")
        assertEquals(path, Values.read("shape", written))
    }

    private companion object {
        val THEMES = listOf("material-light.xml", "material-dark.xml", "reserved.xml", "writer-every.xml")
    }
}
