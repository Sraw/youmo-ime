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
import org.fcitx.fcitx5.android.scene.layout.Letters
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
        assertEquals(Text.Binding("alt", "q"), q.attrs.alt)
        // a table's roots over the letter where it shows them
        assertEquals(Text.Binding("root", "q"), q.variants.single().attrs.label)
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
        // the page shown last, as ReturnKey's menu opens it (picker: with no name)
        assertEquals("picker:", enter.menu.single().action)
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
        // review L-2: one character, `:` too, is a key the input method is sent (HostKeyAction)
        assertEquals(":", LayoutReader.action(":"))
        assertNull(LayoutReader.action("::"))
        // the placeholders P2 settles, each by name: no action:anything
        assertEquals("action:tone-5", LayoutReader.action("action:tone-5"))
        assertEquals("action:zhuyin-slot", LayoutReader.action("action:zhuyin-slot"))
        assertNull(LayoutReader.action("action:tone"))
        assertNull(LayoutReader.action("action:tone-6"))
        assertNull(LayoutReader.action("action:anything"))
        // a group's code is its letters=, no action (060-render-v2 §15)
        assertNull(LayoutReader.action("action:group"))
        // code: a Unicode scalar value, U+10FFFF at most and no surrogate (review S4a)
        for (ok in listOf("code:U+0000", "code:U+D7FF", "code:U+E000", "code:U+10FFFF")) assertEquals(ok, LayoutReader.action(ok))
        for (bad in listOf("code:U+110000", "code:U+FFFFFF", "code:U+D800", "code:U+DBFF", "code:U+DC00", "code:U+DFFF", "code:U+12", "code:U+d800")) {
            assertNull(LayoutReader.action(bad), bad)
        }
        assertEquals(0x10FFFF, LayoutReader.codePoint("code:U+10FFFF"))
        assertNull(LayoutReader.codePoint("code:U+DFFF"))
        // a layout named; emoji search on or off and nothing else
        assertNull(LayoutReader.action("layout:"))
        assertEquals("emoji-search:off", LayoutReader.action("emoji-search:off"))
        assertNull(LayoutReader.action("emoji-search:maybe"))
        assertNull(LayoutReader.action("emoji-search:"))
    }

    // 060-render-v2 §15: a key's letters as one group, the key's own (no variant's, no default's), row
    // by row as they sit on the key; a tap leaning to where it fell unless lean="off"
    @Test
    fun aGroupKeysLettersAreItsOwn() {
        val k18 = Forms.layout("k18").parts.filterIsInstance<Row>().flatMap { it.cells }.filterIsInstance<Key>()
        assertEquals(Letters(listOf("we")), k18.single { it.id == "r1c2" }.letters)
        // L; whatever its face shows (pinyin's L): the group the engine reads its code by
        assertEquals("l;", k18.single { it.id == "r2c6" }.letters?.group)
        assertNull(k18.single { it.id == "apostrophe" }.letters)
        // the 18 keys send their codes alone; the 14 keys' two letters a row lean
        assertTrue(k18.none { it.leans })
        val k14 = Forms.layout("k14").parts.filterIsInstance<Row>().flatMap { it.cells }.filterIsInstance<Key>()
        assertEquals(Letters(listOf("as")), k14.single { it.id == "r2c1" }.letters)
        assertEquals(listOf(true, false), listOf("r2c1", "r2c5").map { id -> k14.single { it.id == id }.leans })
        assertEquals(emptyList(), problems(board("<row><key id=\"g\" label=\"PQRS\" letters=\"pqrs\"/></row>")))
        val grid = LayoutReader.layout(board("<row><key id=\"g\" label=\"PQRS\" letters=\"pq rs\"/><key id=\"h\" label=\"T\" letters=\"tu\" lean=\"off\"/></row>")).ok()
            .parts.filterIsInstance<Row>().single().cells.filterIsInstance<Key>()
        assertEquals(listOf("pq", "rs"), grid[0].letters?.rows)
        assertEquals("pqrs", grid[0].letters?.group)
        assertEquals("pq rs", grid[0].letters.toString())
        assertEquals(listOf(true, false), grid.map { it.leans })
        for (bad in listOf("PQ", "abcdef", "ab cd ef", "a  b", " ab", "ab ", "", "ü")) {
            val p = problems(board("<row><key id=\"g\" label=\"x\" letters=\"$bad\"/></row>"))
            assertTrue(p.single().contains("letters="), "$bad: $p")
        }
        for (twice in listOf("aba", "ab a")) {
            val p = problems(board("<row><key id=\"g\" label=\"x\" letters=\"$twice\"/></row>"))
            assertTrue(p.single().contains("twice"), "$p")
        }
        // lean= is on or off, a group key's alone
        assertTrue(problems(board("<row><key id=\"g\" label=\"x\" letters=\"ab\" lean=\"no\"/></row>")).single().contains("lean="))
        assertTrue(problems(board("<row><key id=\"g\" label=\"x\" lean=\"off\"/></row>")).single().contains("lean="))
        // a variant's or the defaults' letters= is none of theirs
        assertTrue(problems(board("<row><key id=\"g\" label=\"x\" letters=\"a\"><variant when=\"ime=pinyin\" letters=\"b\"/></key></row>")).isNotEmpty())
        assertTrue(problems(board("<defaults letters=\"a\"/><row><key id=\"g\" label=\"x\"/></row>")).isNotEmpty())
    }

    // review L-7: a board split in halves has no one side; asking which is refused until it means something
    @Test
    fun splitSideIsRefusedOnABoardSplitInHalves() {
        val asks = "<row><key id=\"a\" label=\"a\" send=\"commit:a\" when=\"split-side=left\"/></row>"
        val p = problems(board(asks, " on-split=\"split\""))
        assertTrue(p.single().contains("not supported yet"), "$p")
        assertEquals(emptyList(), problems(board(asks)))
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
