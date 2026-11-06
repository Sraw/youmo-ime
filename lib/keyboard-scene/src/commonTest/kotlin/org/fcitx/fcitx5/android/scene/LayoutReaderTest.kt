/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.layout.Condition
import org.fcitx.fcitx5.android.scene.layout.Face
import org.fcitx.fcitx5.android.scene.layout.Gap
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.layout.OnSplit
import org.fcitx.fcitx5.android.scene.layout.Row
import org.fcitx.fcitx5.android.scene.layout.RowSplit
import org.fcitx.fcitx5.android.scene.layout.Slot
import org.fcitx.fcitx5.android.scene.layout.Text
import org.fcitx.fcitx5.android.scene.layout.Width
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// layout-1.xsd as the engine reads it; what the XSD cannot say checked on reading (060-render-xsd README)
class LayoutReaderTest {
    private val ns = LayoutReader.NAMESPACE

    private fun board(rows: String, extra: String = "") =
        "<keyboard xmlns=\"$ns\" id=\"test\" name=\"t\" family=\"symbols\" columns=\"10\"$extra>\n$rows\n</keyboard>"

    private fun problems(source: String) = LayoutReader.layout(source).problems().map { it.toString() }

    @Test
    fun theQwertyExampleReads() {
        val l = Fixtures.qwerty
        assertEquals("qwerty", l.id)
        assertEquals(10.0, l.columns)
        assertEquals(OnSplit.Split, l.onSplit)
        val rows = l.parts.filterIsInstance<Row>()
        assertEquals(4, rows.size)
        assertEquals(listOf(RowSplit.Halves, RowSplit.Halves, RowSplit.Halves, RowSplit.Scale), rows.map { it.split })
        val q = rows[0].cells.first() as Key
        assertEquals(Text.Binding("case", "q"), q.attrs.label)
        assertEquals(Text.Literal("1"), q.attrs.alt)
        assertEquals("q", q.attrs.actions["send"])
        assertEquals("full/q", q.attrs.shortcut)
        val space = rows[3].cells.first { it.id == "space" } as Key
        assertEquals(Width.Fill, space.attrs.width)
        assertEquals(Face.Special, space.attrs.face)
        assertEquals(Text.Binding("hold-badge", null), space.attrs.badge)
        val semicolon = rows[1].cells.first { it.id == "semicolon" } as Key
        assertEquals(true, semicolon.attrs.splitAfter)
        assertEquals(false, semicolon.attrs.mark)
        val enter = rows[3].cells.first { it.id == "enter" } as Key
        assertEquals("picker:Emoji", enter.menu.single().action)
        val domain = rows[3].cells.first { it.id == "domain" } as Key
        assertEquals(listOf(".com", ".cn", ".net", ".org"), domain.popup?.items?.map { it.first })
        assertEquals(true, (rows[2].cells.first { it.id == "backspace" } as Key).attrs.actions["repeat"]?.toBoolean())
    }

    @Test
    fun unknownAttributeIsAProblemWithItsLine() {
        val p = problems(board("<row>\n<key id=\"a\" label=\"a\" colour=\"red\"/>\n</row>"))
        assertEquals(listOf("line 3: <key>: unknown attribute colour="), p)
    }

    @Test
    fun idsAreUnique() {
        val p = problems(board("<row><key id=\"a\" label=\"a\"/></row>\n<row><key id=\"a\" label=\"b\"/></row>"))
        assertTrue(p.single().contains("the id \"a\" is used twice"), "$p")
    }

    @Test
    fun keysAcrossRowsAreRead() {
        val p = problems(board("<row><key id=\"a\" label=\"a\" rows=\"2\"/></row>"))
        assertTrue(p.none { "rows" in it }, "$p")
    }

    @Test
    fun anIconKeyNeedsA11y() {
        val p = problems(board("<row><key id=\"a\" icon=\"x\"/></row>"))
        assertTrue(p.single().contains("a11y"), "$p")
    }

    @Test
    fun aLettersBoardHasAllTwentySixLetters() {
        val src = "<keyboard xmlns=\"$ns\" id=\"qwerty\" name=\"t\" family=\"letters\" columns=\"10\"><row><key id=\"q\" label=\"q\"/></row></keyboard>"
        assertTrue(problems(src).single().contains("lacks the keys a b c"), "${problems(src)}")
    }

    @Test
    fun actionsAreOfTheVocabulary() {
        assertEquals("sym:BackSpace", LayoutReader.action("sym:BackSpace"))
        assertEquals("caps:lock", LayoutReader.action("caps:lock"))
        assertEquals("code:U+F0104", LayoutReader.action("code:U+F0104"))
        assertNull(LayoutReader.action("launch:rockets"))
        val p = problems(board("<row><key id=\"a\" label=\"a\" send=\"launch:rockets\"/></row>"))
        assertTrue(p.single().contains("send="), "$p")
    }

    @Test
    fun textsAndBindings() {
        assertEquals(Text.Literal("a"), LayoutReader.text("a"))
        assertEquals(Text.Binding("case", "q"), LayoutReader.text("{case:q}"))
        assertEquals(Text.Binding("comma", null), LayoutReader.text("{comma}"))
        assertNull(LayoutReader.text("{nonsense}"))
    }

    @Test
    fun wrongRootAndMalformedXml() {
        assertIs<Parsed.Failed>(LayoutReader.layout("<fragment xmlns=\"$ns\" id=\"x\"/>"))
        assertIs<Parsed.Failed>(LayoutReader.layout("<keyboard"))
        assertTrue(problems(board("<row/>")).isNotEmpty())
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\"/></row>", " on-split=\"sideways\"")).isNotEmpty())
        assertTrue(problems("<keyboard xmlns=\"x\" id=\"t\" name=\"t\" family=\"symbols\" columns=\"10\"><row><key id=\"a\" label=\"a\"/></row></keyboard>").isNotEmpty())
    }

    @Test
    fun fragmentsAndIncludes() {
        val f = LayoutReader.fragment("<fragment xmlns=\"$ns\" id=\"bottom\"><defaults kind=\"function\"/><row><key id=\"x\" label=\"x\"/></row></fragment>").ok()
        assertEquals("bottom", f.id)
        assertEquals("function", f.defaults.kind)
        assertEquals(1, f.rows.size)
    }

    @Test
    fun conditionsReadWithTheirOrGroups() {
        val semicolon = Fixtures.qwerty.parts.filterIsInstance<Row>()[1].cells.first { it.id == "semicolon" }
        val c: Condition? = semicolon.condition
        assertEquals(2, c?.anyOf?.size)
    }

    @Test
    fun anIdTwiceIsFineWhereEachHasACondition() {
        // the number pad's `-`: at one place or another as the pad is; the engine checks those shown (Expand)
        val ok = board("<row><key id=\"a\" label=\"a\" when=\"pad=signed\"/></row>\n<row><key id=\"a\" label=\"b\" when=\"pad=phone\"/></row>")
        assertEquals(emptyList(), problems(ok))
        val half = board("<row><key id=\"a\" label=\"a\" when=\"pad=signed\"/></row>\n<row><key id=\"a\" label=\"b\"/></row>")
        assertTrue(problems(half).single().contains("used twice"))
    }

    @Test
    fun variantsGapsWithALookAndBoardSwitches() {
        val l = LayoutReader.layout(
            board(
                "<row><key id=\"a\" label=\"a\"><variant when=\"composing\" label=\"分词\" text-size=\"16\"/></key>" +
                    "<gap width=\"1\" look=\"function\" class=\"blank\"/><slot id=\"s\" kind=\"nine-side\" width=\"2\" look=\"function\" face=\"keep\"/></row>",
                " preview=\"off\" popups=\"off\"",
            ),
        ).ok()
        assertEquals(false, l.preview)
        assertEquals(false, l.popups)
        val cells = l.parts.filterIsInstance<Row>().single().cells
        val a = assertIs<Key>(cells[0])
        assertEquals(Text.Literal("分词"), a.variants.single().attrs.label)
        assertEquals(16.0, a.variants.single().attrs.textSize)
        assertEquals("function", assertIs<Gap>(cells[1]).look)
        assertEquals(listOf("blank"), assertIs<Gap>(cells[1]).classes)
        assertEquals("function", assertIs<Slot>(cells[2]).look)
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\"><variant label=\"b\"/></key></row>")).single().contains("when"))
        assertTrue(problems(board("<row><gap width=\"1\"><key id=\"a\" label=\"a\"/></gap><key id=\"b\" label=\"b\"/></row>")).isNotEmpty())
    }

    @Test
    fun popupsAreChecked() {
        assertEquals(emptyList(), problems(board("<row><key id=\"a\" label=\"a\"><popup from=\"nine\"/></key></row>")))
        assertEquals(emptyList(), problems(board("<row><key id=\"a\" label=\"a\"><popup preset=\"q\" symbols-from=\"2\"/></key></row>")))
        // a preset names a key; options are of their kind; items only for a list
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\"><popup preset=\"a b\"/></key></row>")).isNotEmpty())
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\"><popup from=\"none\" preset=\"q\"/></key></row>")).isNotEmpty())
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\"><popup symbols-from=\"many\"/></key></row>")).isNotEmpty())
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\"><popup from=\"list\"/></key></row>")).isNotEmpty())
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\"><popup from=\"nine\"><item text=\"x\"/></popup></key></row>")).isNotEmpty())
    }

    @Test
    fun badBindingsAndNumbersAreProblems() {
        assertTrue(problems(board("<row><key id=\"a\" icon=\"x\" a11y=\"{nonsense}\"/></row>")).isNotEmpty())
        // a number the JVM and JS would read differently is not one (M3)
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\" width=\"1e1\"/></row>")).isNotEmpty())
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\" width=\"0x10\"/></row>")).isNotEmpty())
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\" width=\" 2\"/></row>")).isNotEmpty())
        assertEquals(emptyList(), problems(board("<row><key id=\"a\" label=\"a\" width=\".5\"/></row>")))
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\" rows=\"+2\"/></row>")).isNotEmpty())
        assertEquals(Text.Binding("punct", "，"), LayoutReader.text("{punct:，}"))
        assertNull(LayoutReader.text("{punct: }"))
    }
}
