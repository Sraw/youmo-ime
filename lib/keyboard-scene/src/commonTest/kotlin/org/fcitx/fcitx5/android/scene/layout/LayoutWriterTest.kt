/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Fixtures
import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.SceneText
import org.fcitx.fcitx5.android.scene.TEST_DATA
import org.fcitx.fcitx5.android.scene.engine.FixedMeasurer
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.readText
import org.fcitx.fcitx5.android.scene.recording
import org.fcitx.fcitx5.android.scene.testData
import org.fcitx.fcitx5.android.scene.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// a layout as the maker saves it and the app exports it (061-custom-layout §12): read back, the same layout
class LayoutWriterTest {
    private val boards = listOf(
        "emoji-search", "emoji", "emoticon", "expanded-side", "k14", "k18", "nine", "number-row", "number",
        "qwerty", "symbols", "zhuyin-nine", "zhuyin", "k17", "layout-every",
    )
    private val fragments = mapOf("grouped-bottom" to LayoutReader.fragment(testData("grouped-bottom.xml")).ok())

    private fun written(name: String) = LayoutWriter.write(LayoutReader.layout(testData("$name.xml")).ok())

    @Test
    fun whatIsWrittenReadsBackAsItWasWritten() {
        for (name in boards) {
            val once = written(name)
            assertEquals(once, LayoutWriter.write(LayoutReader.layout(once).ok()), name)
        }
    }

    // a control in a name (pasted on the maker's page): left out as written, as the reader drops one, so what is written
    // reads back (R26 F8187)
    @Test
    fun aCharacterXmlHasNoneForIsLeftOut() {
        val l = LayoutReader.layout(testData("qwerty.xml")).ok()
        val named = Layout(l.id, "26\u0001键\uFFFE", l.family, l.columns, l.onSplit, l.defaults, l.parts, l.preview, l.popups, l.basedOn, l.meta, l.pads)
        val text = LayoutWriter.write(named)
        assertTrue('\u0001' !in text && '\uFFFE' !in text)
        assertEquals("26键", LayoutReader.layout(text).ok().name)
    }

    // the same board: every scene of it in the app's environments drawn alike from the file and from what is written of it
    @Test
    fun whatIsWrittenDrawsAsTheFileDoes() {
        for (name in boards.filter { it != "layout-every" }) {
            val file = LayoutReader.layout(testData("$name.xml")).ok()
            val again = LayoutReader.layout(LayoutWriter.write(file)).ok()
            for ((size, env) in Fixtures.sizes) {
                fun scene(l: Layout) = SceneBuilder(Fixtures.materialLight, env, FixedMeasurer).build(l, fragments).ok()
                // a key's line is where it was written: not the layout
                assertEquals(SceneText.of(scene(file)).replace(line, ""), SceneText.of(scene(again)).replace(line, ""), "$name $size")
            }
        }
    }

    @Test
    fun aGapsLookIsNotWritten() {
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\">" +
                "<row><gap width=\"1\" look=\"function\" class=\"blank\"/><key id=\"a\" label=\"a\"/></row></keyboard>",
        ).ok()
        val text = LayoutWriter.write(l)
        assertFalse("look=" in text || "class=" in text, text)
        assertTrue("<gap/>" in text, text)
    }

    // a function key's name holding a / is written whole: bare, fn/a/b would read back as the character key a/b
    @Test
    fun aFunctionKeysNameWithASlashIsWrittenWhole() {
        val l = LayoutReader.layout(
            "<keyboard xmlns=\"${LayoutReader.NAMESPACE}\" id=\"t\" name=\"t\" family=\"symbols\">" +
                "<row><key id=\"a\" label=\"a\" shortcut=\"fn/a/b\"/><key id=\"c\" label=\"c\" shortcut=\"comma\"/></row></keyboard>",
        ).ok()
        val text = LayoutWriter.write(l)
        assertTrue("shortcut=\"fn/a/b\"" in text && "shortcut=\"comma\"" in text, text)
        val again = LayoutReader.layout(text).ok().parts.filterIsInstance<Row>().single().cells.filterIsInstance<Key>()
        assertEquals(listOf("fn/a/b", "fn/comma"), again.map { it.attrs.shortcut })
    }

    // a number as a person writes it, on the JVM and JS alike: never in an exponent, the same value read back
    @Test
    fun aNumberIsWrittenWithoutAnExponent() {
        val cases = mapOf(
            0.0001 to "0.0001", 1e-7 to "0.0000001", 0.000012345 to "0.000012345", -0.0005 to "-0.0005", -1e-7 to "-0.0000001", -2.5e-6 to "-0.0000025",
            0.5 to "0.5", 1.25 to "1.25", 12345678.9 to "12345678.9", 3.0 to "3",
        )
        for ((v, s) in cases) {
            assertEquals(s, LayoutWriter.number(v), "$v")
            assertEquals(v, s.toDouble(), s)
        }
        // widths from 0.0001 to 20, each 1.37 times the last: no letter, read back as written
        var w = 0.0001
        while (w < 20) {
            val s = LayoutWriter.number(w)
            assertTrue(s.all { it.isDigit() || it == '.' }, s)
            assertEquals(w, s.toDouble(), s)
            w *= 1.37
        }
    }

    // contract: the canonical text itself (what users save and share): what is written changes only on purpose
    @Test
    fun theCanonicalTextIsTheSnapshots() {
        val failed = listOf("layout-every", "k18").mapNotNull { name ->
            val text = written(name)
            val path = "$TEST_DATA/writer/$name.layout"
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

    private val line = Regex(" line=[0-9]+")
}
