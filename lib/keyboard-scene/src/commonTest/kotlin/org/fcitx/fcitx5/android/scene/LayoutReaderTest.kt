/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.layout.Condition
import org.fcitx.fcitx5.android.scene.layout.Direction
import org.fcitx.fcitx5.android.scene.layout.Face
import org.fcitx.fcitx5.android.scene.layout.Gap
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.LayoutMeta
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.layout.Letters
import org.fcitx.fcitx5.android.scene.layout.OnSplit
import org.fcitx.fcitx5.android.scene.layout.Row
import org.fcitx.fcitx5.android.scene.layout.RowSplit
import org.fcitx.fcitx5.android.scene.layout.Slot
import org.fcitx.fcitx5.android.scene.layout.Text
import org.fcitx.fcitx5.android.scene.layout.Width
import org.fcitx.fcitx5.android.scene.skin.assertRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutReaderTest {
    private val ns = LayoutReader.NAMESPACE

    private fun board(rows: String, extra: String = "") =
        "<keyboard xmlns=\"$ns\" id=\"test\" name=\"t\" family=\"symbols\" columns=\"10\"$extra>\n$rows\n</keyboard>"

    private fun problems(source: String) = LayoutReader.layout(source).problems().map { it.toString() }

    // contract: the committed example layout reads as written; a reader change that breaks a shared layout fails here
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
        assertNull(q.attrs.alt)
        assertEquals(Text.Binding("root", "q"), q.variants.single().attrs.label)
        assertEquals("q", q.attrs.actions["send"])
        assertNull(q.attrs.actions["swipe-up"])
        assertEquals("full/q", q.attrs.shortcut)
        val theirs = LayoutReader.layout(board("<row><key id=\"q\" label=\"q\" alt=\"{alt:q}\" swipe-up=\"1\" shortcut=\"q\"/></row>")).ok().parts.filterIsInstance<Row>().single().cells.single() as Key
        assertEquals(Text.Binding("alt", "q"), theirs.attrs.alt)
        assertEquals("1", theirs.attrs.actions["swipe-up"])
        val space = rows[3].cells.first { it.id == "space" } as Key
        assertEquals(Width.Fill, space.attrs.width)
        assertEquals(Face.Special, space.attrs.face)
        assertEquals(Text.Binding("hold-badge", null), space.attrs.badge)
        val semicolon = rows[1].cells.first { it.id == "semicolon" } as Key
        assertEquals(true, semicolon.attrs.splitAfter)
        assertEquals(false, semicolon.attrs.mark)
        val enter = rows[3].cells.first { it.id == "enter" } as Key
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
    fun aKeyIdStartingViewIsRefused() {
        // view: names an old View key to KeyRef; a scene key of that id would be routed as one
        val p = problems(board("<row><key id=\"view:3\" label=\"a\"/></row>"))
        assertTrue(p.single().contains("not starting view:"), "$p")
    }

    @Test
    fun keysAcrossRowsAreRead() {
        val p = problems(board("<row><key id=\"a\" label=\"a\" rows=\"2\"/></row>"))
        assertEquals(emptyList(), p)
    }

    @Test
    fun anIconKeyNeedsA11y() {
        val p = problems(board("<row><key id=\"a\" icon=\"x\"/></row>"))
        assertTrue(p.single().contains("a11y"), "$p")
    }

    @Test
    fun aLettersBoardLackingLettersReads() {
        val src = "<keyboard xmlns=\"$ns\" id=\"qwerty\" name=\"t\" family=\"letters\" columns=\"10\"><row><key id=\"q\" label=\"q\"/></row></keyboard>"
        assertEquals(emptyList(), problems(src), "the check tells what it lacks (LayoutCheck's lacks)")
    }

    @Test
    fun actionsAreOfTheVocabulary() {
        assertEquals("sym:BackSpace", LayoutReader.action("sym:BackSpace"))
        assertEquals("caps:lock", LayoutReader.action("caps:lock"))
        assertEquals("code:U+F0104", LayoutReader.action("code:U+F0104"))
        assertNull(LayoutReader.action("launch:rockets"))
        val p = problems(board("<row><key id=\"a\" label=\"a\" send=\"launch:rockets\"/></row>"))
        assertTrue(p.single().contains("send="), "$p")
        assertEquals(":", LayoutReader.action(":"))
        assertNull(LayoutReader.action("::"))
        assertEquals("action:tone-5", LayoutReader.action("action:tone-5"))
        assertEquals("action:zhuyin-slot", LayoutReader.action("action:zhuyin-slot"))
        assertNull(LayoutReader.action("action:tone"))
        assertNull(LayoutReader.action("action:tone-6"))
        assertNull(LayoutReader.action("action:anything"))
        assertNull(LayoutReader.action("action:group"))
        for (ok in listOf("code:U+0000", "code:U+D7FF", "code:U+E000", "code:U+10FFFF")) assertEquals(ok, LayoutReader.action(ok))
        for (bad in listOf("code:U+110000", "code:U+FFFFFF", "code:U+D800", "code:U+DBFF", "code:U+DC00", "code:U+DFFF", "code:U+12", "code:U+d800")) {
            assertNull(LayoutReader.action(bad), bad)
        }
        assertEquals(0x10FFFF, LayoutReader.codePoint("code:U+10FFFF"))
        assertNull(LayoutReader.codePoint("code:U+DFFF"))
        assertNull(LayoutReader.action("layout:"))
        assertEquals("emoji-search:off", LayoutReader.action("emoji-search:off"))
        assertNull(LayoutReader.action("emoji-search:maybe"))
        assertNull(LayoutReader.action("emoji-search:"))
    }

    // 0.6.0's handwriting keyboard's 注: a layout written for it still reads, warned of, the key doing nothing of it
    @Test
    fun theHandwritingKeyboardsReadingKeyIsWarnedOfAndRead() {
        val l = LayoutReader.layout(
            board(
                "<row><key id=\"a\" label=\"注\" role=\"reading\" send=\"reading\" long-press=\"reading:tone\">" +
                    "<popup from=\"list\"><item text=\"x\" send=\"reading\"/></popup>" +
                    "<menu><item label=\"y\" do=\"reading\"/><item label=\"z\" do=\"lang\"/></menu></key></row>",
            ),
        )
        assertEquals(emptyList(), l.problems().map { it.toString() })
        assertEquals(5, l.warnings().size, "${l.warnings()}")
        assertTrue(l.warnings().all { "注 is gone" in it.message }, "${l.warnings()}")
        val only = LayoutReader.layout(
            board("<row><key id=\"a\" label=\"注\"><menu><item label=\"y\" do=\"reading:tone\"/><item label=\"z\" do=\"reading:style\"/></menu></key></row>"),
        )
        assertEquals(emptyList(), only.problems().map { it.toString() })
        assertEquals(2, only.warnings().size, "${only.warnings()}")
        assertEquals(emptyList(), (only.ok().parts.filterIsInstance<Row>().single().cells.single() as Key).menu)
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\"><menu></menu></key></row>")).isNotEmpty())
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\" role=\"reading:tone\"/></row>")).isNotEmpty())
    }

    @Test
    fun aKeysPreviewIsOnOrOff() {
        val nine = Forms.layout("zhuyin-nine").parts.filterIsInstance<Row>().flatMap { it.cells }.filterIsInstance<Key>().associateBy { it.id }
        assertEquals("on", nine.getValue("r1c1").attrs.actions["preview"])
        assertEquals("on", nine.getValue("r2c5").attrs.actions["preview"])
        assertEquals("off", nine.getValue("r4c4").attrs.actions["preview"])
        assertNull(nine.getValue("r1c2").attrs.actions["preview"])
        val p = problems(board("<row><key id=\"a\" label=\"a\" preview=\"maybe\"/></row>"))
        assertTrue(p.single().contains("preview"), "$p")
    }

    // a key's letters as one group, the key's own (no variant's, no default's), row by row as they sit on the key
    @Test
    fun aGroupKeysLettersAreItsOwn() {
        val k18 = Forms.layout("k18").parts.filterIsInstance<Row>().flatMap { it.cells }.filterIsInstance<Key>()
        assertEquals(Letters(listOf("we")), k18.single { it.id == "r1c2" }.letters)
        assertEquals("l;", k18.single { it.id == "r2c6" }.letters?.group)
        assertNull(k18.single { it.id == "apostrophe" }.letters)
        assertEquals(listOf("we", "rt", "io", "sd", "fg", "jk", "l;", "xc", "bn"), k18.filter { it.leans }.map { it.letters!!.group })
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
        assertTrue(problems(board("<row><key id=\"g\" label=\"x\" letters=\"ab\" lean=\"no\"/></row>")).single().contains("lean="))
        assertTrue(problems(board("<row><key id=\"g\" label=\"x\" lean=\"off\"/></row>")).single().contains("lean="))
        assertTrue(problems(board("<row><key id=\"g\" label=\"x\" letters=\"a\"><variant when=\"ime=pinyin\" letters=\"b\"/></key></row>")).isNotEmpty())
        assertTrue(problems(board("<defaults letters=\"a\"/><row><key id=\"g\" label=\"x\"/></row>")).isNotEmpty())
    }

    // a board split in halves has no one side; asking which is refused until it means something
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
        val ok = board("<row><key id=\"a\" label=\"a\" when=\"pad=datetime\"/></row>\n<row><key id=\"a\" label=\"b\" when=\"pad=phone\"/></row>")
        assertEquals(emptyList(), problems(ok))
        val half = board("<row><key id=\"a\" label=\"a\" when=\"pad=datetime\"/></row>\n<row><key id=\"a\" label=\"b\"/></row>")
        assertTrue(problems(half).single().contains("used twice"))
        val rows = board("<row when=\"pad=datetime\"><key id=\"a\" label=\"a\"/></row>\n<row when=\"pad=phone\"><key id=\"a\" label=\"b\"/></row>")
        assertEquals(emptyList(), problems(rows))
    }

    @Test
    fun aLayoutOfItsOwnSaysWhoMadeItWhatItIsBasedOnAndItsPads() {
        val row = "<row><key id=\"a\" label=\"a\"/></row>"
        val meta = "<meta author=\"某人\" version=\"1.2\" license=\"CC-BY-4.0\" min-app=\"0.6.1\" description=\"H 和 JK 并成一个键\"/>"
        val l = LayoutReader.layout(board("$meta\n<defaults kind=\"letter\"/>\n$row", " based-on=\"symbols\"")).ok()
        assertEquals(LayoutMeta("某人", "1.2", "CC-BY-4.0", "0.6.1", "H 和 JK 并成一个键"), l.meta)
        assertEquals("symbols", l.basedOn)
        assertNull(l.pads)
        assertEquals(1, l.parts.size, "<meta> is no part")
        assertNull(Fixtures.qwerty.meta)
        assertNull(Fixtures.qwerty.basedOn)
        assertTrue(problems(board("$row\n$meta")).single().contains("<meta> must come first"))
        assertTrue(problems(board("$meta\n$meta\n$row")).single().contains("twice"))
        assertTrue(problems(board("<meta min-app=\"next\"/>\n$row")).single().contains("min-app"))
        assertTrue(problems(board("<meta author=\"${"x".repeat(65)}\"/>\n$row")).single().contains("author"))
        assertTrue(problems(board("<meta><x/></meta>\n$row")).single().contains("not allowed"))
        assertTrue(problems(board(row, " based-on=\"Qwerty\"")).single().contains("based-on"))
        val number = { pads: String -> board(row, " pads=\"$pads\"").replace("family=\"symbols\"", "family=\"number\"") }
        assertEquals(listOf("full", "phone"), LayoutReader.layout(number("full phone")).ok().pads)
        // the fields of the pads merged have the full one: a board for them is for it, with a warning
        val merged = LayoutReader.layout(number("full digits decimal phone"))
        assertEquals(listOf("full", "phone"), merged.ok().pads)
        assertEquals(listOf("digits", "decimal"), merged.warnings().map { Regex("\"(.*?)\"").find(it.message)!!.groupValues[1] })
        assertEquals(listOf("full"), LayoutReader.layout(number("signed signed-decimal")).ok().pads)
        // a key only for a pad merged shows nowhere now: told, still read
        val old = LayoutReader.layout(number("full").replace("<key ", "<key when=\"pad=full|digits, !pad=decimal\" "))
        assertEquals(listOf("pad=digits", "pad=decimal"), old.warnings().map { Regex("pad=[a-z-]+").find(it.message)!!.value })
        assertEquals(emptyList(), LayoutReader.layout(number("full").replace("<key ", "<key when=\"pad=full|phone\" ")).warnings())
        assertTrue(problems(number("full abacus")).single().contains("abacus"))
        assertTrue(problems(number("full full")).single().contains("twice"))
        assertTrue(problems(board(row, " pads=\"full\"")).single().contains("number board"))
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
        assertEquals(Width.Units(1.0), assertIs<Gap>(cells[1]).width, "a 0.6.0 gap's look and class read, drawn no more (NumberPadsTest)")
        assertEquals("function", assertIs<Slot>(cells[2]).look)
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\"><variant label=\"b\"/></key></row>")).single().contains("when"))
        assertTrue(problems(board("<row><gap width=\"1\"><key id=\"a\" label=\"a\"/></gap><key id=\"b\" label=\"b\"/></row>")).isNotEmpty())
    }

    @Test
    fun popupsAreChecked() {
        assertEquals(emptyList(), problems(board("<row><key id=\"a\" label=\"a\"><popup from=\"nine\"/></key></row>")))
        assertEquals(emptyList(), problems(board("<row><key id=\"a\" label=\"a\"><popup preset=\"q\" symbols-from=\"2\"/></key></row>")))
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\"><popup preset=\"a b\"/></key></row>")).isNotEmpty())
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\"><popup from=\"none\" preset=\"q\"/></key></row>")).isNotEmpty())
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\"><popup symbols-from=\"many\"/></key></row>")).isNotEmpty())
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\"><popup from=\"list\"/></key></row>")).isNotEmpty())
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\"><popup from=\"nine\"><item text=\"x\"/></popup></key></row>")).isNotEmpty())
    }

    @Test
    fun badBindingsAndNumbersAreProblems() {
        assertTrue(problems(board("<row><key id=\"a\" icon=\"x\" a11y=\"{nonsense}\"/></row>")).isNotEmpty())
        // a number the JVM and JS would read differently is not one
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\" width=\"1e1\"/></row>")).isNotEmpty())
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\" width=\"0x10\"/></row>")).isNotEmpty())
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\" width=\" 2\"/></row>")).isNotEmpty())
        assertEquals(emptyList(), problems(board("<row><key id=\"a\" label=\"a\" width=\".5\"/></row>")))
        assertTrue(problems(board("<row><key id=\"a\" label=\"a\" rows=\"+2\"/></row>")).isNotEmpty())
        assertEquals(Text.Binding("punct", "，"), LayoutReader.text("{punct:，}"))
        assertNull(LayoutReader.text("{punct: }"))
        assertEquals(emptyList(), problems(board("<row><key id=\"a\" label=\"{punct:,.?!}\"/></row>")))
        // basic plane alone, so the JVM and the theme maker's JS count a run alike
        assertTrue(problems(board("<row><key id=\"a\" label=\"{punct:\uD83D\uDE00}\"/></row>")).isNotEmpty())
        for (attr in listOf("alt", "a11y")) {
            assertTrue(problems(board("<row><key id=\"a\" label=\"a\" $attr=\"{punct:,.}\"/></row>")).isNotEmpty(), attr)
            assertEquals(emptyList(), problems(board("<row><key id=\"a\" label=\"a\" $attr=\"{punct:,}\"/></row>")), attr)
        }
    }

    // {group:} takes one or two letters; three or more are written out, letters= its letters (layout-edit.js groupLabel)
    @Test
    fun aGroupBindingIsOfOneOrTwoLettersAndLongerGroupsAreWrittenOut() {
        assertEquals(Text.Binding("group", "qw"), LayoutReader.text("{group:qw}"))
        assertEquals(Text.Binding("group", "l;"), LayoutReader.text("{group:l;}"))
        assertNull(LayoutReader.text("{group:hjk}"))
        assertTrue(problems(board("<row><key id=\"hjk\" label=\"{group:hjk}\" letters=\"hjk\"/></row>")).isNotEmpty())
        val l = LayoutReader.layout(board("<row><key id=\"hjk\" label=\"HJK\" letters=\"hjk\"/></row>")).ok()
        val hjk = l.parts.filterIsInstance<Row>().single().cells.filterIsInstance<Key>().single()
        assertEquals(Text.Literal("HJK"), hjk.attrs.label)
        assertEquals("hjk", hjk.letters?.group)
    }

    @Test
    fun aComposedKeyWrittenWithNoNameIsNamedAsTheAppsAre() {
        fun names(family: String, rows: String) = LayoutReader.layout("<keyboard xmlns=\"$ns\" id=\"t\" name=\"t\" family=\"$family\" columns=\"10\">$rows</keyboard>")
            .ok().parts.filterIsInstance<Row>().flatMap { r -> r.cells.filterIsInstance<Key>().map { it.attrs.shortcut } }
        val nine = "<row><key id=\"1\" label=\"1\"><popup from=\"nine\"/></key><key id=\"2\" label=\"ABC\" shortcut=\"t9/9\"><popup from=\"nine\"/></key>" +
            "<key id=\"x\" label=\"x\"><popup from=\"nine\"/></key><key id=\"3\" label=\"3\"/></row>"
        assertEquals(listOf("t9/1", "t9/9", null, null), names("nine", nine))
        assertEquals(listOf(null, "t9/9", null, null), names("symbols", nine))
        val groups = "<row><key id=\"a\" label=\"ㄅㄉㄚ\"><popup from=\"group\"/></key><key id=\"b\" label=\"ㄍ\" shortcut=\"zy9/7\"><popup from=\"group\"/></key>" +
            "<key id=\"c\" label=\"ㄓ\"><popup from=\"group\"/></key></row>"
        assertEquals(listOf("zy9/1", "zy9/7", "zy9/3"), names("zhuyin-nine", groups))
        // a name another key wrote is not given twice (two keys would share one list): left unnamed, warned
        val taken = "<row><key id=\"a\" label=\"ㄅㄉㄚ\" shortcut=\"zy9/2\"><popup from=\"group\"/></key><key id=\"b\" label=\"ㄍ\"><popup from=\"group\"/></key>" +
            "<key id=\"c\" label=\"ㄓ\"><popup from=\"group\"/></key></row>"
        assertEquals(listOf("zy9/2", null, "zy9/3"), names("zhuyin-nine", taken))
        val twice = "<row><key id=\"2\" label=\"ABC\" shortcut=\"t9/9\"><popup from=\"nine\"/></key><key id=\"9\" label=\"WXYZ\"><popup from=\"nine\"/></key></row>"
        assertEquals(listOf("t9/9", null), names("nine", twice))
    }

    private fun scrollBoard(cells: String, family: String = "number", row: String = "<row>") =
        "<keyboard xmlns=\"$ns\" id=\"test\" name=\"t\" family=\"$family\" columns=\"10\">\n$row$cells<key id=\"1\" label=\"1\"/></row>\n</keyboard>"

    // a layout names a scroll column's category and look; its items are the app's
    @Test
    fun aScrollSlotReadsItsListDirectionAndShow() {
        val l = LayoutReader.layout(scrollBoard("<slot id=\"s\" kind=\"scroll\" list=\"url\" direction=\"horizontal\" show=\"6\" width=\"3\"/>")).ok()
        val s = assertIs<Slot>(l.parts.filterIsInstance<Row>().single().cells[0])
        assertEquals("scroll", s.kind)
        assertEquals("url", s.list)
        assertEquals(Direction.Horizontal, s.direction)
        assertEquals(6, s.show)
        val d = assertIs<Slot>(LayoutReader.layout(scrollBoard("<slot kind=\"scroll\" list=\"nine\"/>")).ok().parts.filterIsInstance<Row>().single().cells[0])
        assertEquals(Direction.Vertical, d.direction)
        assertEquals(Slot.DEFAULT_SHOW, d.show)
        assertEquals("function" to Face.Keep, d.look to d.face, "drawn on the function keys' block when the layout says nothing")
        val own = assertIs<Slot>(LayoutReader.layout(scrollBoard("<slot kind=\"scroll\" list=\"nine\" look=\"letter\" face=\"default\"/>")).ok().parts.filterIsInstance<Row>().single().cells[0])
        assertEquals("letter" to Face.Default, own.look to own.face)
        assertRows(LayoutReader.SCROLL_FAMILIES.toList(), { "on $it" }) { assertEquals(emptyList(), problems(scrollBoard("<slot kind=\"scroll\" list=\"nine\"/>", it))) }
    }

    @Test
    fun aScrollSlotWrittenWrongDoesNotRead() {
        // each with what its problem says, so another problem of the board does not stand for it
        val wrong = mapOf(
            "no list" to (scrollBoard("<slot kind=\"scroll\"/>") to "list= is required"),
            "a category the app has not" to (scrollBoard("<slot kind=\"scroll\" list=\"symbols\"/>") to "list=\"symbols\" is not"),
            "a list on the nine keys' column" to (scrollBoard("<slot kind=\"nine-side\" list=\"nine\"/>", "nine") to "list= is a scroll slot's"),
            "a direction on another kind" to (scrollBoard("<slot kind=\"nine-side\" direction=\"vertical\"/>", "nine") to "direction= is a scroll slot's"),
            "show of 0" to (scrollBoard("<slot kind=\"scroll\" list=\"nine\" show=\"0\"/>") to "show=\"0\" is not"),
            "show of 9" to (scrollBoard("<slot kind=\"scroll\" list=\"nine\" show=\"9\"/>") to "show=\"9\" is not"),
            "a direction it has not" to (scrollBoard("<slot kind=\"scroll\" list=\"nine\" direction=\"diagonal\"/>") to "direction=\"diagonal\" is not"),
            "items" to (scrollBoard("<slot kind=\"scroll\" list=\"nine\"><item text=\"a\"/></slot>") to "<item> is not allowed in <slot>"),
            "three" to (scrollBoard("<slot kind=\"scroll\" list=\"nine\"/>".repeat(3)) to "more than 2 scroll slots"),
            "the symbol panel" to (scrollBoard("<slot kind=\"scroll\" list=\"nine\"/>", "symbols") to "not symbols"),
            "the emoji search" to (scrollBoard("<slot kind=\"scroll\" list=\"nine\"/>", "emoji-search") to "not emoji-search"),
            "a row split in halves" to (scrollBoard("<slot kind=\"scroll\" list=\"nine\"/>", "letters", "<row split=\"halves\">") to "split in halves"),
        )
        assertRows(wrong.entries.toList(), { it.key }) { (_, case) ->
            val (text, says) = case
            assertTrue(problems(text).any { says in it }, "${problems(text)}")
        }
        assertEquals(emptyList(), problems(scrollBoard("<slot kind=\"scroll\" list=\"nine\"/>", "letters", "<row split=\"halves\">").replace("scroll", "nine-side").replace(" list=\"nine\"", "")))
        assertEquals(emptyList(), problems(scrollBoard("<slot kind=\"scroll\" list=\"nine\"/>".repeat(2))), "two are allowed")
        val fragment = "<fragment xmlns=\"$ns\" id=\"f\"><row><slot kind=\"scroll\" list=\"nine\"/></row></fragment>"
        assertTrue(LayoutReader.fragment(fragment).problems().any { "not a fragment" in it.message }, "a fragment's board is not known")
    }
}
