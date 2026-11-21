/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.TEST_DATA
import org.fcitx.fcitx5.android.scene.Uat
import org.fcitx.fcitx5.android.scene.readText
import org.fcitx.fcitx5.android.scene.recording
import org.fcitx.fcitx5.android.scene.skin.assertRows
import org.fcitx.fcitx5.android.scene.testData
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// one check for the app's import and the layout maker's page, run alike on the JVM and JS (the reports' snapshot below)
class LayoutCheckTest {
    private val boards = listOf(
        "emoji-search", "emoji", "emoticon", "expanded-side", "k14", "k18", "nine", "number-row", "number",
        "qwerty", "symbols", "zhuyin-nine", "zhuyin",
    )
    private val sources = LayoutCheck.Sources(boards.associateWith { testData("$it.xml") }, mapOf("grouped-bottom" to testData("grouped-bottom.xml")))

    private fun own(id: String) = testData("$id.xml").replaceFirst("id=\"$id\"", "id=\"my-$id\" based-on=\"$id\"")

    private val qwerty = own("qwerty")

    // as 0.6.2 shipped it, before the lookup key's switch: what each feature alone asks
    private val qwerty062 = qwerty.replace(", table !table-lookup", "").replace(" table-lookup", "")

    private fun check(text: String, strict: Boolean = true, app: String = "0.6.1") = LayoutCheck.check(text, sources, app, strict = strict)

    private fun errors(text: String) = check(text).errors.map { it.key }

    private fun warnings(text: String) = check(text).warnings.map { it.key }

    private fun replaceKey(text: String, key: String, with: String) =
        text.replaceFirst(Regex("<key id=\"${Regex.escape(key)}\"[^>]*?/>"), with)

    private fun loaded(text: String) = when (val p = LayoutReader.layout(text)) {
        is Parsed.Ok -> LayoutCheck.loaded(p.value, sources, "0.6.1").map { it.key }
        else -> listOf("unread $p")
    }

    private fun withRow(keys: String, board: String = qwerty) = board.replace("</keyboard>", "<row>$keys</row>\n</keyboard>")

    private fun commands(text: String) = errors(text).filter { it == "command" }.distinct() to loaded(text).filter { it == "command" }.distinct()

    private val commandBoth = listOf("command") to listOf("command")

    private fun refused(key: String, board: String = qwerty) = assertEquals(commandBoth, commands(withRow(key, board)), key)

    private fun passes(key: String, board: String = qwerty) {
        assertEquals(emptyList(), errors(withRow(key, board)), key)
        assertEquals(emptyList(), loaded(withRow(key, board)), key)
    }

    private fun swiping(label: String, board: String = "qwerty") =
        "<key id=\"x2\" kind=\"function\" label=\"$label\" send=\"layout:Number\" swipe-up=\"shortcut:clear\" shortcut=\"my-$board/x2\"/>"

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
            assertEquals(if (id == "qwerty") "0.6.3" else "0.6.1", r.minApp, "$id: based-on= is 0.6.1's, the lookup key's switch 0.6.3's")
        }
        assertEquals(listOf("full"), check(own("number")).pads, "a number board with no pads= is for 123's")
        val every = own("number").replaceFirst("family=\"number\"", "family=\"number\" pads=\"${LayoutReader.PADS.joinToString(" ")}\"")
        val r = check(every)
        assertEquals(emptyList(), r.errors)
        assertEquals(LayoutReader.PADS, r.pads)
        assertTrue(r.warnings.none { it.key == "pad-lacks" }, r.warnings.toString())
        // commits its base does not type are listed: none, qwerty's / @ . .com are its base's
        assertEquals(emptyList(), check(qwerty).commits)
        assertTrue(check(qwerty).warnings.none { it.key == "commits" })
    }

    @Test
    fun theEighteenKeysRearrangedAreShuangpinsAndAGroupTwiceIsNot() {
        val k18 = own("k18")
        val q = Regex("<key [^>]*letters=\"q\"[^>]*/>").find(k18)!!.value
        val we = Regex("<key [^>]*letters=\"we\"[^>]*/>").find(k18)!!.value
        val swapped = k18.replace(q, "@@").replace(we, q).replace("@@", we)
        assertEquals(emptyList(), check(swapped).errors)
        assertEquals(listOf("pinyin", "shuangpin"), check(swapped).ims)
        val twice = k18.replace(q, q + "<key id=\"r1c0\" label=\"{group:q}\" letters=\"q\"/>")
        assertEquals(listOf("pinyin"), check(twice).ims)
    }

    // a grouped board's groups in an included fragment count too (SceneGroups.of)
    @Test
    fun theGroupsOfAnIncludedFragmentAreTheBoardsToo() {
        val k18 = own("k18")
        val top = Regex("<row>[\\s\\S]*?</row>").find(k18)!!.value
        assertTrue("letters=\"we\"" in top)
        val withTop = LayoutCheck.Sources(
            sources.layouts,
            sources.fragments + ("k18-top" to "<fragment xmlns=\"https://youmo-ime/layout/1\" id=\"k18-top\">\n$top\n</fragment>"),
        )
        val r = LayoutCheck.check(k18.replace(top, "<include src=\"k18-top\"/>"), withTop, "0.6.1")
        assertTrue(r.errors.none { it.key == "groups" }, r.errors.toString())
        assertEquals(listOf("pinyin", "shuangpin"), r.ims)
    }

    // a file saved with a byte-order mark (Windows' Notepad) reads as the app reads it
    @Test
    fun aByteOrderMarkIsTakenAsTheAppTakesIt() {
        val r = check("\uFEFF" + qwerty)
        assertEquals(emptyList(), r.errors)
        assertEquals(check(qwerty).warnings, r.warnings)
    }

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
        val bare = testData("number.xml")
        assertEquals(listOf("id-built-in", "based-on"), errors(replaceKey(bare, "back", "<gap width=\"14\"/>")).distinct())
        assertEquals(listOf("full"), check(bare).pads)
        assertEquals(listOf("id-length"), errors(qwerty.replace("id=\"my-qwerty\"", "id=\"${"a".repeat(33)}\"")))
        assertEquals(listOf("id-prefix"), errors(qwerty.replace("id=\"my-qwerty\"", "id=\"g18\"")))
        assertEquals(listOf("name-length"), errors(qwerty.replace("name=\"26 键\"", "name=\"${"名".repeat(25)}\"")))
        for (c in listOf("&#x202E;", "&#x200B;", "&#10;", "&#x2028;")) {
            assertEquals(listOf(LayoutCheck.Finding(0, "hidden-chars", listOf("name"))), check(qwerty.replace("name=\"26 键\"", "name=\"26${c}键\"")).errors, c)
        }
        val meta = qwerty.replaceFirst("<defaults", "<meta author=\"a&#x200F;b\" description=\"ok\"/>\n  <defaults")
        assertEquals(listOf(LayoutCheck.Finding(0, "hidden-chars", listOf("author"))), check(meta).errors)
        assertEquals(listOf("hidden-chars"), check(meta, strict = false).warnings.map { it.key }.filter { it == "hidden-chars" })
        assertEquals("ab", LayoutCheck.plain("a‮​b\n"))
        assertEquals(listOf("based-on"), errors(qwerty.replace("based-on=\"qwerty\"", "based-on=\"zhuyin\"")))
        assertEquals(listOf("based-on"), errors(qwerty.replace(" based-on=\"qwerty\"", "")))
        val zhuyin = errors(own("zhuyin").replace("based-on=\"zhuyin\"", "based-on=\"qwerty\""))
        assertTrue("family" in zhuyin && "base" !in zhuyin, zhuyin.toString())
    }

    @Test
    fun itsSizeIsWithinTheLimits() {
        assertEquals(listOf("too-big"), errors(qwerty + "<!--" + "x".repeat(PackLayouts.MAX_BYTES.toInt()) + "-->"))
        // measured before it is read, and refused even under the loose rules (PackLayouts.read)
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
    fun aNameOfItsOwnHasItsBoardBeforeIt() {
        val named = { name: String -> qwerty.replace("shortcut=\"full/q\"", "shortcut=\"$name\"") }
        assertEquals(emptyList(), errors(named("my-qwerty/q")))
        assertEquals(listOf("command"), errors(named("comma")), "a letter taking a function key's name of the app's (L3 review M3)")
        assertEquals(listOf("command"), errors(qwerty.replace("shortcut=\"lang\"", "shortcut=\"comma\"")), "a function key taking one")
        assertEquals(listOf("name-no-slash"), errors(named("mine")))
        assertEquals(listOf("name-prefix"), errors(named("g18/mine")))
        // fn/ is how a bare name is kept, so said as one
        assertEquals(listOf("name-no-slash"), errors(named("fn/mine")))
        assertEquals(listOf("name-no-slash"), errors(qwerty.replaceFirst("<defaults", "<defaults shortcut=\"mine\"")))
        assertEquals(listOf("name-prefix"), errors(qwerty.replaceFirst(Regex("<row([^>]*)>"), "<row$1><defaults shortcut=\"g18/mine\"/>")))
        assertEquals(emptyList(), errors(qwerty.replaceFirst("<defaults", "<defaults shortcut=\"my-qwerty/all\"")))
        val loose = check(qwerty.replaceFirst("<defaults", "<defaults shortcut=\"mine\""), strict = false)
        assertTrue(loose.ok)
        assertTrue(loose.warnings.any { it.key == "name-no-slash" }, loose.warnings.toString())
    }

    @Test
    fun everySceneItIsShownInIsMadeWithoutAProblem() {
        val wide = qwerty.replace("width=\"1.4\" label=\".com\"", "width=\"5\" label=\".com\"")
        val r = check(wide)
        assertEquals(listOf("scene"), r.errors.map { it.key }.distinct())
        assertTrue(r.errors.first().args[0].contains("field=url"), r.errors.toString())
        // a key across rows in the middle of its row (Y): the row under it laid out past it
        val plain = replaceKey(replaceKey(qwerty, "semicolon", ""), "star", "").replace(" on-split=\"split\"", "")
        val tall = plain.replace("<key id=\"y\" label", "<key id=\"y\" rows=\"2\" label")
        assertEquals(emptyList(), check(tall).errors, "a key two rows tall in the middle of its row")
        val found = check(tall.replace("<key id=\"a\" label", "<key id=\"a\" width=\"0.5\" label")).errors
        assertEquals(listOf("scene"), found.map { it.key }.distinct())
        assertTrue(found.all { "reaches into" in it.args[1] }, found.toString())
        val over = check(tall.replace("<key id=\"g\" label", "<key id=\"g\" width=\"1.5\" label")).errors
        assertTrue(over.isNotEmpty() && over.all { "over the 9 it has" in it.args[1] }, over.toString())
        // drawn, only unseen: told, as a theme's blank key is
        val blank = check(qwerty.replace("label=\"拼\" text-size=\"18\" text-style=\"bold\"\n         send=\"code:U+F0104\" when=\"table table-lookup !looking-up\"", "send=\"code:U+F0104\" when=\"table table-lookup !looking-up\""))
        assertEquals(emptyList(), blank.errors)
        assertEquals(listOf("blank"), blank.warnings.map { it.key }.filter { it == "blank" })
        assertEquals("0.6.3", blank.minApp, "the old app refused it")
    }

    @Test
    fun whatIsOnlyToldIsWarned() {
        assertTrue("no-way" in warnings(replaceKey(replaceKey(qwerty, "symbols", ""), "numbers", "")))
        assertEquals(listOf(listOf("Number")), check(replaceKey(qwerty, "numbers", "")).warnings.filter { it.key == "no-way" }.map { it.args })
        assertEquals(listOf(listOf("Symbol")), check(replaceKey(qwerty, "symbols", "")).warnings.filter { it.key == "no-way" }.map { it.args })
        assertTrue(check(own("number")).warnings.none { it.key == "no-way" })
        assertTrue(check(own("symbols")).warnings.none { it.key == "no-way" })
        val coded = own("number").replace("send=\"sym:KP_1\"", "send=\"code:U+0031\"")
        assertTrue(check(coded).warnings.none { it.key == "pad-lacks" }, check(coded).warnings.toString())
        assertTrue("narrow" in warnings(qwerty.replace("<key id=\"comma\" role=\"comma\" kind=\"function\"", "<key id=\"comma\" role=\"comma\" kind=\"function\" width=\"0.4\"")))
        val typed = qwerty.replace("send=\"commit:.com\"", "send=\"commit:.io\"")
            .replace("</keyboard>", "<row><key id=\"haha\" label=\"哈\" send=\"commit:哈哈\" shortcut=\"my-qwerty/haha\"/><key id=\"slash2\" label=\"/\" send=\"commit:/\" shortcut=\"my-qwerty/slash2\"/></row>\n</keyboard>")
        val listed = check(typed)
        assertEquals(listOf(".io", "哈哈", "/"), listed.commits)
        assertEquals(LayoutCheck.Finding(0, "commits", listOf("3")), listed.warnings.single { it.key == "commits" })
        assertEquals(listOf("q"), check(qwerty.replace(" shortcut=\"full/q\"", "")).warnings.filter { it.key == "unnamed" }.map { it.args[0] })
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
        val pack = testData("qwerty.xml").replace("<key id=\"lookup\"", "<gap width=\"0\" look=\"function\"/><key id=\"lookup\"")
        val r = check(pack, strict = false)
        assertEquals(emptyList(), r.errors)
        assertEquals(listOf("id-built-in", "based-on"), r.warnings.map { it.key }.filter { it == "id-built-in" || it == "based-on" })
        assertTrue(r.warnings.none { it.key == "gap-look" })
        assertEquals(listOf(listOf("backspace")), check(replaceKey(pack, "backspace", ""), strict = false).warnings.filter { it.key == "lacks" }.map { it.args })
        val number = own("number").replace("<key id=\"symbols\" role=\"symbols\"", "<key id=\"symbols\" role=\"lock\"")
        assertTrue(check(number, strict = false).warnings.none { it.key == "bottom-left" })
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
            "meta" to qwerty062.replaceFirst("<defaults", "<meta author=\"me\"/>\n  <defaults"),
            "based-on" to qwerty062,
            "pads" to own("number").replaceFirst("family=\"number\"", "family=\"number\" pads=\"full\""),
            "rows-middle" to qwerty062.replace("<key id=\"g\" label", "<key id=\"g\" rows=\"2\" label"),
            // a key id twice in rows each shown on its own (the old app refused the second); a gap's id too
            "row-when gap" to qwerty062.replace("</keyboard>", "<row when=\"field=url\"><gap id=\"g1\" width=\"1\"/><key id=\"zz\" label=\"x\"/></row>\n<row when=\"!field=url\"><gap id=\"g1\" width=\"1\"/></row>\n</keyboard>"),
            "row-when" to qwerty062.replace("</keyboard>", "<row when=\"field=url\"><key id=\"zz\" label=\"x\"/></row>\n<row when=\"!field=url\"><key id=\"zz\" label=\"y\"/></row>\n</keyboard>"),
            // the app's own: an older app fails the board on the switch
            "table-lookup" to qwerty,
            "input-method-commands" to qwerty062.replace("<key id=\"g\" label", "<key id=\"g\" swipe-up=\"shortcut:switch_input_method\" label"),
            "input-method-commands menu" to qwerty062.replace("<key id=\"g\" label", "<key id=\"g\" long-press=\"shortcut:choose_input_method\" label"),
        )
        // lacks is the check's (whatABoardLacksIsToldOnceAndTaken), not the reader's
        assertEquals(LayoutCheck.FEATURES.keys - "lacks", samples.keys.map { it.substringBefore(' ') }.toSet())
        for ((sample, text) in samples) {
            val feature = sample.substringBefore(' ')
            val layout = LayoutReader.layout(text).ok()
            assertTrue(feature in LayoutCheck.features(layout), feature)
            assertEquals(LayoutCheck.FEATURES.getValue(feature), LayoutCheck.minApp(layout), feature)
        }
        for (id in boards - "qwerty") assertEquals(LayoutCheck.BASE_VERSION, LayoutCheck.minApp(LayoutReader.layout(testData("$id.xml")).ok()), id)
        val nine = LayoutReader.layout(testData("nine.xml")).ok()
        assertTrue("rows-middle" !in LayoutCheck.features(nine))
    }

    @Test
    fun theScenesAreTheScreensOfTheWideAsLandscapeRule() {
        val scenes = LayoutCheck.scenes(LayoutReader.layout(qwerty).ok(), listOf("pinyin"), emptyList())
        assertTrue(scenes.first { it.name == "600dp" }.env.landscape)
        assertTrue(!scenes.first { it.name == "412dp" }.env.landscape)
        assertTrue(scenes.any { it.name == "800dp turned split" })
        assertEquals(4 * 48 * 2.625, scenes.first { it.name == "800dp turned" }.env.height, 1e-9)
        assertEquals(4 * 56 * 2.625, scenes.first { it.name == "600dp" }.env.height, 1e-9)
        assertEquals(4 * 36 * 2.625, scenes.first { it.name == "800dp turned 144dp" }.env.height, 1e-9)
    }

    @Test
    fun whatAKeyTypesIsSeenAndALetterRunsNoCommand() {
        for (send in listOf("commit:a&#x202E;b", "commit:&#x200F;", "commit:a&#9;b", "&#x2066;", "code:U+061C", "code:U+E0041")) {
            val text = qwerty.replace("send=\"commit:/\"", "send=\"$send\"")
            assertEquals(listOf("hidden-chars"), errors(text), send)
            assertEquals(listOf("hidden-chars"), check(text, strict = false).warnings.map { it.key }.filter { it == "hidden-chars" }, send)
        }
        assertEquals(emptyList(), errors(qwerty.replace("send=\"commit:/\"", "send=\"commit:é😀\"")), "seen characters, one past the BMP among them")
        val clear = qwerty.replace("<key id=\"q\" label=\"{case:q}\" send=\"q\"", "<key id=\"q\" label=\"{case:q}\" send=\"q\" swipe-down=\"shortcut:clear\"")
        assertEquals(listOf(LayoutCheck.Finding(17, "command", listOf("q", "shortcut:clear"))), check(clear).errors)
        assertEquals(listOf("command"), check(clear, strict = false).warnings.map { it.key }.filter { it == "command" })
        val picker = qwerty.replace("<key id=\"a\" label=\"{case:a}\" send=\"a\"", "<key id=\"a\" label=\"{case:a}\" send=\"ime-picker\" kind=\"letter\"")
        assertEquals(listOf("command"), errors(picker))
        val own = qwerty.replace("</keyboard>", "<row><key id=\"paste\" kind=\"function\" label=\"贴\" send=\"shortcut:paste\" shortcut=\"my-qwerty/paste\"/></row>\n</keyboard>")
        assertEquals(emptyList(), errors(own).filter { it == "command" })
    }

    // a list item types its text; a letter is what types one, whatever the file calls it
    @Test
    fun aListsTextsAndALettersNamesAndSymbolsAreHeldAsWhatTheyDo() {
        fun listed(vararg items: String) = withRow(
            "<key id=\"it\" kind=\"function\" label=\"x\" send=\"commit:xy\" shortcut=\"my-qwerty/it\"><popup from=\"list\">" +
                items.joinToString("") { "<item text=\"$it\"/>" } + "</popup></key>",
        )
        for (hidden in listOf("a&#x202E;b", "&#x200B;", "a&#10;b")) {
            assertEquals(listOf("hidden-chars"), errors(listed(hidden)).filter { it == "hidden-chars" }, hidden)
            assertEquals(listOf("hidden-chars"), loaded(listed(hidden)).filter { it == "hidden-chars" }, hidden)
        }
        assertEquals(listOf("commit-long"), errors(listed("x".repeat(LayoutCheck.MAX_COMMIT + 1))).filter { it == "commit-long" })
        assertEquals(listOf("too-many-commits"), loaded(listed(*Array(LayoutCheck.MAX_COMMITS + 1) { "w$it" })).filter { it == "too-many-commits" })
        assertTrue("ok" in check(listed("ok")).commits)
        val named = qwerty.replace("send=\"e\" shortcut=\"full/e\"", "send=\"e\" shortcut=\"backspace\"")
        assertEquals(listOf(LayoutCheck.Finding(19, "command", listOf("e", "shortcut=fn/backspace"))), check(named).errors)
        val again = qwerty.replace("</keyboard>", "<row><key id=\"apostrophe-2\" role=\"apostrophe\" kind=\"function\" label=\"分词\" send=\"'\" shortcut=\"separator\"/></row>\n</keyboard>")
        assertEquals(emptyList(), check(again).errors.filter { it.key == "command" })
        assertEquals(listOf("command"), loaded(named))
        assertEquals(listOf("command"), errors(qwerty.replace("send=\"e\" shortcut=\"full/e\"", "send=\"e\" shortcut=\"space\"")).filter { it == "command" })
        for (send in listOf("commit:e", "code:U+0065", "e")) {
            val key = "<key id=\"ee\" role=\"comma\" kind=\"function\" label=\"e\" send=\"$send\" swipe-up=\"shortcut:clear\" shortcut=\"my-qwerty/ee\"/>"
            assertEquals(listOf("command"), errors(withRow(key)).filter { it == "command" }, send)
            assertEquals(listOf("command"), loaded(withRow(key)).filter { it == "command" }, send)
        }
        assertEquals(listOf("command"), errors(qwerty.replace("send=\"e\" shortcut=\"full/e\"", "send=\"commit:hello\" kind=\"function\" role=\"comma\" swipe-up=\"shortcut:clear\" shortcut=\"full/e\"")).filter { it == "command" })
        for (sym in listOf("sym:Return", "sym:BackSpace", "sym:Escape", "sym:KP_Enter")) {
            assertEquals(listOf("command"), loaded(qwerty.replace("send=\"q\" shortcut=\"full/q\"", "send=\"q\" swipe-down=\"$sym\" shortcut=\"full/q\"")), sym)
        }
        assertEquals(emptyList(), loaded(qwerty.replace("send=\"q\" shortcut=\"full/q\"", "send=\"q\" swipe-down=\"sym:KP_1\" shortcut=\"full/q\"")))
        val item = qwerty.replace(
            "send=\"q\" shortcut=\"full/q\"><variant when=\"roots\" label=\"{root:q}\"/></key>",
            "send=\"q\" shortcut=\"full/q\"><variant when=\"roots\" label=\"{root:q}\"/><popup from=\"list\"><item text=\"x\" send=\"shortcut:clear\"/></popup></key>",
        )
        assertEquals(listOf("command"), loaded(item))
    }

    // every state of a key judged, an allow-list
    @Test
    fun everyStateOfAKeyIsJudgedAndOnlyATrueCopyBorrowsAName() {
        val e = "<key id=\"e\" label=\"{case:e}\" send=\"e\" shortcut=\"full/e\">"
        for (name in listOf("backspace", "return")) {
            assertEquals(commandBoth, commands(qwerty.replace(e, "<key id=\"e\" label=\"{case:e}\" shortcut=\"$name\">")), name)
        }
        assertEquals(commandBoth, commands(qwerty.replace(e, "<key id=\"e\" label=\"{case:e}\" letters=\"e\" shortcut=\"backspace\">")))
        refused("<key id=\"bs\" kind=\"letter\" label=\"x\" shortcut=\"backspace\"/>")
        assertEquals(commandBoth, commands(qwerty.replace("send=\"sym:space\" swipe-axis=\"both\" shortcut=\"space\"", "swipe-axis=\"both\" shortcut=\"return\"")))
        refused("<key id=\"bs\" label=\"e\" shortcut=\"backspace\"/>")
        for (send in listOf("commit:ee", "commit:hello world")) {
            for (swipe in listOf("shortcut:clear", "sym:Return")) refused("<key id=\"w2\" kind=\"function\" label=\"ee\" send=\"$send\" swipe-up=\"$swipe\" shortcut=\"my-qwerty/w2\"/>")
        }
        refused("<key id=\"fx\" kind=\"function\" label=\"x\" send=\"lang\" shortcut=\"backspace\"><variant when=\"!composing\" label=\"e\" send=\"e\"/></key>")
        for (send in listOf("e", "commit:hello")) {
            refused("<key id=\"fx\" kind=\"function\" label=\"x\" send=\"layout:Number\" swipe-up=\"shortcut:clear\" shortcut=\"my-qwerty/fx\"><variant when=\"!composing\" label=\"e\" send=\"$send\"/></key>")
        }
        refused("<key id=\"fx\" kind=\"function\" icon=\"backspace\" a11y=\"a11y_key_backspace\" shortcut=\"backspace\"><variant when=\"composing\" label=\"e\"/></key>")
        refused("<key id=\"rx\" kind=\"function\" icon=\"{return-icon}\" a11y=\"{return}\" send=\"sym:Return\" shortcut=\"return\"><variant when=\"composing\" label=\"e\" send=\"e\"/></key>")
        for (name in listOf("backspace", "return")) refused("<key id=\"sx\" kind=\"function\" label=\"x\" send=\"sym:space\" shortcut=\"$name\"/>")
        for (send in listOf("sym:space", "code:U+0020", "commit:　")) {
            refused("<key id=\"sx\" kind=\"function\" label=\"x\" send=\"$send\" swipe-up=\"shortcut:clear\" shortcut=\"my-qwerty/sx\"/>")
        }
        for (send in listOf("lang", "caps", "picker:emoji", "sym:e")) refused("<key id=\"cx\" kind=\"function\" label=\"e\" send=\"$send\" shortcut=\"backspace\"/>")
        refused("<key id=\"cx\" label=\"{case:e}\" send=\"layout:Number\" shortcut=\"backspace\"/>")
        for (send in listOf("sym:5", "sym:KP_5", "keypad:5", "action:tone-1")) {
            refused("<key id=\"dx\" kind=\"function\" label=\"5\" send=\"$send\" swipe-up=\"shortcut:clear\" shortcut=\"my-qwerty/dx\"/>")
        }
        refused("<key id=\"dx\" kind=\"function\" label=\"5\" send=\"keypad:5\" shortcut=\"backspace\"/>")
        passes("<key id=\"apostrophe-2\" role=\"apostrophe\" kind=\"function\" label=\"分词\" send=\"'\" shortcut=\"separator\"/>")
        passes("<key id=\"space-2\" role=\"space\" kind=\"space\" label=\"x\" send=\"sym:space\" shortcut=\"space\"/>")
        refused("<key id=\"space-2\" role=\"space\" kind=\"space\" label=\"x\" send=\"sym:space\" shortcut=\"return\"/>")
        for (label in listOf("⌫", "删", "DEL")) passes("<key id=\"bs\" kind=\"function\" label=\"$label\" send=\"sym:BackSpace\" shortcut=\"backspace\"/>")
        refused("<key id=\"bs\" kind=\"function\" label=\"e\" send=\"sym:BackSpace\" shortcut=\"backspace\"/>")
        for (send in listOf("layout:Number", "ime-picker")) {
            passes("<key id=\"go\" kind=\"function\" label=\"符\" send=\"$send\" swipe-up=\"shortcut:clear\" shortcut=\"my-qwerty/go\"/>")
        }
        // a grouped board keeping the full keyboard's ⇧ (the layout maker's merge): a true copy of an app key
        val grouped = qwerty.replace("family=\"letters\"", "family=\"grouped\"").replace("based-on=\"qwerty\"", "based-on=\"k18\"")
        assertEquals(emptyList(), check(grouped).errors.filter { it.key == "command" && it.args[0] == "shift" })
        assertEquals(listOf("shift"), check(grouped.replace("shortcut=\"shift\"", "shortcut=\"backspace\"")).errors.filter { it.key == "command" }.map { it.args[0] })
        // emoticon.xml's id is emoji's: own() makes no copy of it
        for (id in boards - "emoticon") assertEquals(emptyList(), check(own(id)).errors.filter { it.key == "command" }, id)
    }

    // a key showing a letter is no true copy of one whose tap is a command
    @Test
    fun aLetterShownIsALetterAndABaseFunctionsIdTypingLosesItsName() {
        refused("<key id=\"w2\" label=\"e\" swipe-up=\"shortcut:clear\" shortcut=\"my-qwerty/w2\"/>")
        refused("<key id=\"w2\" label=\"{case:e}\" send=\"caps\" swipe-up=\"shortcut:clear\" shortcut=\"my-qwerty/w2\"/>")
        val copy = "<key id=\"bs2\" kind=\"function\" label=\"⌫\" send=\"sym:BackSpace\" shortcut=\"backspace\"/>"
        for ((id, name) in listOf("backspace" to "backspace", "enter" to "return")) {
            val made = Regex("<key id=\"$id\"[^>]*?/?>").replace(qwerty) { "<key id=\"$id\" label=\"e\" send=\"e\" shortcut=\"$name\"" + if (it.value.endsWith("/>")) "/>" else ">" }
            assertEquals(commandBoth, commands(made), id)
            assertEquals(commandBoth, commands(made.replace("</keyboard>", "<row>$copy</row>\n</keyboard>")), "$id with a copy")
        }
        // a 删除 copy labelled e looks like e
        for (label in listOf("e", "{case:e}")) refused("<key id=\"bs\" kind=\"function\" label=\"$label\" send=\"sym:BackSpace\" shortcut=\"backspace\"/>")
        refused("<key id=\"bs\" kind=\"function\" label=\"e\" send=\"sym:BackSpace\" swipe-up=\"shortcut:clear\" shortcut=\"backspace\"/>")
    }

    // a letter is what the eye takes for one: a {punct:} drawn as is, spaced, folded, of another script
    @Test
    fun aCommandShowingALetterIsNoCopyAndALetterIsWhatTheEyeTakesForOne() {
        val nine = own("nine")
        for (label in listOf("e", "{case:e}", "{group:we}")) refused("<key id=\"r2\" kind=\"function\" label=\"$label\" send=\"sym:Return\" shortcut=\"return\"/>")
        val enter = "width=\"1.5\" icon=\"{return-icon}\" a11y=\"{return}\""
        assertEquals(1, Regex(Regex.escape(enter)).findAll(qwerty).count())
        assertEquals(commandBoth, commands(qwerty.replace(enter, "width=\"1.5\" label=\"e\"")), "enter labelled e")
        refused("<key id=\"l2\" kind=\"function\" label=\"e\" send=\"lang\" shortcut=\"lang\"/>")
        refused("<key id=\"r2\" kind=\"function\" label=\"e\" send=\"sym:Escape\" shortcut=\"retype\"/>", nine)
        assertEquals(commandBoth, commands(nine.replace("label=\"重输\"", "label=\"e\"")), "retype labelled e")
        for (label in listOf("{punct:e}", "{punct:5}", " e", "e\u200B", "ｅ", "\u0435", "é", "e\u0301", "ab", "E;")) refused(swiping(label, "qwerty"))
        refused(swiping("abc", "nine"), nine)
        refused(swiping("qw", "k18"), own("k18"))
        for (label in listOf("符", "123", "拼", "中", "Emoji", ".com", "{punct:,}", "ㄅ", "あ", "한")) passes(swiping(label, "qwerty"))
    }

    // what draws nothing next to a letter is not seen; boxed capitals and regional indicators are letters
    @Test
    fun aBaseFunctionKeyShownAsALetterKeepsNoCommandAndWhatDrawsNothingIsNotSeen() {
        fun shownAsE(board: String, id: String): String {
            val key = Regex("<key id=\"$id\"[^>]*>").find(board)!!.value
            val made = key.replace(Regex(" (icon|a11y|label|shortcut)=\"[^\"]*\""), "").replace("<key id=\"$id\"", "<key id=\"$id\" label=\"e\"")
            return board.replaceFirst(key, made)
        }
        for (id in listOf("enter", "backspace", "lang")) assertEquals(commandBoth, commands(shownAsE(qwerty, id)), id)
        assertEquals(commandBoth, commands(shownAsE(own("nine"), "reset")), "reset")
        val unseen = listOf("\uDB40\uDC20", "\uDB40\uDC01", "\uDB40\uDD00", "\u3164", "\u115F", "\u1160", "\uFFA0", "\u2800", "\u00AD", "\uFE0F")
        for (pad in unseen) {
            for ((send, name) in listOf("sym:BackSpace" to "backspace", "sym:Return" to "return")) {
                val key = "<key id=\"c2\" kind=\"function\" label=\"e$pad\" send=\"$send\" shortcut=\"$name\"/>"
                assertEquals(commandBoth, commands(withRow(key)), "$name ${pad.map { it.code.toString(16) }}")
            }
        }
        for (label in listOf("\uD83C\uDD30", "\uD83C\uDD50", "\uD83C\uDD70", "\uD83C\uDDEA")) {
            val key = "<key id=\"x2\" kind=\"function\" label=\"$label\" send=\"layout:Number\" swipe-up=\"shortcut:clear\" shortcut=\"my-qwerty/x2\"/>"
            assertEquals(commandBoth, commands(withRow(key)), label)
        }
    }

    // a mark beyond the BMP and the null notehead are not seen; parenthesized and squared capitals are letters
    @Test
    fun aMarkBeyondTheBmpIsNotSeenAndEveryEnclosedCapitalIsALetter() {
        fun text(cp: Int) = if (cp < 0x10000) cp.toChar().toString() else charArrayOf(
            (((cp - 0x10000) shr 10) + 0xD800).toChar(), (((cp - 0x10000) and 0x3FF) + 0xDC00).toChar(),
        ).concatToString()
        for (pad in listOf(0x1D165, 0x1D167, 0x1D16E, 0x101FD, 0x1E944, 0x1D159)) {
            val label = "e" + text(pad)
            for ((send, name) in listOf("sym:BackSpace" to "backspace", "sym:Return" to "return")) {
                assertEquals(commandBoth, commands(withRow("<key id=\"c2\" kind=\"function\" label=\"$label\" send=\"$send\" shortcut=\"$name\"/>")), "$name ${pad.toString(16)}")
            }
            assertEquals(commandBoth, commands(withRow(swiping(label))), "swipe ${pad.toString(16)}")
        }
        for (cp in listOf(0x1F110, 0x1F129, 0x1F12B, 0x1F12E)) assertEquals(commandBoth, commands(withRow(swiping(text(cp)))), cp.toString(16))
        for (cp in listOf(0x1F12A, 0x1F18A, 0x1D18F, 0x1D194, 0x1FBF0, 0x1FBF9)) {
            assertEquals(commandBoth, commands(withRow(swiping(text(cp)))), cp.toString(16))
        }
    }

    // a key of the app's name writing a long press or swipe of its own: the app's is taken, the file told by line
    @Test
    fun aKeyOfTheAppsNameWritingWhatItDoesHeldIsTold() {
        val k18 = own("k18")
        assertTrue(check(k18).warnings.none { it.key == "held" }, "the app's board as it is: nothing written")
        val swiped = k18.replace("letters=\"h\" shortcut=\"g18/h\"", "letters=\"h\" shortcut=\"g18/h\" swipe-down=\"x\"")
        val line = swiped.lines().indexOfFirst { "swipe-down=\"x\"" in it } + 1
        assertEquals(listOf(LayoutCheck.Finding(line, "held", listOf("g18/h", "k18"))), check(swiped).warnings.filter { it.key == "held" })
        val borrowed = qwerty.replace("</keyboard>", "<row><key id=\"h2\" label=\"H\" send=\"commit:h\" shortcut=\"g18/h\" alt=\"!\" swipe-up=\"commit:嗨\"/></row>\n</keyboard>")
        assertEquals(listOf(listOf("g18/h", "k18")), check(borrowed).warnings.filter { it.key == "held" }.map { it.args })
        // the swiped 「嗨」 is the app key's, not among the texts it types
        assertEquals(listOf("h"), check(borrowed).commits)
        val own = qwerty.replace("</keyboard>", "<row><key id=\"h2\" label=\"H\" send=\"commit:h\" shortcut=\"my-qwerty/h2\" swipe-up=\"commit:嗨\"/></row>\n</keyboard>")
        assertTrue(check(own).warnings.none { it.key == "held" })
        assertEquals(listOf("h", "嗨"), check(own).commits)
        val bare = check(own("number").replaceFirst("send=\"sym:KP_Add\"", "send=\"sym:KP_Add\" swipe-up=\"x\"")).warnings.filter { it.key == "held" }
        assertEquals(listOf(listOf("+", "number")), bare.map { it.args })
    }

    // letters= other than an app key's make its long press none of theirs, told
    @Test
    fun aKeyOfTheAppsNameIsTheAppsKeyEachToldAndPopupsToo() {
        val k18 = own("k18")
        fun held(text: String) = check(text).warnings.filter { it.key.startsWith("held") && it.key != "held-popups" }
        val merged = k18.replace("letters=\"h\" shortcut=\"g18/h\"", "letters=\"hjk\" shortcut=\"g18/h\"")
        val line = merged.lines().indexOfFirst { "letters=\"hjk\"" in it } + 1
        assertEquals(listOf(LayoutCheck.Finding(line, "held-letters", listOf("g18/h", "k18"))), held(merged))
        assertEquals(listOf("held", "held-letters"), held(merged.replace("letters=\"hjk\"", "letters=\"hjk\" swipe-down=\"x\"")).map { it.key })
        assertTrue(held(merged.replace("shortcut=\"g18/h\"", "shortcut=\"my-k18/hjk\"")).isEmpty())
        assertTrue(held(qwerty.replace("</keyboard>", "<row><key id=\"h2\" label=\"H\" send=\"h\" shortcut=\"g18/h\"/></row>\n</keyboard>")).isEmpty())
        val two = qwerty.replace(
            "</keyboard>",
            "<row><key id=\"a2\" send=\"a\" shortcut=\"full/a\" swipe-down=\"x\"/><key id=\"b2\" send=\"b\" shortcut=\"full/b\" swipe-down=\"y\"/></row>\n</keyboard>",
        )
        assertEquals(listOf(listOf("full/a", "qwerty"), listOf("full/b", "qwerty")), held(two).map { it.args })
        val variant = qwerty.replace(
            "</keyboard>",
            "<row><key id=\"a2\" send=\"a\" shortcut=\"full/a\"><variant when=\"ime=english\" shortcut=\"full/b\" swipe-down=\"y\"/></key></row>\n</keyboard>",
        )
        assertTrue(listOf("full/b", "qwerty") in held(variant).map { it.args })
        val off = k18.replaceFirst("family=\"grouped\"", "family=\"grouped\" popups=\"off\"")
        assertEquals(listOf(LayoutCheck.Finding(0, "held-popups", listOf("k18"))), check(off).warnings.filter { it.key == "held-popups" })
        assertTrue(check(k18).warnings.none { it.key == "held-popups" })
    }

    // a key's id and name hold nothing unseen either, as the layout's name
    @Test
    fun aKeysIdAndNameHoldNothingUnseen() {
        fun row(attrs: String) = withRow("<key $attrs label=\"Z\" send=\"commit:z\"/>")
        val id = row("id=\"z&#x202E;z\" shortcut=\"my-qwerty/zz\"")
        val line = id.lines().indexOfFirst { "z&#x202E;z" in it } + 1
        assertEquals(listOf(LayoutCheck.Finding(line, "hidden-chars", listOf("zz"))), check(id).errors.filter { it.key == "hidden-chars" })
        assertEquals(listOf("hidden-chars"), check(id, strict = false).warnings.map { it.key }.filter { it == "hidden-chars" })
        assertEquals(emptyList(), loaded(id).filter { it == "hidden-chars" })
        val name = row("id=\"zz\" shortcut=\"my-qwerty/z&#x202E;z\"")
        assertEquals(listOf(listOf("my-qwerty/zz")), check(name).errors.filter { it.key == "hidden-chars" }.map { it.args })
        assertEquals(emptyList(), check(row("id=\"zz\" shortcut=\"my-qwerty/zz\"")).errors.filter { it.key == "hidden-chars" })
    }

    @Test
    fun aNameGivenElsewhereHoldsNothingUnseenEither() {
        val hidden = "my-qwerty/z&#x202E;y"
        val variant = withRow("<key id=\"zz\" label=\"Z\" send=\"commit:z\" shortcut=\"my-qwerty/zz\"><variant when=\"ime=english\" shortcut=\"$hidden\"/></key>")
        val layoutDefaults = qwerty.replaceFirst("<defaults", "<defaults shortcut=\"$hidden\"")
        val rowDefaults = withRow("<defaults shortcut=\"$hidden\"/><key id=\"zz\" label=\"Z\" send=\"commit:z\"/>")
        fun lineOf(text: String, part: String) = text.lines().indexOfFirst { part in it } + 1
        fun told(line: Int) = listOf(LayoutCheck.Finding(line, "hidden-chars", listOf("my-qwerty/zy")))
        val rows = mapOf(
            "a variant's shortcut=, at its key's line" to (variant to told(lineOf(variant, "shortcut=\"my-qwerty/zz\"><variant"))),
            "the layout's <defaults shortcut=>, at line 0" to (layoutDefaults to told(0)),
            "a row's <defaults shortcut=>, at the row's line" to (rowDefaults to told(lineOf(rowDefaults, "<row><defaults"))),
        )
        assertEquals(
            rows.mapValues { (_, row) -> row.second },
            rows.mapValues { (_, row) -> check(row.first).errors.filter { it.key == "hidden-chars" } },
        )
    }

    // a grouped layout based on the 26 keys: held to the 18 keys, its app-named keys looked up from its own based-on
    @Test
    @Uat("ST-192")
    fun aGroupedLayoutOnTheTwentySixKeysIsHeldToTheEighteenButToldOfItsOwnBase() {
        val grouped = own("k18").replace("based-on=\"k18\"", "based-on=\"qwerty\"")
        assertEquals("k18", LayoutCheck.baseId(LayoutReader.layout(grouped).ok(), sources))
        val swiped = grouped.replaceFirst("shortcut=\"backspace\"", "shortcut=\"backspace\" swipe-down=\"x\"")
        val held = check(swiped).warnings.filter { it.key == "held" }.map { it.args }
        assertTrue(listOf("fn/backspace", "qwerty") in held, held.toString())
        assertTrue(listOf("fn/backspace", "k18") !in held, held.toString())
        // group corners judged by its own family's shape: an old k18 file is told of nothing
        val mint = testData("mint-k17/layouts/k18.xml").replace("id=\"k18\"", "id=\"my-k17\" based-on=\"qwerty\"")
        assertEquals(emptyList(), check(mint).warnings.filter { it.key == "held" })
    }

    // a literal alt= with no swipe-up= is a first: held to what a commit: is and listed with the texts it types
    @Test
    fun aLiteralAltTypedAsAFirstIsHeldToWhatACommitIs() {
        fun ownKey(attrs: String) = qwerty.replace("</keyboard>", "<row><key id=\"zz\" label=\"Z\" send=\"commit:z\" shortcut=\"my-qwerty/zz\" $attrs/></row>\n</keyboard>")
        assertEquals(listOf("hidden-chars"), errors(ownKey("alt=\"\u202Emoc.live\"")))
        assertEquals(listOf("hidden-chars"), errors(ownKey("alt=\"a\u200Bb\" swipe-up=\"commit:z\"")), "drawn and maybe typed: never hidden")
        assertTrue("hidden-chars" in errors(qwerty.replace("</keyboard>", "<row><defaults alt=\"\u202E\"/><key id=\"zz\" label=\"Z\" send=\"commit:z\" shortcut=\"my-qwerty/zz\"/></row>\n</keyboard>")), "a row's")
        assertEquals(listOf("commit-long"), errors(ownKey("alt=\"${"x".repeat(LayoutCheck.MAX_COMMIT + 1)}\"")))
        assertEquals(listOf("hidden-chars"), errors(ownKey("alt=\"code:U+202E\"")))
        assertEquals(listOf("hidden-chars"), errors(ownKey("alt=\"code:U+202E\" swipe-up=\"commit:z\"")))
        val variant = qwerty.replace("</keyboard>", "<row><key id=\"zz\" label=\"Z\" send=\"commit:z\" shortcut=\"my-qwerty/zz\"><variant when=\"ime=english\" alt=\"code:U+200B\"/></key></row>\n</keyboard>")
        assertEquals(listOf("hidden-chars"), errors(variant))
        assertTrue("hidden-chars" in errors(qwerty.replace("</keyboard>", "<row><defaults alt=\"code:U+2066\"/><key id=\"zz\" label=\"Z\" send=\"commit:z\" shortcut=\"my-qwerty/zz\"/></row>\n</keyboard>")), "a row's code:")
        assertEquals(emptyList(), errors(ownKey("alt=\"code:U+000202E\"")))
        assertEquals(listOf("z"), check(ownKey("alt=\"code:U+000202E\"")).commits)
        assertEquals(listOf("commit-long"), errors(ownKey("alt=\"commit:${"x".repeat(LayoutCheck.MAX_COMMIT + 1)}\"")))
        assertEquals(emptyList(), errors(ownKey("alt=\"commit:${"x".repeat(LayoutCheck.MAX_COMMIT)}\"")))
        assertEquals(listOf("z", "zz!"), check(ownKey("alt=\"zz!\"")).commits)
        assertEquals(listOf("z", "q"), check(ownKey("alt=\"zz!\" swipe-up=\"commit:q\"")).commits)
        assertEquals(listOf("z"), check(ownKey("alt=\"ime-picker\"")).commits)
        assertEquals(listOf("z"), check(ownKey("alt=\"zz!\"").replace("my-qwerty/zz", "full/z")).commits)
        val rowNamed = qwerty.replace("</keyboard>", "<row><defaults shortcut=\"full/z\"/><key id=\"zz\" label=\"Z\" send=\"commit:z\" swipe-up=\"commit:哈\"/></row>\n</keyboard>")
        assertEquals(listOf("z"), check(rowNamed).commits)
    }

    // a drawn file is not trusted: what needs no scene is asked again as it is read to be drawn
    @Test
    fun aLayoutReadToBeDrawnIsHeldToWhatNeedsNoScene() {
        for (id in listOf("qwerty", "k14", "k18", "nine", "number", "symbols")) assertEquals(emptyList(), loaded(own(id)), id)
        assertEquals(emptyList(), loaded(qwerty.replace("role=\"backspace\" ", "")), "lacking ⌫: drawn")
        assertEquals(listOf("groups"), loaded(withRow(THIRTEEN_GROUPS, own("k18"))))
        assertEquals(listOf("name-no-slash"), loaded(qwerty.replace("shortcut=\"full/q\"", "shortcut=\"mine\"")))
        assertEquals(listOf("hidden-chars"), loaded(qwerty.replace("send=\"commit:/\"", "send=\"commit:&#x202E;\"")))
        assertEquals(listOf("layout-target"), loaded(qwerty.replace("send=\"commit:/\"", "send=\"layout:Emoji\"")))
        assertEquals(listOf("id-built-in"), loaded(qwerty.replace("id=\"my-qwerty\"", "id=\"k18\"")))
        assertEquals(emptyList(), loaded(qwerty.replace("name=\"26 键\"", "name=\"26&#x202E;键\"")))
        val meta = qwerty.replaceFirst("<defaults", "<meta min-app=\"9.0.0\"/>\n  <defaults")
        assertEquals(listOf("newer"), loaded(meta))
    }

    // held as text: the same on the JVM and JS (the app's import and the page agree)
    @Test
    fun theReportsAreTheSnapshots() {
        val cases = listOf(
            "qwerty" to qwerty, "k14" to own("k14"), "k18" to own("k18"), "nine" to own("nine"), "number" to own("number"),
            "symbols" to own("symbols"), "k17" to testData("k17.xml").replaceFirst("id=\"k17\"", "id=\"my-k17\" based-on=\"k18\"")
                .replace("</keyboard>", "  <include src=\"grouped-bottom\"/>\n</keyboard>"),
            "the-app-qwerty" to testData("qwerty.xml"),
            "nine-big-enter" to testData("layout-check/nine-big-enter.layout"),
            "pack-qwerty" to testData("qwerty.xml"),
        )
        val text = cases.joinToString("\n") { (name, t) ->
            // the app the sample asks (min-app="0.6.3")
            val r = check(t, strict = name != "pack-qwerty", app = "0.6.3")
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
