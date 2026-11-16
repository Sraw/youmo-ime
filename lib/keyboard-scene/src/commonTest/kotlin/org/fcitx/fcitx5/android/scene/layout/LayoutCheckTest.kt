/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.TEST_DATA
import org.fcitx.fcitx5.android.scene.readText
import org.fcitx.fcitx5.android.scene.recording
import org.fcitx.fcitx5.android.scene.testData
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// a layout of its own (061-custom-layout §3): one check for the app's import and the layout maker's page, run alike on
// the JVM and JS (the reports' snapshot below)
class LayoutCheckTest {
    private val boards = listOf(
        "emoji-search", "emoji", "emoticon", "expanded-side", "k14", "k18", "nine", "number-row", "number",
        "qwerty", "symbols", "zhuyin-nine", "zhuyin",
    )
    private val sources = LayoutCheck.Sources(boards.associateWith { testData("$it.xml") }, mapOf("grouped-bottom" to testData("grouped-bottom.xml")))

    // the app's board [id] made a layout of one's own: another id, based on it
    private fun own(id: String) = testData("$id.xml").replaceFirst("id=\"$id\"", "id=\"my-$id\" based-on=\"$id\"")

    private val qwerty = own("qwerty")

    private fun check(text: String, strict: Boolean = true, app: String = "0.6.1") = LayoutCheck.check(text, sources, app, strict = strict)

    private fun errors(text: String) = check(text).errors.map { it.key }

    private fun warnings(text: String) = check(text).warnings.map { it.key }

    // [text] with [key]'s element (`<key id="key" …/>`, one line or more, no children) replaced by [with]
    private fun replaceKey(text: String, key: String, with: String) =
        text.replaceFirst(Regex("<key id=\"${Regex.escape(key)}\"[^>]*?/>"), with)

    @Test
    fun theAppsBoardsMadeOnesOwnPass() {
        val expected = mapOf(
            "qwerty" to listOf("pinyin", "shuangpin", "english", "table"),
            "k14" to listOf("pinyin", "shuangpin"),
            "k18" to listOf("pinyin", "shuangpin"),
            "nine" to listOf("pinyin", "shuangpin", "english"),
            "number" to emptyList(),
            "symbols" to emptyList(),
        )
        for ((id, ims) in expected) {
            val r = check(own(id))
            assertEquals(emptyList(), r.errors, id)
            assertEquals(ims, r.ims, id)
            assertEquals("0.6.1", r.minApp, "$id: based-on= is 0.6.1's")
        }
        assertEquals(listOf("full"), check(own("number")).pads, "a number board with no pads= is for 123's")
        // every pad: the app's number board has each pad's keys
        val every = own("number").replaceFirst("family=\"number\"", "family=\"number\" pads=\"${LayoutReader.PADS.joinToString(" ")}\"")
        val r = check(every)
        assertEquals(emptyList(), r.errors)
        assertEquals(LayoutReader.PADS, r.pads)
        assertTrue(r.warnings.none { it.key == "pad-lacks" }, r.warnings.toString())
        // the commits a board types its base does not are listed (the import's confirmation): none, qwerty's / @ . .com its base's
        assertEquals(emptyList(), check(qwerty).commits)
        assertTrue(check(qwerty).warnings.none { it.key == "commits" })
    }

    // a file saved with a byte-order mark (Windows' Notepad) reads as the app reads it
    @Test
    fun aByteOrderMarkIsTakenAsTheAppTakesIt() {
        val r = check("\uFEFF" + qwerty)
        assertEquals(emptyList(), r.errors)
        assertEquals(check(qwerty).warnings, r.warnings)
    }

    // what its rows' <defaults> give every key of the row is held as a key's own would be
    @Test
    fun aRowsDefaultsActionsAreHeldAsAKeysOwn() {
        val long = "x".repeat(LayoutCheck.MAX_COMMIT + 1)
        val row = qwerty.replaceFirst("<row split=\"halves\">", "<row split=\"halves\">\n    <defaults long-press=\"commit:$long\" swipe-up=\"layout:Emoji\"/>")
        val r = check(row)
        assertTrue(r.errors.any { it.key == "commit-long" && it.line > 0 }, r.errors.toString())
        assertTrue(r.errors.any { it.key == "layout-target" && it.line > 0 }, r.errors.toString())
        val listed = qwerty.replaceFirst("<row split=\"halves\">", "<row split=\"halves\">\n    <defaults long-press=\"commit:hi\"/>")
        assertEquals(listOf("hi"), check(listed).commits)
    }

    @Test
    fun whatDoesNotReadIsSaidWithItsLine() {
        val r = check(qwerty.replace("<row split=\"halves\">", "<row split=\"halves\" bogus=\"1\">"))
        assertEquals(listOf("read"), r.errors.map { it.key }.distinct())
        assertTrue(r.errors.all { it.line > 0 })
    }

    @Test
    fun oneForANewerAppIsRefused() {
        val meta = qwerty.replaceFirst("<defaults", "<meta min-app=\"9.0.0\"/>\n  <defaults")
        assertEquals(listOf(LayoutCheck.Finding(0, "newer", listOf("9.0.0"))), check(meta).errors)
        assertEquals(emptyList(), check(meta, app = "9.0.0").errors)
    }

    @Test
    fun itsIdNameFamilyAndBaseAreItsOwn() {
        assertEquals(listOf("id-built-in", "based-on"), errors(testData("qwerty.xml")), "the app's board as it is")
        // the rest still looked at, and a number board's pads said as anywhere else
        val bare = testData("number.xml")
        assertEquals(listOf("id-built-in", "based-on", "lacks"), errors(replaceKey(bare, "back", "<gap width=\"14\"/>")).distinct())
        assertEquals(listOf("full"), check(bare).pads)
        assertEquals(listOf("id-length"), errors(qwerty.replace("id=\"my-qwerty\"", "id=\"${"a".repeat(33)}\"")))
        assertEquals(listOf("id-prefix"), errors(qwerty.replace("id=\"my-qwerty\"", "id=\"g18\"")))
        assertEquals(listOf("name-length"), errors(qwerty.replace("name=\"26 键\"", "name=\"${"名".repeat(25)}\"")))
        // a direction override, a zero-width space, a line break written as a reference: in the name or the <meta>
        for (c in listOf("&#x202E;", "&#x200B;", "&#10;", "&#x2028;")) {
            assertEquals(listOf(LayoutCheck.Finding(0, "hidden-chars", listOf("name"))), check(qwerty.replace("name=\"26 键\"", "name=\"26${c}键\"")).errors, c)
        }
        val meta = qwerty.replaceFirst("<defaults", "<meta author=\"a&#x200F;b\" description=\"ok\"/>\n  <defaults")
        assertEquals(listOf(LayoutCheck.Finding(0, "hidden-chars", listOf("author"))), check(meta).errors)
        assertEquals(listOf("hidden-chars"), check(meta, strict = false).warnings.map { it.key }.filter { it == "hidden-chars" })
        assertEquals("ab", LayoutCheck.plain("a‮​b\n"))
        assertEquals(listOf("based-on"), errors(qwerty.replace("based-on=\"qwerty\"", "based-on=\"zhuyin\"")))
        assertEquals(listOf("based-on"), errors(qwerty.replace(" based-on=\"qwerty\"", "")))
        // a family no layout of one's own is: zhuyin
        val zhuyin = errors(own("zhuyin").replace("based-on=\"zhuyin\"", "based-on=\"qwerty\""))
        assertTrue("family" in zhuyin && "base" !in zhuyin, zhuyin.toString())
    }

    @Test
    fun itsSizeIsWithinTheLimits() {
        assertEquals(listOf("too-big"), errors(qwerty + "<!--" + "x".repeat(PackLayouts.MAX_BYTES.toInt()) + "-->"))
        // measured before it is read, and refused even where only 0.6.0's rules hold (the app reads no more: PackLayouts.read)
        val huge = "<" + "x".repeat(PackLayouts.MAX_BYTES.toInt())
        assertEquals(listOf("too-big"), check(huge).errors.map { it.key })
        assertEquals(listOf("too-big"), check(qwerty + "<!--" + "x".repeat(PackLayouts.MAX_BYTES.toInt()) + "-->", strict = false).errors.map { it.key })
        assertEquals(listOf("label-long"), errors(qwerty.replace("label=\"拼\"", "label=\"${"拼".repeat(17)}\"")).distinct())
        val long = "x".repeat(LayoutCheck.MAX_COMMIT + 1)
        assertEquals(listOf("commit-long"), errors(qwerty.replace("send=\"commit:/\"", "send=\"commit:$long\"")))
        val items = (1..LayoutCheck.MAX_COMMITS + 1).joinToString("") { "<item text=\"$it\" send=\"commit:$it\"/>" }
        val popup = qwerty.replace("<popup from=\"none\"/>\n    </key>\n    <key id=\"at\"", "<popup from=\"list\">$items</popup>\n    </key>\n    <key id=\"at\"")
        assertEquals(listOf("too-many-commits"), errors(popup))
        val keys = (1..LayoutCheck.MAX_KEYS).joinToString("") { "<key id=\"x$it\" label=\"x\" send=\"x\" shortcut=\"my-qwerty/x$it\" width=\"0.06\"/>" }
        assertEquals(listOf("too-many-keys"), errors(qwerty.replace("</keyboard>", "<row>$keys</row>\n</keyboard>")), "nothing more looked through")
    }

    @Test
    fun itIncludesOnlyTheAppsFragmentsAndGoesOnlyToTheBoardsOfText() {
        assertEquals(listOf("include"), errors(own("k18").replace("src=\"grouped-bottom\"", "src=\"my-bottom\"")))
        assertEquals(listOf("layout-target"), errors(qwerty.replace("send=\"layout:Number\"", "send=\"layout:Emoji\"")))
    }

    @Test
    fun itKeepsWhatItsBaseMustBeTypedOn() {
        val r = check(replaceKey(qwerty, "backspace", ""))
        assertEquals(listOf(LayoutCheck.Finding(0, "base", listOf("it has no key [backspace]"))), r.errors)
        // made grouped of the 26 keys: held to the grouped family's base, 18 keys (its slots, its roles)
        val nine = own("nine")
        assertEquals(listOf("base"), errors(nine.replace(Regex("<slot [^>]*kind=\"nine-side\"[^>]*/>"), "<gap width=\"16\"/>")).take(1))
    }

    @Test
    fun aNameOfItsOwnHasItsBoardBeforeIt() {
        val named = { name: String -> qwerty.replace("shortcut=\"full/q\"", "shortcut=\"$name\"") }
        assertEquals(emptyList(), errors(named("my-qwerty/q")))
        assertEquals(listOf("command"), errors(named("comma")), "a letter taking a function key's name of the app's (L3 review M3)")
        // a function key taking one it is no copy of (L3 fix's second re-check: a name is its base key's or a true copy's)
        assertEquals(listOf("command"), errors(qwerty.replace("shortcut=\"lang\"", "shortcut=\"comma\"")), "a function key taking one")
        assertEquals(listOf("name-no-slash"), errors(named("mine")))
        assertEquals(listOf("name-prefix"), errors(named("g18/mine")))
        // a function key's prefix written: read as a bare name (fn/ is how one is kept), so said as one
        assertEquals(listOf("name-no-slash"), errors(named("fn/mine")))
    }

    @Test
    fun itHasTheKeysItsFamilyNeeds() {
        assertEquals(listOf("lacks"), errors(replaceKey(qwerty, "shift", "")))
        val k18 = own("k18")
        assertEquals(listOf("groups"), errors(k18.replaceFirst("letters=\"we\"", "letters=\"w\"")))
        val symbols = own("symbols")
        assertEquals(listOf(LayoutCheck.Finding(0, "lacks", listOf("panel-back"))), check(symbols.replace("send=\"panel-back\"", "send=\"layout:Text\"")).errors)
        val number = own("number")
        assertEquals(listOf(LayoutCheck.Finding(0, "lacks", listOf("pad=full: back"))), check(replaceKey(number, "back", "<gap width=\"14\"/>")).errors)
        val decimal = number.replaceFirst("family=\"number\"", "family=\"number\" pads=\"decimal\"")
        assertEquals(emptyList(), check(decimal).errors)
        val noDot = Regex("<key id=\"\\.\"[^>]*/>").replace(decimal, "<gap width=\"10\"/>")
        assertEquals(listOf(LayoutCheck.Finding(0, "lacks", listOf("pad=decimal: ."))), check(noDot).errors)
        // its digits held by what they type, not by the app's ids (a board of one's own names its keys as it likes)
        val renamed = (0..9).fold(number) { t, d -> t.replaceFirst("<key id=\"$d\"", "<key id=\"d$d\"") }
        assertEquals(emptyList(), check(renamed).errors)
        assertEquals(listOf("base"), check(renamed, strict = false).errors.map { it.key }, "a theme's board of the list: held as 0.6.0 held it")
        // a digit gone is still found, by what the pad needs
        assertEquals(listOf("lacks"), errors(Regex("<key id=\"d5\"[^>]*/>").replace(renamed, "<gap width=\"22\"/>")))
        // a phone's + * # only told
        val phone = Regex("<key id=\"#\"[^>]*/>").replace(number.replaceFirst("family=\"number\"", "family=\"number\" pads=\"phone\""), "<gap width=\"16\"/>")
        val r = check(phone)
        assertEquals(emptyList(), r.errors)
        assertTrue(LayoutCheck.Finding(0, "pad-lacks", listOf("phone", "#")) in r.warnings, r.warnings.toString())
    }

    @Test
    fun everySceneItIsShownInIsMadeWithoutAProblem() {
        // a row wider than the board where the field is a URL (the address keys shown): found in that scene
        val wide = qwerty.replace("width=\"1.4\" label=\".com\"", "width=\"5\" label=\".com\"")
        val r = check(wide)
        assertEquals(listOf("scene"), r.errors.map { it.key }.distinct())
        assertTrue(r.errors.first().args[0].contains("field=url"), r.errors.toString())
        // a key across rows in the middle of its row (Y, 5–6 of the first row's 10 units): the row under it laid out past it,
        // A–G before it and H–L after (the board not split, the second row's ; and * gone: 9 units, as it leaves them)
        val plain = replaceKey(replaceKey(qwerty, "semicolon", ""), "star", "").replace(" on-split=\"split\"", "")
        val tall = plain.replace("<key id=\"y\" label", "<key id=\"y\" rows=\"2\" label")
        assertEquals(emptyList(), check(tall).errors, "a key two rows tall in the middle of its row")
        // A half as wide, the row centred: H (4.75–5.75) reaches into Y's room (5–6)
        val found = check(tall.replace("<key id=\"a\" label", "<key id=\"a\" width=\"0.5\" label")).errors
        assertEquals(listOf("scene"), found.map { it.key }.distinct())
        assertTrue(found.all { "reaches into" in it.args[1] }, found.toString())
        // the row under it too wide for what it leaves: said as such
        val over = check(tall.replace("<key id=\"g\" label", "<key id=\"g\" width=\"1.5\" label")).errors
        assertTrue(over.isNotEmpty() && over.all { "over the 9 it has" in it.args[1] }, over.toString())
        // a key with neither words nor a picture
        assertEquals(listOf("blank"), errors(qwerty.replace("label=\"拼\" text-size=\"18\" text-style=\"bold\"\n         send=\"code:U+F0104\" when=\"table !looking-up\"", "send=\"code:U+F0104\" when=\"table !looking-up\"")))
    }

    @Test
    fun whatIsOnlyToldIsWarned() {
        assertTrue("no-way" in warnings(replaceKey(replaceKey(qwerty, "symbols", ""), "numbers", "")))
        // each way missing told: the number pad's alone, the symbols' alone; none to itself
        assertEquals(listOf(listOf("Number")), check(replaceKey(qwerty, "numbers", "")).warnings.filter { it.key == "no-way" }.map { it.args })
        assertEquals(listOf(listOf("Symbol")), check(replaceKey(qwerty, "symbols", "")).warnings.filter { it.key == "no-way" }.map { it.args })
        assertTrue(check(own("number")).warnings.none { it.key == "no-way" })
        assertTrue(check(own("symbols")).warnings.none { it.key == "no-way" })
        // a digit written as its code point types that digit: no pad lacks it
        val coded = own("number").replace("send=\"sym:KP_1\"", "send=\"code:U+0031\"")
        assertTrue(check(coded).warnings.none { it.key == "pad-lacks" }, check(coded).warnings.toString())
        assertTrue("narrow" in warnings(qwerty.replace("<key id=\"comma\" role=\"comma\" kind=\"function\"", "<key id=\"comma\" role=\"comma\" kind=\"function\" width=\"0.4\"")))
        // a base key typing other text, and a key of its own: each listed, the base's own left out (its long press's .cn too)
        val typed = qwerty.replace("send=\"commit:.com\"", "send=\"commit:.io\"")
            .replace("</keyboard>", "<row><key id=\"haha\" label=\"哈\" send=\"commit:哈哈\" shortcut=\"my-qwerty/haha\"/><key id=\"slash2\" label=\"/\" send=\"commit:/\" shortcut=\"my-qwerty/slash2\"/></row>\n</keyboard>")
        val listed = check(typed)
        assertEquals(listOf(".io", "哈哈", "/"), listed.commits)
        assertEquals(LayoutCheck.Finding(0, "commits", listOf("3")), listed.warnings.single { it.key == "commits" })
        assertEquals(listOf("q"), check(qwerty.replace(" shortcut=\"full/q\"", "")).warnings.filter { it.key == "unnamed" }.map { it.args[0] })
        // the number pad's + - * / have no name on the app's board either
        assertTrue(check(own("number")).warnings.none { it.key == "unnamed" })
        val gap = qwerty.replace("<key id=\"lookup\"", "<gap width=\"0\" look=\"function\"/><key id=\"lookup\"")
        assertEquals(1, check(gap).warnings.count { it.key == "gap-look" && it.line > 0 })
        val swapped = own("number").replace("<key id=\"symbols\" role=\"symbols\"", "<key id=\"symbols\" role=\"lock\"")
        assertTrue("bottom-left" in warnings(swapped))
        // a theme that hides Return's picture: told, not refused (another theme draws it)
        val theme = ThemeReader.theme(testData("material-light.xml").replace("</theme>", "<style select=\"*/enter\" hidden=\"true\"/>\n</theme>")).ok()
        val r = LayoutCheck.check(qwerty, sources, "0.6.1", theme)
        assertEquals(emptyList(), r.errors)
        assertTrue(r.warnings.any { it.key == "theme-blank" && it.args[0].startsWith("my-qwerty/enter") }, r.warnings.toString())
    }

    @Test
    fun aThemesBoardMadeOneOfTheListIsHeldOnlyToWhat060Refused() {
        // its id the app's, no base, a gap of 0.6.0's look: read, kept what it must, so only told
        val pack = testData("qwerty.xml").replace("<key id=\"lookup\"", "<gap width=\"0\" look=\"function\"/><key id=\"lookup\"")
        val r = check(pack, strict = false)
        assertEquals(emptyList(), r.errors)
        assertEquals(listOf("id-built-in", "based-on"), r.warnings.map { it.key }.filter { it == "id-built-in" || it == "based-on" })
        assertTrue(r.warnings.none { it.key == "gap-look" })
        // what 0.6.0 refused still is
        assertEquals(listOf("base"), check(replaceKey(pack, "backspace", ""), strict = false).errors.map { it.key })
        val number = own("number").replace("<key id=\"symbols\" role=\"symbols\"", "<key id=\"symbols\" role=\"lock\"")
        assertTrue(check(number, strict = false).warnings.none { it.key == "bottom-left" })
        // 0.6.1's rules where strict refuses: each only told here, the scenes' problems among them
        val wide = qwerty.replace("width=\"1.4\" label=\".com\"", "width=\"5\" label=\".com\"")
        val cases = mapOf(
            "scene" to wide,
            "layout-target" to qwerty.replace("send=\"layout:Number\"", "send=\"layout:Emoji\""),
            "name-no-slash" to qwerty.replace("shortcut=\"full/q\"", "shortcut=\"mine\""),
            "label-long" to qwerty.replace("label=\"拼\"", "label=\"${"拼".repeat(17)}\""),
        )
        for ((key, text) in cases) {
            assertTrue(key in errors(text), key)
            val r = check(text, strict = false)
            assertEquals(emptyList(), r.errors, key)
            assertTrue(r.warnings.any { it.key == key }, "$key: ${r.warnings}")
        }
    }

    @Test
    fun theFeaturesTableIsTheReaders() {
        val samples = mapOf(
            "meta" to qwerty.replaceFirst("<defaults", "<meta author=\"me\"/>\n  <defaults"),
            "based-on" to qwerty,
            "pads" to own("number").replaceFirst("family=\"number\"", "family=\"number\" pads=\"full\""),
            "rows-middle" to qwerty.replace("<key id=\"g\" label", "<key id=\"g\" rows=\"2\" label"),
            // a key id twice in rows each shown on its own (0.6.0 refused the second)
            // a gap's id too: the reader holds every cell's
            "row-when gap" to qwerty.replace("</keyboard>", "<row when=\"field=url\"><gap id=\"g1\" width=\"1\"/><key id=\"zz\" label=\"x\"/></row>\n<row when=\"!field=url\"><gap id=\"g1\" width=\"1\"/></row>\n</keyboard>"),
            "row-when" to qwerty.replace("</keyboard>", "<row when=\"field=url\"><key id=\"zz\" label=\"x\"/></row>\n<row when=\"!field=url\"><key id=\"zz\" label=\"y\"/></row>\n</keyboard>"),
        )
        assertEquals(LayoutCheck.FEATURES.keys, samples.keys.map { it.substringBefore(' ') }.toSet())
        for ((sample, text) in samples) {
            val feature = sample.substringBefore(' ')
            val layout = LayoutReader.layout(text).ok()
            assertTrue(feature in LayoutCheck.features(layout), feature)
            assertEquals(LayoutCheck.FEATURES.getValue(feature), LayoutCheck.minApp(layout), feature)
        }
        // the app's own boards use none: 0.6.0 reads them
        for (id in boards) assertEquals(LayoutCheck.BASE_VERSION, LayoutCheck.minApp(LayoutReader.layout(testData("$id.xml")).ok()), id)
        // a key across rows first or last in its row is 0.6.0's
        val nine = LayoutReader.layout(testData("nine.xml")).ok()
        assertTrue("rows-middle" !in LayoutCheck.features(nine))
    }

    @Test
    fun theScenesAreTheScreensOfTheWideAsLandscapeRule() {
        val scenes = LayoutCheck.scenes(LayoutReader.layout(qwerty).ok(), listOf("pinyin"), emptyList())
        // 600 dp upright is 横 (061-wide-as-landscape), 412 dp upright is not
        assertTrue(scenes.first { it.name == "600dp" }.env.landscape)
        assertTrue(!scenes.first { it.name == "412dp" }.env.landscape)
        assertTrue(scenes.any { it.name == "800dp turned split" })
        // turned, four rows of 48 dp; a large screen's 56; the lowest the app lets the keys get, four of 36 (061-landscape-height)
        assertEquals(4 * 48 * 2.625, scenes.first { it.name == "800dp turned" }.env.height, 1e-9)
        assertEquals(4 * 56 * 2.625, scenes.first { it.name == "600dp" }.env.height, 1e-9)
        assertEquals(4 * 36 * 2.625, scenes.first { it.name == "800dp turned 144dp" }.env.height, 1e-9)
    }

    // L2 security review S4: what a key types is seen, a letter's swipe does not clear what is being written
    @Test
    fun whatAKeyTypesIsSeenAndALetterRunsNoCommand() {
        // a direction override, a mark, a control: typed by commit:, by one character, by code:
        for (send in listOf("commit:a&#x202E;b", "commit:&#x200F;", "commit:a&#9;b", "&#x2066;", "code:U+061C", "code:U+E0041")) {
            val text = qwerty.replace("send=\"commit:/\"", "send=\"$send\"")
            assertEquals(listOf("hidden-chars"), errors(text), send)
            assertEquals(listOf("hidden-chars"), check(text, strict = false).warnings.map { it.key }.filter { it == "hidden-chars" }, send)
        }
        assertEquals(emptyList(), errors(qwerty.replace("send=\"commit:/\"", "send=\"commit:é😀\"")), "seen characters, one past the BMP among them")
        // a command on a letter or the space its base's key has not
        val clear = qwerty.replace("<key id=\"q\" label=\"{case:q}\" send=\"q\"", "<key id=\"q\" label=\"{case:q}\" send=\"q\" swipe-down=\"shortcut:clear\"")
        assertEquals(listOf(LayoutCheck.Finding(17, "command", listOf("q", "shortcut:clear"))), check(clear).errors)
        assertEquals(listOf("command"), check(clear, strict = false).warnings.map { it.key }.filter { it == "command" })
        val picker = qwerty.replace("<key id=\"a\" label=\"{case:a}\" send=\"a\"", "<key id=\"a\" label=\"{case:a}\" send=\"ime-picker\" kind=\"letter\"")
        assertEquals(listOf("command"), errors(picker))
        // a key of its own that is no letter may: one seen for what it is
        val own = qwerty.replace("</keyboard>", "<row><key id=\"paste\" kind=\"function\" label=\"贴\" send=\"shortcut:paste\" shortcut=\"my-qwerty/paste\"/></row>\n</keyboard>")
        assertEquals(emptyList(), errors(own).filter { it == "command" })
    }

    // L3 review: a long press's list item types its text; a letter is what types one, whatever the file calls it; a symbol
    // other than the space's and a function key's name are commands too
    @Test
    fun aListsTextsAndALettersNamesAndSymbolsAreHeldAsWhatTheyDo() {
        fun loaded(text: String) = LayoutCheck.loaded((LayoutReader.layout(text) as Parsed.Ok).value, sources, "0.6.1").map { it.key }
        fun withRow(keys: String) = qwerty.replace("</keyboard>", "<row>$keys</row>\n</keyboard>")
        fun listed(vararg items: String) = withRow(
            "<key id=\"it\" kind=\"function\" label=\"x\" send=\"commit:xy\" shortcut=\"my-qwerty/it\"><popup from=\"list\">" +
                items.joinToString("") { "<item text=\"$it\"/>" } + "</popup></key>",
        )
        // a list item's text: typed as a commit: is
        for (hidden in listOf("a&#x202E;b", "&#x200B;", "a&#10;b")) {
            assertEquals(listOf("hidden-chars"), errors(listed(hidden)).filter { it == "hidden-chars" }, hidden)
            assertEquals(listOf("hidden-chars"), loaded(listed(hidden)).filter { it == "hidden-chars" }, hidden)
        }
        assertEquals(listOf("commit-long"), errors(listed("x".repeat(LayoutCheck.MAX_COMMIT + 1))).filter { it == "commit-long" })
        assertEquals(listOf("too-many-commits"), loaded(listed(*Array(LayoutCheck.MAX_COMMITS + 1) { "w$it" })).filter { it == "too-many-commits" })
        assertTrue("ok" in check(listed("ok")).commits)
        // a letter named as the app's 删除 key: its swipes (清空) and what is set on it
        val named = qwerty.replace("send=\"e\" shortcut=\"full/e\"", "send=\"e\" shortcut=\"backspace\"")
        assertEquals(listOf(LayoutCheck.Finding(19, "command", listOf("e", "shortcut=fn/backspace"))), check(named).errors)
        // the base's 分词 added again under an id of its own: its copy, doing nothing it does not
        val again = qwerty.replace("</keyboard>", "<row><key id=\"apostrophe-2\" role=\"apostrophe\" kind=\"function\" label=\"分词\" send=\"'\" shortcut=\"separator\"/></row>\n</keyboard>")
        assertEquals(emptyList(), check(again).errors.filter { it.key == "command" })
        assertEquals(listOf("command"), loaded(named))
        assertEquals(listOf("command"), errors(qwerty.replace("send=\"e\" shortcut=\"full/e\"", "send=\"e\" shortcut=\"space\"")).filter { it == "command" })
        // what its tap types says it is a letter, whatever role and kind it is given
        for (send in listOf("commit:e", "code:U+0065", "e")) {
            val key = "<key id=\"ee\" role=\"comma\" kind=\"function\" label=\"e\" send=\"$send\" swipe-up=\"shortcut:clear\" shortcut=\"my-qwerty/ee\"/>"
            assertEquals(listOf("command"), errors(withRow(key)).filter { it == "command" }, send)
            assertEquals(listOf("command"), loaded(withRow(key)).filter { it == "command" }, send)
        }
        // the base's letter by its id, whatever it types now
        assertEquals(listOf("command"), errors(qwerty.replace("send=\"e\" shortcut=\"full/e\"", "send=\"commit:hello\" kind=\"function\" role=\"comma\" swipe-up=\"shortcut:clear\" shortcut=\"full/e\"")).filter { it == "command" })
        // Return sends, BackSpace and Escape take away: commands on a letter; the space's and a keypad's character are typed
        for (sym in listOf("sym:Return", "sym:BackSpace", "sym:Escape", "sym:KP_Enter")) {
            assertEquals(listOf("command"), loaded(qwerty.replace("send=\"q\" shortcut=\"full/q\"", "send=\"q\" swipe-down=\"$sym\" shortcut=\"full/q\"")), sym)
        }
        assertEquals(emptyList(), loaded(qwerty.replace("send=\"q\" shortcut=\"full/q\"", "send=\"q\" swipe-down=\"sym:KP_1\" shortcut=\"full/q\"")))
        // a list item's send on a letter
        val item = qwerty.replace(
            "send=\"q\" shortcut=\"full/q\"><variant when=\"roots\" label=\"{root:q}\"/></key>",
            "send=\"q\" shortcut=\"full/q\"><variant when=\"roots\" label=\"{root:q}\"/><popup from=\"list\"><item text=\"x\" send=\"shortcut:clear\"/></popup></key>",
        )
        assertEquals(listOf("command"), loaded(item))
    }

    // the L3 fix re-checks: every state of a key judged, an allow-list. A key any state of which types holds no command
    // its base's key lacks; a name is the base's key's of its id, or the key is a true copy of one taking it
    @Test
    fun everyStateOfAKeyIsJudgedAndOnlyATrueCopyBorrowsAName() {
        fun loaded(text: String) = when (val p = LayoutReader.layout(text)) {
            is Parsed.Ok -> LayoutCheck.loaded(p.value, sources, "0.6.1").map { it.key }
            else -> listOf("unread $p")
        }
        fun withRow(keys: String) = qwerty.replace("</keyboard>", "<row>$keys</row>\n</keyboard>")
        fun commands(text: String) = errors(text).filter { it == "command" }.distinct() to loaded(text).filter { it == "command" }.distinct()
        val held = listOf("command") to listOf("command")
        val free = emptyList<String>() to emptyList<String>()
        fun refused(key: String) = assertEquals(held, commands(withRow(key)), key)
        // a key that reads, with no error at all
        fun passes(key: String) {
            assertEquals(emptyList(), errors(withRow(key)), key)
            assertEquals(emptyList(), loaded(withRow(key)), key)
        }
        val e = "<key id=\"e\" label=\"{case:e}\" send=\"e\" shortcut=\"full/e\">"
        // the first re-check's M-1: no send, named 删除 or 回车 (its name's preset would clear and send)
        for (name in listOf("backspace", "return")) {
            assertEquals(held, commands(qwerty.replace(e, "<key id=\"e\" label=\"{case:e}\" shortcut=\"$name\">")), name)
        }
        assertEquals(held, commands(qwerty.replace(e, "<key id=\"e\" label=\"{case:e}\" letters=\"e\" shortcut=\"backspace\">")))
        refused("<key id=\"bs\" kind=\"letter\" label=\"x\" shortcut=\"backspace\"/>")
        assertEquals(held, commands(qwerty.replace("send=\"sym:space\" swipe-axis=\"both\" shortcut=\"space\"", "swipe-axis=\"both\" shortcut=\"return\"")))
        refused("<key id=\"bs\" label=\"e\" shortcut=\"backspace\"/>")
        // S-1: several characters typed: held as a letter
        for (send in listOf("commit:ee", "commit:hello world")) {
            for (swipe in listOf("shortcut:clear", "sym:Return")) refused("<key id=\"w2\" kind=\"function\" label=\"ee\" send=\"$send\" swipe-up=\"$swipe\" shortcut=\"my-qwerty/w2\"/>")
        }
        // M-A: a variant that types, or shows a letter with no send of its own
        refused("<key id=\"fx\" kind=\"function\" label=\"x\" send=\"lang\" shortcut=\"backspace\"><variant when=\"!composing\" label=\"e\" send=\"e\"/></key>")
        for (send in listOf("e", "commit:hello")) {
            refused("<key id=\"fx\" kind=\"function\" label=\"x\" send=\"layout:Number\" swipe-up=\"shortcut:clear\" shortcut=\"my-qwerty/fx\"><variant when=\"!composing\" label=\"e\" send=\"$send\"/></key>")
        }
        refused("<key id=\"fx\" kind=\"function\" icon=\"backspace\" a11y=\"a11y_key_backspace\" shortcut=\"backspace\"><variant when=\"composing\" label=\"e\"/></key>")
        refused("<key id=\"rx\" kind=\"function\" icon=\"{return-icon}\" a11y=\"{return}\" send=\"sym:Return\" shortcut=\"return\"><variant when=\"composing\" label=\"e\" send=\"e\"/></key>")
        // M-B: a space by its send
        for (name in listOf("backspace", "return")) refused("<key id=\"sx\" kind=\"function\" label=\"x\" send=\"sym:space\" shortcut=\"$name\"/>")
        for (send in listOf("sym:space", "code:U+0020", "commit:　")) {
            refused("<key id=\"sx\" kind=\"function\" label=\"x\" send=\"$send\" swipe-up=\"shortcut:clear\" shortcut=\"my-qwerty/sx\"/>")
        }
        // M-C: a label of text, a send typing none: no name to borrow
        for (send in listOf("lang", "caps", "picker:emoji", "sym:e")) refused("<key id=\"cx\" kind=\"function\" label=\"e\" send=\"$send\" shortcut=\"backspace\"/>")
        refused("<key id=\"cx\" label=\"{case:e}\" send=\"layout:Number\" shortcut=\"backspace\"/>")
        // S-A: digits and tones type
        for (send in listOf("sym:5", "sym:KP_5", "keypad:5", "action:tone-1")) {
            refused("<key id=\"dx\" kind=\"function\" label=\"5\" send=\"$send\" swipe-up=\"shortcut:clear\" shortcut=\"my-qwerty/dx\"/>")
        }
        refused("<key id=\"dx\" kind=\"function\" label=\"5\" send=\"keypad:5\" shortcut=\"backspace\"/>")
        // what passes: the base's 分词 and 空格 again, a true 删除 copy labelled as one, function keys typing nothing
        passes("<key id=\"apostrophe-2\" role=\"apostrophe\" kind=\"function\" label=\"分词\" send=\"'\" shortcut=\"separator\"/>")
        passes("<key id=\"space-2\" role=\"space\" kind=\"space\" label=\"x\" send=\"sym:space\" shortcut=\"space\"/>")
        refused("<key id=\"space-2\" role=\"space\" kind=\"space\" label=\"x\" send=\"sym:space\" shortcut=\"return\"/>")
        for (label in listOf("⌫", "删", "DEL")) passes("<key id=\"bs\" kind=\"function\" label=\"$label\" send=\"sym:BackSpace\" shortcut=\"backspace\"/>")
        refused("<key id=\"bs\" kind=\"function\" label=\"e\" send=\"sym:BackSpace\" shortcut=\"backspace\"/>")
        for (send in listOf("layout:Number", "ime-picker")) {
            passes("<key id=\"go\" kind=\"function\" label=\"符\" send=\"$send\" swipe-up=\"shortcut:clear\" shortcut=\"my-qwerty/go\"/>")
        }
        // a grouped board keeping the full keyboard's ⇧ (the layout maker's merge): a true copy of a key of the app's
        val grouped = qwerty.replace("family=\"letters\"", "family=\"grouped\"").replace("based-on=\"qwerty\"", "based-on=\"k18\"")
        assertEquals(emptyList(), check(grouped).errors.filter { it.key == "command" && it.args[0] == "shift" })
        assertEquals(listOf("shift"), check(grouped.replace("shortcut=\"shift\"", "shortcut=\"backspace\"")).errors.filter { it.key == "command" }.map { it.args[0] })
        // every board of the app's, as its own base, passes (emoticon.xml's id is emoji's: no copy of its own made by own())
        for (id in boards - "emoticon") assertEquals(emptyList(), check(own(id)).errors.filter { it.key == "command" }, id)
    }

    // the third re-check: a letter shown is a letter (SHOULD-2); a base function key's id made a letter takes not its
    // name (SHOULD-1); the fourth: a key showing a letter is no true copy of one whose tap is a command (MUST-1)
    @Test
    fun aLetterShownIsALetterAndABaseFunctionsIdTypingLosesItsName() {
        fun loaded(text: String) = when (val p = LayoutReader.layout(text)) {
            is Parsed.Ok -> LayoutCheck.loaded(p.value, sources, "0.6.1").map { it.key }
            else -> listOf("unread $p")
        }
        fun commands(text: String) = errors(text).filter { it == "command" }.distinct() to loaded(text).filter { it == "command" }.distinct()
        val held = listOf("command") to listOf("command")
        fun withRow(keys: String) = qwerty.replace("</keyboard>", "<row>$keys</row>\n</keyboard>")
        fun refused(key: String) = assertEquals(held, commands(withRow(key)), key)
        // with no send of its own, or one typing none
        refused("<key id=\"w2\" label=\"e\" swipe-up=\"shortcut:clear\" shortcut=\"my-qwerty/w2\"/>")
        refused("<key id=\"w2\" label=\"{case:e}\" send=\"caps\" swipe-up=\"shortcut:clear\" shortcut=\"my-qwerty/w2\"/>")
        // a base function key's id made a letter takes not its name, a true copy elsewhere or not (SHOULD-1)
        val copy = "<key id=\"bs2\" kind=\"function\" label=\"⌫\" send=\"sym:BackSpace\" shortcut=\"backspace\"/>"
        for ((id, name) in listOf("backspace" to "backspace", "enter" to "return")) {
            val made = Regex("<key id=\"$id\"[^>]*?/?>").replace(qwerty) { "<key id=\"$id\" label=\"e\" send=\"e\" shortcut=\"$name\"" + if (it.value.endsWith("/>")) "/>" else ">" }
            assertEquals(held, commands(made), id)
            assertEquals(held, commands(made.replace("</keyboard>", "<row>$copy</row>\n</keyboard>")), "$id with a copy")
        }
        // a 删除 copy labelled e looks like e: no copy (its name's preset would clear)
        for (label in listOf("e", "{case:e}")) refused("<key id=\"bs\" kind=\"function\" label=\"$label\" send=\"sym:BackSpace\" shortcut=\"backspace\"/>")
        refused("<key id=\"bs\" kind=\"function\" label=\"e\" send=\"sym:BackSpace\" swipe-up=\"shortcut:clear\" shortcut=\"backspace\"/>")
    }

    // the fourth re-check: a 回车, 切换, 重输 shown as a letter is no copy (MUST-1); a letter is what the eye takes for
    // one: a {punct:} drawn as is, a letter spaced, folded or of another script (SHOULD-1, -2), a few together (SHOULD-3)
    @Test
    fun aCommandShowingALetterIsNoCopyAndALetterIsWhatTheEyeTakesForOne() {
        fun loaded(text: String) = when (val p = LayoutReader.layout(text)) {
            is Parsed.Ok -> LayoutCheck.loaded(p.value, sources, "0.6.1").map { it.key }
            else -> listOf("unread $p")
        }
        fun commands(text: String) = errors(text).filter { it == "command" }.distinct() to loaded(text).filter { it == "command" }.distinct()
        val held = listOf("command") to listOf("command")
        fun withRow(board: String, keys: String) = board.replace("</keyboard>", "<row>$keys</row>\n</keyboard>")
        val nine = own("nine")
        fun refused(key: String, board: String = qwerty) = assertEquals(held, commands(withRow(board, key)), key)
        fun passes(key: String, board: String = qwerty) {
            assertEquals(emptyList(), errors(withRow(board, key)), key)
            assertEquals(emptyList(), loaded(withRow(board, key)), key)
        }
        for (label in listOf("e", "{case:e}", "{group:we}")) refused("<key id=\"r2\" kind=\"function\" label=\"$label\" send=\"sym:Return\" shortcut=\"return\"/>")
        val enter = "width=\"1.5\" icon=\"{return-icon}\" a11y=\"{return}\""
        assertEquals(1, Regex(Regex.escape(enter)).findAll(qwerty).count())
        assertEquals(held, commands(qwerty.replace(enter, "width=\"1.5\" label=\"e\"")), "enter labelled e")
        refused("<key id=\"l2\" kind=\"function\" label=\"e\" send=\"lang\" shortcut=\"lang\"/>")
        refused("<key id=\"r2\" kind=\"function\" label=\"e\" send=\"sym:Escape\" shortcut=\"retype\"/>", nine)
        assertEquals(held, commands(nine.replace("label=\"重输\"", "label=\"e\"")), "retype labelled e")
        // a letter as the eye takes it, each with a swipe clearing
        fun swiping(label: String, board: String) =
            "<key id=\"x2\" kind=\"function\" label=\"$label\" send=\"layout:Number\" swipe-up=\"shortcut:clear\" shortcut=\"my-$board/x2\"/>"
        for (label in listOf("{punct:e}", "{punct:5}", " e", "e\u200B", "ｅ", "\u0435", "é", "e\u0301", "ab", "E;")) refused(swiping(label, "qwerty"))
        refused(swiping("abc", "nine"), nine)
        refused(swiping("qw", "k18"), own("k18"))
        for (label in listOf("符", "123", "拼", "中", "Emoji", ".com", "{punct:,}", "ㄅ", "あ", "한")) passes(swiping(label, "qwerty"))
    }

    // the fifth re-check: a base function key shown as a letter keeps none of its commands, its name dropped or not
    // (MUST-1); what draws nothing next to a letter is not seen (SHOULD-1); boxed capitals and regional indicators
    @Test
    fun aBaseFunctionKeyShownAsALetterKeepsNoCommandAndWhatDrawsNothingIsNotSeen() {
        fun loaded(text: String) = when (val p = LayoutReader.layout(text)) {
            is Parsed.Ok -> LayoutCheck.loaded(p.value, sources, "0.6.1").map { it.key }
            else -> listOf("unread $p")
        }
        fun commands(text: String) = errors(text).filter { it == "command" }.distinct() to loaded(text).filter { it == "command" }.distinct()
        val held = listOf("command") to listOf("command")
        // [board]'s first key [id] labelled e, its icon, a11y and name dropped
        fun shownAsE(board: String, id: String): String {
            val key = Regex("<key id=\"$id\"[^>]*>").find(board)!!.value
            val made = key.replace(Regex(" (icon|a11y|label|shortcut)=\"[^\"]*\""), "").replace("<key id=\"$id\"", "<key id=\"$id\" label=\"e\"")
            return board.replaceFirst(key, made)
        }
        for (id in listOf("enter", "backspace", "lang")) assertEquals(held, commands(shownAsE(qwerty, id)), id)
        assertEquals(held, commands(shownAsE(own("nine"), "reset")), "reset")
        // e with what draws nothing beside it, on a 删除 or 回车 copy
        fun withRow(keys: String) = qwerty.replace("</keyboard>", "<row>$keys</row>\n</keyboard>")
        val unseen = listOf("\uDB40\uDC20", "\uDB40\uDC01", "\uDB40\uDD00", "\u3164", "\u115F", "\u1160", "\uFFA0", "\u2800", "\u00AD", "\uFE0F")
        for (pad in unseen) {
            for ((send, name) in listOf("sym:BackSpace" to "backspace", "sym:Return" to "return")) {
                val key = "<key id=\"c2\" kind=\"function\" label=\"e$pad\" send=\"$send\" shortcut=\"$name\"/>"
                assertEquals(held, commands(withRow(key)), "$name ${pad.map { it.code.toString(16) }}")
            }
        }
        // the boxed and circled capitals and the regional indicators are letters
        for (label in listOf("\uD83C\uDD30", "\uD83C\uDD50", "\uD83C\uDD70", "\uD83C\uDDEA")) {
            val key = "<key id=\"x2\" kind=\"function\" label=\"$label\" send=\"layout:Number\" swipe-up=\"shortcut:clear\" shortcut=\"my-qwerty/x2\"/>"
            assertEquals(held, commands(withRow(key)), label)
        }
    }

    // the sixth re-check: a mark beyond the BMP over a letter, and the null notehead, are not seen (SHOULD-1); the
    // parenthesized and squared capitals are letters (SHOULD-2)
    @Test
    fun aMarkBeyondTheBmpIsNotSeenAndEveryEnclosedCapitalIsALetter() {
        fun loaded(text: String) = when (val p = LayoutReader.layout(text)) {
            is Parsed.Ok -> LayoutCheck.loaded(p.value, sources, "0.6.1").map { it.key }
            else -> listOf("unread $p")
        }
        fun commands(text: String) = errors(text).filter { it == "command" }.distinct() to loaded(text).filter { it == "command" }.distinct()
        val held = listOf("command") to listOf("command")
        fun withRow(keys: String) = qwerty.replace("</keyboard>", "<row>$keys</row>\n</keyboard>")
        fun text(cp: Int) = if (cp < 0x10000) cp.toChar().toString() else charArrayOf(
            (((cp - 0x10000) shr 10) + 0xD800).toChar(), (((cp - 0x10000) and 0x3FF) + 0xDC00).toChar(),
        ).concatToString()
        fun swiping(label: String) =
            "<key id=\"x2\" kind=\"function\" label=\"$label\" send=\"layout:Number\" swipe-up=\"shortcut:clear\" shortcut=\"my-qwerty/x2\"/>"
        for (pad in listOf(0x1D165, 0x1D167, 0x1D16E, 0x101FD, 0x1E944, 0x1D159)) {
            val label = "e" + text(pad)
            for ((send, name) in listOf("sym:BackSpace" to "backspace", "sym:Return" to "return")) {
                assertEquals(held, commands(withRow("<key id=\"c2\" kind=\"function\" label=\"$label\" send=\"$send\" shortcut=\"$name\"/>")), "$name ${pad.toString(16)}")
            }
            assertEquals(held, commands(withRow(swiping(label))), "swipe ${pad.toString(16)}")
        }
        for (cp in listOf(0x1F110, 0x1F129, 0x1F12B, 0x1F12E)) assertEquals(held, commands(withRow(swiping(text(cp)))), cp.toString(16))
    }

    // the file drawn is not trusted: what needs no scene asked again as it is read to be drawn (L3)
    @Test
    fun aLayoutReadToBeDrawnIsHeldToWhatNeedsNoScene() {
        fun loaded(text: String) = LayoutCheck.loaded((LayoutReader.layout(text) as Parsed.Ok).value, sources, "0.6.1").map { it.key }
        for (id in listOf("qwerty", "k14", "k18", "nine", "number", "symbols")) assertEquals(emptyList(), loaded(own(id)), id)
        assertEquals(listOf("base"), loaded(qwerty.replace("role=\"backspace\" ", "")).filter { it == "base" })
        assertEquals(listOf("name-no-slash"), loaded(qwerty.replace("shortcut=\"full/q\"", "shortcut=\"mine\"")))
        assertEquals(listOf("hidden-chars"), loaded(qwerty.replace("send=\"commit:/\"", "send=\"commit:&#x202E;\"")))
        assertEquals(listOf("layout-target"), loaded(qwerty.replace("send=\"commit:/\"", "send=\"layout:Emoji\"")))
        assertEquals(listOf("id-built-in"), loaded(qwerty.replace("id=\"my-qwerty\"", "id=\"k18\"")))
        // the name shown plain wherever shown: not what stops it
        assertEquals(emptyList(), loaded(qwerty.replace("name=\"26 键\"", "name=\"26&#x202E;键\"")))
        val meta = qwerty.replaceFirst("<defaults", "<meta min-app=\"9.0.0\"/>\n  <defaults")
        assertEquals(listOf("newer"), loaded(meta))
    }

    // the reports of a batch of layouts, held as text: the same on the JVM and JS (the app's import and the page agree)
    @Test
    fun theReportsAreTheSnapshots() {
        val cases = listOf(
            "qwerty" to qwerty, "k14" to own("k14"), "k18" to own("k18"), "nine" to own("nine"), "number" to own("number"),
            "symbols" to own("symbols"), "k17" to testData("k17.xml").replaceFirst("id=\"k17\"", "id=\"my-k17\" based-on=\"k18\"")
                .replace("</keyboard>", "  <include src=\"grouped-bottom\"/>\n</keyboard>"),
            "the-app-qwerty" to testData("qwerty.xml"),
            "pack-qwerty" to testData("qwerty.xml"),
        )
        val text = cases.joinToString("\n") { (name, t) ->
            val r = check(t, strict = name != "pack-qwerty")
            "# $name\nfamily ${r.family} ims ${r.ims} pads ${r.pads} min-app ${r.minApp} commits ${r.commits}\n" +
                r.errors.joinToString("") { "E $it\n" } + r.warnings.joinToString("") { "W $it\n" }
        }
        assertTrue(cases.flatMap { (_, t) -> check(t).errors + check(t).warnings }.all { it.key in LayoutCheck.KEYS })
        val path = "$TEST_DATA/layout-check/reports.txt"
        val committed = readText(path)
        when {
            recording() -> writeText(path, text)
            committed == null -> error("no $path: run the JVM tests with -Psnapshots.record=true")
            else -> assertEquals(committed, text, "$path differs: review, then record anew")
        }
    }
}
