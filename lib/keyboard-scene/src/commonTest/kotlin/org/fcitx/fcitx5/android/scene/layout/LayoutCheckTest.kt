/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Fixtures.ok
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
        assertEquals(emptyList(), errors(named("comma")), "a function key's name of the app's")
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
        // a base key typing other text, and a key of its own: each listed, the base's own left out
        val typed = qwerty.replace("send=\"commit:.com\"", "send=\"commit:.cn\"")
            .replace("</keyboard>", "<row><key id=\"haha\" label=\"哈\" send=\"commit:哈哈\" shortcut=\"my-qwerty/haha\"/><key id=\"slash2\" label=\"/\" send=\"commit:/\" shortcut=\"my-qwerty/slash2\"/></row>\n</keyboard>")
        val listed = check(typed)
        assertEquals(listOf(".cn", "哈哈", "/"), listed.commits)
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
