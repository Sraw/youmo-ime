/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.Forms
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.keys.DefaultMarks
import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant.Form
import org.fcitx.fcitx5.android.scene.testData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 主题只管外观 (061-custom-layout §19): what a theme's layout writes of a key held or swiped gives way to the app's key of
// its name; a user's layout keeps it for a name of its own
class KeyDataTest {
    private fun read(text: String) = LayoutReader.layout(text).ok()

    private fun keys(l: Layout, fragments: Map<String, Fragment> = emptyMap()): Map<String, Key> =
        (l.parts.filterIsInstance<Row>() + fragments.values.flatMap { it.rows }).flatMap { it.cells }.filterIsInstance<Key>().associateBy { it.id }

    private fun pack(id: String, text: String, fragments: Map<String, Fragment> = emptyMap()) = KeyData.of(id, read(text), fragments, KeyData.Mode.Pack)

    // the 18 keys' the example 薄荷 17 键 has: H and JK one key, every group key writing its corner as alt= and swipe-up=
    private val k17 by lazy { testData("mint-k17/layouts/k18.xml") }

    @Test
    fun aKeyOfTheAppsNameTakesTheAppsWhateverTheThemeWrites() {
        val q = Forms.layout("qwerty")
        val text = testData("qwerty.xml")
            .replace("send=\"e\"", "send=\"e\" alt=\"9\" swipe-up=\"commit:ee\" swipe-down=\"x\" long-press=\"y\" double-tap=\"z\"")
        val r = pack("qwerty", text)
        val e = keys(r.layout).getValue("e")
        assertNull(e.attrs.alt)
        assertEquals(mapOf("send" to "e"), e.attrs.actions)
        // each written, none taken: the notice's
        assertEquals(listOf("alt", "long-press", "swipe-up", "swipe-down", "double-tap"), r.ignored.map { it.what })
        assertTrue(r.ignored.all { it.file == "qwerty" && it.line == keys(q).getValue("e").line })
        // Shift's double tap the app's, whatever the theme leaves out
        val noLock = pack("qwerty", text.replace("double-tap=\"caps:lock\"", ""))
        assertEquals("caps:lock", keys(noLock.layout).getValue("shift").attrs.actions["double-tap"])
        assertTrue(noLock.ignored.none { it.what == "double-tap" && it.line == keys(q).getValue("shift").line })
    }

    @Test
    fun whatIsWrittenAsTheAppHasItIsNoNoticeAndTheTapAndLookStay() {
        val r = pack("qwerty", testData("qwerty.xml"))
        assertEquals(emptyList(), r.ignored)
        val q = keys(Forms.layout("qwerty"))
        for ((id, k) in keys(r.layout)) {
            assertEquals(q.getValue(id).attrs.actions, k.attrs.actions, id)
            assertEquals(q.getValue(id).popup, k.popup, id)
        }
    }

    @Test
    fun repeatAndSwipeAxisAreTheLayouts() {
        val text = testData("zhuyin.xml").replace("repeat=\"true\"", "repeat=\"false\"").replace("swipe-axis=\"both\"", "swipe-axis=\"vertical\"")
        val bs = keys(pack("zhuyin", text).layout).getValue("backspace")
        assertEquals("false", bs.attrs.actions["repeat"])
        assertEquals("vertical", bs.attrs.actions["swipe-axis"])
    }

    @Test
    fun defaultsWrittenReachNoKey() {
        val text = testData("zhuyin.xml").replace("<defaults kind=\"letter\"", "<defaults kind=\"letter\" swipe-up=\"commit:zz\" alt=\"Z\"")
        val r = pack("zhuyin", text)
        assertNull(r.layout.defaults.alt)
        assertTrue(r.layout.defaults.actions.isEmpty())
        val k = keys(r.layout).getValue("1")
        assertNull(k.attrs.over(r.layout.defaults).alt)
        assertNull(k.attrs.over(r.layout.defaults).actions["swipe-up"])
        // a commit no longer counted: the defaults' gone
        assertTrue(r.ignored.any { it.what == "swipe-up" })
    }

    @Test
    fun aThemesZhuyinKeysAreHeldAsTheAppsWhatItWrites() {
        // 注音 (no board 0.6.1 moves): a key of a name of the theme's own has no data, one of the app's the app's
        val text = testData("zhuyin.xml")
            .replace("shortcut=\"zyfull/1\"", "shortcut=\"zyfull/1\" swipe-up=\"commit:ㄅㄅ\" alt=\"!\"")
            .replace("shortcut=\"zyfull/2\"", "shortcut=\"mine/2\" swipe-up=\"commit:ㄉㄉ\"")
        val r = pack("zhuyin", text)
        val k = keys(r.layout)
        val app = keys(Forms.layout("zhuyin"))
        assertEquals(app.getValue("1").attrs.actions, k.getValue("1").attrs.actions)
        assertNull(k.getValue("2").attrs.actions["swipe-up"])
        assertNull(k.getValue("1").attrs.alt)
        // the long press of each as the app's: by the node, the layout's first gone
        val facts = DefaultMarks.nodes(r.layout, r.fragments).toMap()
        val appFacts = DefaultMarks.nodes(Forms.layout("zhuyin"), emptyMap()).toMap()
        val v = DefaultMarks.variantOf(r.layout)
        assertEquals(
            KeyDefaults.of(KeyId("zyfull/1"), v, appFacts.getValue("zyfull/1")),
            KeyDefaults.of(KeyId("zyfull/1"), v, facts.getValue("zyfull/1")),
        )
        assertNull(facts.getValue("zyfull/1").layoutFirst)
        assertEquals(3, r.ignored.size)
    }

    @Test
    fun aListedBoardsOwnNameKeepsWhatItWritesTheAppsTakeTheApps() {
        // the 26 keys 0.6.1 moves to the list: read as a user's layout already, a name of its own its data
        val text = testData("qwerty.xml")
            .replace("shortcut=\"full/e\"", "shortcut=\"mine/e\" swipe-up=\"commit:ee\" alt=\"E\"")
            .replace("send=\"r\"", "send=\"r\" swipe-up=\"commit:rr\"")
        val r = pack("qwerty", text)
        val k = keys(r.layout)
        assertEquals("commit:ee", k.getValue("e").attrs.actions["swipe-up"])
        assertEquals(Text.Literal("E"), k.getValue("e").attrs.alt)
        assertNull(k.getValue("r").attrs.actions["swipe-up"])
        assertEquals(listOf("swipe-up"), r.ignored.map { it.what })
    }

    @Test
    fun aKeyOfNoNameTakesTheAppsOfItsIdOnItsBoard() {
        // 网址的 .com: no name, the app's list of the key of its id
        val text = testData("qwerty.xml").replace("<item text=\".com\"/>", "<item text=\".io\"/>")
        assertNotEquals(testData("qwerty.xml"), text)
        val r = pack("qwerty", text)
        assertEquals(keys(Forms.layout("qwerty")).getValue("domain").popup, keys(r.layout).getValue("domain").popup)
        assertEquals(listOf("popup"), r.ignored.map { it.what })
        // one the app's board has not: none, the layout's own keeping it where it is a user's
        val extra = testData("zhuyin.xml").replace("<key id=\"1\"", "<key id=\"mine\" label=\"x\" send=\"x\" swipe-up=\"commit:xx\"/><key id=\"1\"")
        assertNull(keys(pack("zhuyin", extra).layout).getValue("mine").attrs.actions["swipe-up"])
        val own = KeyData.of("zhuyin", read(extra), emptyMap(), KeyData.Mode.Own)
        assertEquals("commit:xx", keys(own.layout).getValue("mine").attrs.actions["swipe-up"])
        assertTrue(own.ignored.isEmpty())
    }

    @Test
    fun theLookupOrderOnABoardThatReads() {
        // on its board, then its base (b), then the app's in order (qwerty before zhuyin); one of no name, its id's on the two
        val held = { s: String -> KeyData.Held(actions = mapOf("swipe-up" to s)) }
        val app = mapOf(
            "a" to KeyData.Board(true, mapOf("n/1" to held("a"))),
            "b" to KeyData.Board(false, mapOf("n/1" to held("b"), "n/2" to held("b")), unnamed = mapOf("kk" to held("b-k"))),
            "qwerty" to KeyData.Board(true, mapOf("n/1" to held("q"), "n/2" to held("q"), "n/3" to held("q"))),
            "zhuyin" to KeyData.Board(true, mapOf("n/3" to held("z"), "n/4" to held("z"))),
        )
        val text = testData("emoji-search.xml")
            .replace(Regex("<key id=\"q\"[^>]*><popup from=\"none\"/></key>")) { "<key id=\"k1\" label=\"1\" shortcut=\"n/1\"/><key id=\"k2\" label=\"2\" shortcut=\"n/2\"/><key id=\"k3\" label=\"3\" shortcut=\"n/3\"/><key id=\"k4\" label=\"4\" shortcut=\"n/4\"/><key id=\"kk\" label=\"k\" swipe-up=\"commit:mine\"/><key id=\"k5\" label=\"5\" shortcut=\"n/5\" swipe-up=\"commit:own\"/>" }
        val l = read(text)
        val own = keys(KeyData.of("x", l, emptyMap(), KeyData.Mode.Own, basedOn = "b", app = app).layout)
        assertEquals(listOf("b", "b", "q", "z", "b-k", "commit:own"), listOf("k1", "k2", "k3", "k4", "kk", "k5").map { own.getValue(it).attrs.actions["swipe-up"] })
        // the board's own first: a's n/1, not its base's
        val onA = keys(KeyData.of("a", l, emptyMap(), KeyData.Mode.Own, basedOn = "b", app = app).layout)
        assertEquals("a", onA.getValue("k1").attrs.actions["swipe-up"])
        // popups= the board's, else its base's
        assertEquals(false, KeyData.of("x", l, emptyMap(), KeyData.Mode.Own, basedOn = "b", app = app).layout.popups)
        // a theme's: a name the app has not, none
        assertNull(keys(KeyData.of("x", l, emptyMap(), KeyData.Mode.Pack, app = app).layout).getValue("k5").attrs.actions["swipe-up"])
    }

    @Test
    fun aVariantOfANameOfItsOwnLooksThatNameUpAnyOtherFollowsItsKey() {
        val text = testData("nine.xml")
            .replace("<variant when=\"composing\" label=\"分词\"/>", "<variant when=\"composing\" label=\"分词\" swipe-up=\"commit:v\"/>")
        val r = pack("nine", text)
        val v = keys(r.layout).values.flatMap { it.variants }
        assertTrue(v.isNotEmpty())
        assertTrue(v.none { it.attrs.actions.containsKey("swipe-up") })
        assertTrue(r.ignored.any { it.what == "swipe-up" })
    }

    @Test
    fun aNineKeyNamedByTheReaderTakesTheAppsOfThatName() {
        // a nine key of no shortcut= with <popup from="nine">: named t9/2 as it is read, before the app's is put in
        val text = testData("nine.xml").replace(" shortcut=\"t9/2\"", "")
        val r = pack("nine", text)
        val two = keys(r.layout).getValue("2")
        assertEquals("t9/2", two.attrs.shortcut)
        assertEquals(Popup("nine", null, emptyList(), emptyMap()), two.popup)
    }

    @Test
    fun theZhuyinNineGroupsLongPressFollowsTheLabelTheLayoutShows() {
        // the label is the layout's (Q10): a group of other zhuyin long-presses those it shows
        val text = testData("zhuyin-nine.xml").replace("label=\"ㄅㄉㄚ\"", "label=\"ㄆㄊㄛ\"")
        val r = pack("zhuyin-nine", text)
        val facts = DefaultMarks.nodes(r.layout, r.fragments).toMap().getValue("zy9/1")
        val list = KeyDefaults.of(KeyId("zy9/1"), DefaultMarks.variantOf(r.layout), facts)!!
        assertEquals(listOf("ㄆ", "ㄊ", "ㄛ"), list.row.map { it.text })
    }

    @Test
    fun aGroupKeysCornerIsTheAppsKeyOfItsNamesNotOfItsPlace() {
        // 薄荷 17 键: JK merged into H, so every key after it is one place earlier; the corners stay each name's
        val r = pack("k18", k17, Forms.fragments.filterKeys { it == "grouped-bottom" })
        val got = DefaultMarks.shortcuts(r.layout, r.fragments)
        val app = DefaultMarks.shortcuts(Forms.layout("k18"), Forms.fragments)
        val names = listOf("g18/h", "g18/l", "g18/z", "g18/xc", "g18/v", "g18/bn", "g18/m")
        for (n in names) assertEquals(app.getValue("$n:up"), got.getValue("$n:up"), n)
        assertEquals(MarkContent.Symbol("-"), got.getValue("g18/l:up"))
        // its alt= and swipe-up= are the corners the app draws there (0.6.0's k18 wrote them so): no notice; one other is
        assertEquals(emptyList(), r.ignored)
        val other = pack("k18", k17.replace("alt=\"{punct:-}\"", "alt=\"{punct:(}\""), Forms.fragments.filterKeys { it == "grouped-bottom" })
        assertEquals(listOf("alt"), other.ignored.map { it.what })
        // HJK's long press its own letters
        val hjk = DefaultMarks.nodes(r.layout, r.fragments).toMap().getValue("g18/h")
        val list = KeyDefaults.of(KeyId("g18/h"), DefaultMarks.variantOf(r.layout), hjk)!!
        assertEquals(listOf("H", "J", "K", "(", "h", "j", "k"), list.row.map { it.text })
    }

    @Test
    fun aGroupKeyOfANameOfItsOwnHasNoCornerOfTheApps() {
        val text = k17.replace("shortcut=\"g18/h\"", "shortcut=\"k17/hjk\"")
        val own = KeyData.of("x", read(text), Forms.fragments.filterKeys { it == "grouped-bottom" }, KeyData.Mode.Own, basedOn = "k18")
        val marks = DefaultMarks.shortcuts(own.layout, own.fragments, DefaultMarks.variantOf(own.layout, "k18"))
        // written: its own corner
        assertEquals(MarkContent.Symbol("("), marks.getValue("k17/hjk:up"))
        // not written: none
        val bare = KeyData.of("x", read(text.replace("alt=\"{punct:(}\" letters=\"hjk\" swipe-up=\"(\"", "letters=\"hjk\"")), Forms.fragments.filterKeys { it == "grouped-bottom" }, KeyData.Mode.Own, basedOn = "k18")
        assertEquals(-1, keys(bare.layout).getValue("r2c4").place)
        assertEquals(MarkContent.Empty, DefaultMarks.shortcuts(bare.layout, bare.fragments, DefaultMarks.variantOf(bare.layout, "k18")).getValue("k17/hjk:up"))
    }

    @Test
    fun the18KeysAreToldByTheBoardALayoutIsOf() {
        val l = read(k17.replace("id=\"k18\"", "id=\"t1a2b3c4d-k18\""))
        assertEquals(Form.Grouped14, DefaultMarks.variantOf(l).form)
        assertEquals(Form.Grouped18, DefaultMarks.variantOf(l, "k18").form)
        assertEquals(Form.Grouped14, DefaultMarks.variantOf(l, "qwerty").form)
        assertEquals(KeyboardVariant.Form.Grouped18, DefaultMarks.variantOf(Forms.layout("k18")).form)
    }

    @Test
    fun aKeyboardsPopupsIsTheApps() {
        val text = testData("zhuyin.xml").replace("family=\"zhuyin\"", "family=\"zhuyin\" popups=\"off\"")
        assertNotEquals(testData("zhuyin.xml"), text)
        val r = pack("zhuyin", text)
        assertTrue(r.layout.popups)
        assertEquals(listOf(KeyData.Ignored("zhuyin", 0, "popups")), r.ignored)
        // the 颜文字 board by its file's id, whose <keyboard id> is emoji's
        assertEquals(false, pack("emoticon", testData("emoticon.xml").replace("popups=\"off\"", "")).layout.popups)
    }

    @Test
    fun aKeyOfItsOwnNameKeepsWhatItsDefaultsGiveIt() {
        // the defaults are cleared, so what they give a key keeping its own is put on the key
        val text = testData("qwerty.xml")
            .replace("<defaults kind=\"letter\"", "<defaults kind=\"letter\" swipe-up=\"commit:zz\" alt=\"Z\"")
            .replace("shortcut=\"full/e\"", "shortcut=\"mine/e\"")
        assertNotEquals(testData("qwerty.xml"), text.replace("shortcut=\"mine/e\"", "shortcut=\"full/e\""))
        val r = pack("qwerty", text)
        val e = keys(r.layout).getValue("e")
        assertEquals("commit:zz", e.attrs.over(r.layout.defaults).actions["swipe-up"])
        assertEquals(Text.Literal("Z"), e.attrs.over(r.layout.defaults).alt)
        // one of the app's name: the app's, the defaults' not reaching it
        assertNull(keys(r.layout).getValue("r").attrs.over(r.layout.defaults).actions["swipe-up"])
    }

    // a user's layout (L6, §9, §19.7 step 3): a new name's long press is what the layout writes, its first the swipe-up
    // (else a literal alt=), its list the <popup>'s; a new group key of no corner written has its letters, no corner
    @Test
    fun aUsersNewNamesDefaultsAreWhatItsLayoutWrites() {
        val text = testData("qwerty.xml").replaceFirst("id=\"qwerty\"", "id=\"mine\" based-on=\"qwerty\"").replace(
            "</keyboard>",
            "<row><key id=\"ha\" label=\"哈\" send=\"commit:哈\" shortcut=\"mine/ha\" swipe-up=\"commit:哈哈\" swipe-down=\"commit:呵\">" +
                "<popup from=\"list\"><item text=\"嘿\"/><item text=\"嗨\"/></popup></key>" +
                "<key id=\"lit\" label=\"L\" send=\"commit:l\" shortcut=\"mine/lit\" alt=\"\$year\"/>" +
                "<key id=\"xy\" label=\"{group:xy}\" letters=\"xy\" shortcut=\"mine/xy\"/></row>\n</keyboard>",
        )
        val r = KeyData.of("mine", read(text), emptyMap(), KeyData.Mode.Own, basedOn = "qwerty")
        assertEquals(emptyList(), r.ignored, "a new name's own: nothing told")
        val variant = DefaultMarks.variantOf(r.layout, "qwerty")
        val nodes = DefaultMarks.nodes(r.layout, r.fragments).toMap()
        val ha = KeyDefaults.of(KeyId("mine/ha"), variant, nodes.getValue("mine/ha"))!!
        assertEquals("哈哈", ha.first?.text)
        assertEquals(listOf("哈哈", "嘿", "嗨"), ha.symbols.map { it.text }, "the first, then the <popup>'s")
        assertEquals("commit:呵", keys(r.layout).getValue("ha").attrs.over(r.layout.defaults).actions["swipe-down"])
        // literal text: no variable made of it
        assertEquals("\$year", KeyDefaults.of(KeyId("mine/lit"), variant, nodes.getValue("mine/lit"))!!.first?.text)
        val xy = KeyDefaults.of(KeyId("mine/xy"), variant, nodes.getValue("mine/xy"))!!
        assertEquals(listOf("X", "Y", "x", "y"), xy.row.map { it.text })
        assertNull(xy.first)
        // a key of the app's name on it keeps none of its own: the app's (full/q's 1)
        assertEquals("1", KeyDefaults.of(KeyId("full/q"), variant, nodes.getValue("full/q"))!!.first?.text)
    }

    @Test
    fun aVariantOfTheAppsNameOnAKeyOfItsOwnIsTheApps() {
        val text = testData("qwerty.xml").replace(
            "<key id=\"q\" label=\"{case:q}\" send=\"q\" shortcut=\"full/q\"><variant when=\"roots\" label=\"{root:q}\"/></key>",
            "<key id=\"q\" label=\"{case:q}\" send=\"q\" shortcut=\"mine/q\"><variant when=\"roots\" label=\"{root:q}\" shortcut=\"full/w\" swipe-up=\"commit:w\"/>" +
                "<variant when=\"ime=english\" label=\"Q\" shortcut=\"mine/qq\" swipe-up=\"commit:qq\"/><variant when=\"composing\" label=\"q\" swipe-up=\"commit:u\"/></key>",
        )
        assertNotEquals(testData("qwerty.xml"), text)
        val r = pack("qwerty", text)
        val v = keys(r.layout).getValue("q").variants
        assertEquals(listOf(null, "commit:qq", "commit:u"), v.map { it.attrs.actions["swipe-up"] })
        assertEquals(listOf("swipe-up"), r.ignored.map { it.what })
    }

    // every key with all it is laid on, by id
    private fun laid(l: Layout, fragments: Map<String, Fragment>): Map<String, Pair<Key, KeyAttrs>> =
        l.parts.flatMap { p ->
            when (p) {
                is Row -> listOf(p to l.defaults)
                is Include -> fragments.getValue(p.src).let { f -> f.rows.map { it to f.defaults.over(l.defaults) } }
            }
        }.flatMap { (row, under) -> row.cells.filterIsInstance<Key>().map { it.id to (it to it.attrs.over(row.defaults.over(under))) } }.toMap()

    // every app's board read as a theme's is itself: each key's data as it is (BuiltInKeyData of each, so a name
    // written twice on a board with two data fails here), nothing told
    @Test
    fun everyBoardOfTheAppsReadAsAThemesIsTheSame() {
        for (board in BuiltInKeyData.BOARDS.keys) {
            val l = read(testData("$board.xml"))
            val fragments = l.parts.filterIsInstance<Include>().associate { it.src to Forms.fragments.getValue(it.src) }
            val r = KeyData.of(board, l, fragments, KeyData.Mode.Pack)
            assertEquals(emptyList(), r.ignored, board)
            assertEquals(l.popups, r.layout.popups, board)
            val before = laid(l, fragments)
            val after = laid(r.layout, r.fragments)
            assertEquals(before.keys, after.keys, board)
            for ((id, b) in before) {
                val (k, attrs) = after.getValue(id)
                assertEquals(b.second, attrs, "$board $id")
                assertEquals(b.first.popup, k.popup, "$board $id")
                assertEquals(b.first.menu, k.menu, "$board $id")
                assertEquals(b.first.variants.map { it.attrs }, k.variants.map { it.attrs }, "$board $id")
            }
        }
    }

    @Test
    fun aLayoutMadeAgainKeepsAllButWhatItIsMadeWith() {
        val l = read(testData("number.xml"))
        val again = l.with(KeyAttrs(), emptyList(), popups = true)
        assertEquals(listOf(l.id, l.name, l.family, l.columns, l.onSplit, l.preview), listOf(again.id, again.name, again.family, again.columns, again.onSplit, again.preview))
        assertTrue(again.parts.isEmpty() && again.popups)
        // number.xml's preview="false" kept through KeyData
        assertEquals(l.preview, pack("number", testData("number.xml")).layout.preview)
    }

    @Test
    fun aLayoutOfItsOwnKeepsWhatItIsBasedOnItsMetaAndPadsThroughKeyData() {
        val text = testData("number.xml").replaceFirst(
            "<keyboard ",
            "<keyboard based-on=\"number\" pads=\"full phone\" ",
        ).replaceFirst(Regex("(<keyboard [^>]*>)"), "$1<meta author=\"me\" version=\"2\" min-app=\"0.6.1\"/>")
        val l = read(text)
        assertEquals(listOf("number", "me", "0.6.1"), listOf(l.basedOn, l.meta?.author, l.meta?.minApp))
        val r = KeyData.of("t1-number", l, emptyMap(), KeyData.Mode.Own)
        assertEquals(listOf(l.basedOn, l.meta, l.pads), listOf(r.layout.basedOn, r.layout.meta, r.layout.pads))
        assertEquals(listOf("full", "phone"), r.layout.pads)
    }

    @Test
    fun aGroupedLayoutOfItsOwnIsToldByWhatItIsBasedOn() {
        // a user's k18 under an id of its own: the 18 keys' by its based-on=, with no basedOn passed
        val l = read(testData("k18.xml").replaceFirst("<keyboard ", "<keyboard based-on=\"k18\" ").replaceFirst("id=\"k18\"", "id=\"t1-k18\""))
        assertEquals("t1-k18", l.id)
        assertEquals(Form.Grouped18, DefaultMarks.variantOf(l).form)
    }

    @Test
    fun aKeyOfItsOwnNameKeepsWhatAnIncludedFragmentsDefaultsGiveIt() {
        val bottom = testData("grouped-bottom.xml").replaceFirst(Regex("(<fragment [^>]*>)"), "$1<defaults swipe-up=\"commit:zz\"/>")
            .replace("shortcut=\"symbols\"", "shortcut=\"mine/sym\"")
        val f = LayoutReader.fragment(bottom).ok()
        assertEquals("commit:zz", f.defaults.actions["swipe-up"])
        val r = pack("k18", testData("k18.xml"), mapOf("grouped-bottom" to f))
        val g = r.fragments.getValue("grouped-bottom")
        val laid = g.rows.flatMap { it.cells }.filterIsInstance<Key>().associateBy { it.id }
        assertEquals("commit:zz", laid.getValue("symbols").attrs.over(g.defaults).over(r.layout.defaults).actions["swipe-up"])
        // one of the app's name: the app's, the fragment's defaults not reaching it
        assertNull(laid.getValue("numbers").attrs.over(g.defaults).over(r.layout.defaults).actions["swipe-up"])
    }

    @Test
    fun aGroupKeysCornerWrittenOnlyAsTheCornerIsNotTold() {
        // sym:1 is not the corner 1 written as typed: told
        val r = pack("k18", k17.replace("swipe-up=\"keypad:1\"", "swipe-up=\"sym:1\""), Forms.fragments.filterKeys { it == "grouped-bottom" })
        assertEquals(listOf("swipe-up"), r.ignored.map { it.what })
    }
}
