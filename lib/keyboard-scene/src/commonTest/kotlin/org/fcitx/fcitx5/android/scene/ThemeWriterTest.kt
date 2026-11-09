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
import org.fcitx.fcitx5.android.scene.theme.Style
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.theme.ThemeWriter
import org.fcitx.fcitx5.android.scene.theme.Value
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals("0 0 0 #ff000000", ThemeWriter.value("shadow", Value.OfShadow(Shadow(0.0, 0.0, 0.0, ColorExpr.Literal(Argb(0xff000000.toInt()))))))
        assertEquals("picture(0dp, 100%, @key)", ThemeWriter.value("fill", Value.OfFill(Fill.Picture(null, null, ColorExpr.Ref("key")))))
        assertEquals("1dp 2dp 3dp 4dp", ThemeWriter.value("inset", Value.OfInset(Inset(1.0, 2.0, 3.0, 4.0))))
        // a style that sets nothing (a later stage's properties alone) is not written: a file of it would not read
        val t = read("material-light.xml")
        val nothing = Style(listOf(Selector.parse("preview")!!), emptyMap(), t.styles.size, 0)
        val more = Theme(t.name, t.dark, t.version, t.colors, t.styles + nothing, t.fonts, t.icons, t.picture, t.variants, t.baseColors, t.darkColors, t.meta)
        assertEquals(ThemeWriter.write(t), ThemeWriter.write(more))
    }

    private companion object {
        val THEMES = listOf("material-light.xml", "material-dark.xml", "reserved.xml", "writer-every.xml")
    }
}
