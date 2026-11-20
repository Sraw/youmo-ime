/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.TEST_DATA
import org.fcitx.fcitx5.android.scene.keys.DefaultMarks
import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant
import org.fcitx.fcitx5.android.scene.readText
import org.fcitx.fcitx5.android.scene.recording
import org.fcitx.fcitx5.android.scene.skin.assertRows
import org.fcitx.fcitx5.android.scene.testData
import org.fcitx.fcitx5.android.scene.theme.Meta
import org.fcitx.fcitx5.android.scene.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// one function for the app's import, upgrade and restore and the layout maker's page, held alike on the JVM and in JS
class PackConvertTest {
    private val boards = listOf(
        "emoji-search", "emoji", "emoticon", "expanded-side", "k14", "k18", "nine", "number-row", "number",
        "qwerty", "symbols", "zhuyin-nine", "zhuyin",
    )
    private val sources = LayoutCheck.Sources(boards.associateWith { testData("$it.xml") }, mapOf("grouped-bottom" to testData("grouped-bottom.xml")))
    private val k17 by lazy { testData("mint-k17/layouts/k18.xml") }
    private val meta = Meta("小明", "1.0", "CC-BY-4.0", null, null)

    private fun convert(text: String, id: String = "k18", theme: String = "薄荷", fragments: Map<String, String> = emptyMap(), m: Meta? = meta) =
        PackLayouts.convert(theme, m, id, text, fragments, sources, "0.6.1")

    private fun done(c: PackLayouts.Converted) = assertIs<PackLayouts.Converted.Done>(c, (c as? PackLayouts.Converted.Not)?.why)

    @Test
    fun theHashIsFnv1aOfTheUtf8() {
        // the published values of FNV-1a 32: the JVM and JS give the same id
        assertEquals("811c9dc5", PackLayouts.hash(""))
        assertEquals("e40c292c", PackLayouts.hash("a"))
        // a char-by-char hash or another encoding would differ
        assertEquals("1e9de8c1", PackLayouts.hash("é"))
        assertEquals("698bc6f4", PackLayouts.hash("薄荷 17 键"))
    }

    // an include's when= alternatives with its rows' (every pair) bounded by PackLayouts.MAX_ALTERNATIVES
    @Test
    fun anIncludeAndItsRowsOfTooManyAlternativesAreNotTaken() {
        fun alternatives(n: Int, name: String) = (listOf("landscape", "!landscape") + (1..n - 2).map { "ime=$name$it" }).joinToString(", ")
        fun of(n: Int): PackLayouts.Converted {
            val bottom = testData("grouped-bottom.xml").replaceFirst("<row", "<row when=\"${alternatives(n, "a")}\"")
            val text = k17.replace("<include src=\"grouped-bottom\"/>", "<include src=\"grouped-bottom\" when=\"${alternatives(n, "b")}\"/>")
            check(text != k17 && "ime=a1" in bottom)
            return convert(text, fragments = mapOf("grouped-bottom" to bottom))
        }
        val not = assertIs<PackLayouts.Converted.Not>(of(17))
        assertTrue("alternatives" in not.why, not.why)
        val r = of(16)
        assertFalse(r is PackLayouts.Converted.Not && "alternatives" in r.why, (r as? PackLayouts.Converted.Not)?.why)
    }

    @Test
    fun theExamplePackMakesTheSameFileEverywhere() {
        val c = done(convert(k17, theme = "薄荷 17 键"))
        assertEquals("t${PackLayouts.hash("薄荷 17 键")}-k18", c.id)
        assertEquals("薄荷 17 键 · 17 键", c.name)
        assertEquals(mapOf("g18/h" to "${c.id}/hjk"), c.renames)
        val file = "$TEST_DATA/convert/k17-motion.layout"
        if (recording()) writeText(file, c.text)
        assertEquals(readText(file), c.text, "convert/k17-motion.layout: -Psnapshots.record=true")
        val read = LayoutReader.layout(c.text).ok()
        assertEquals("k18", read.basedOn)
        assertEquals(LayoutMeta("小明", null, "CC-BY-4.0", null, null), read.meta)
        assertEquals(emptyList(), LayoutCheck.loaded(read, sources, "0.6.1"))
        assertEquals(c.text, done(convert(k17, theme = "薄荷 17 键")).text)
    }

    @Test
    fun thePacksOwnBottomRowIsWrittenIn() {
        val bottom = testData("grouped-bottom.xml").replace("<fragment", "<!-- mine -->\n<fragment")
            .replaceFirst(Regex("label=\"[^\"]*\""), "label=\"PK\"")
        val with = done(convert(k17, fragments = mapOf("grouped-bottom" to bottom)))
        assertFalse("<include" in with.text)
        assertTrue("label=\"PK\"" in with.text)
        val keys = LayoutReader.layout(with.text).ok().parts.filterIsInstance<Row>().flatMap { it.cells }.filterIsInstance<Key>().map { it.id }
        val fragmentKeys = LayoutReader.fragment(bottom).ok().rows.flatMap { it.cells }.filterIsInstance<Key>().map { it.id }
        assertTrue(keys.containsAll(fragmentKeys))
        val without = done(convert(k17))
        assertTrue("<include src=\"grouped-bottom\"/>" in without.text)
        assertTrue("<include src=\"grouped-bottom\"/>" in done(convert(k17, fragments = mapOf("mine" to bottom))).text)
    }

    @Test
    fun anIncludesWhenAndTheFragmentsDefaultsGoOnItsRows() {
        val bottom = """<fragment xmlns="https://youmo-ime/layout/1" id="grouped-bottom"><defaults text-size="11"/>""" +
            """<row when="landscape"><key id="x1" label="1"/></row><row><key id="x2" label="2"/></row></fragment>"""
        val rows = PackLayouts.convertRows(
            listOf(Include("grouped-bottom", Condition(listOf(listOf(Condition.Term("compact", null, false)), listOf(Condition.Term("split", null, false)))), 1)),
            mapOf("grouped-bottom" to LayoutReader.fragment(bottom).ok()),
        ).filterIsInstance<Row>()
        assertEquals(listOf("compact landscape, split landscape", "compact, split"), rows.map { it.condition.toString() })
        assertTrue(rows.all { it.defaults.textSize == 11.0 })
    }

    @Test
    fun namesTheRuleRefusesAreRenamedOnceEach() {
        val text = k17
            .replace("shortcut=\"g18/q\"", "shortcut=\"mine\"")
            .replace("shortcut=\"g18/we\"", "shortcut=\"mine\"")
            .replace("shortcut=\"g18/rt\"", "shortcut=\"g18/nope\"")
        val c = done(convert(text))
        val id = c.id
        assertEquals(mapOf("fn/mine" to "$id/r1c1", "g18/nope" to "$id/r1c3", "g18/h" to "$id/hjk"), c.renames)
        val keys = LayoutReader.layout(c.text).ok().parts.filterIsInstance<Row>().flatMap { it.cells }.filterIsInstance<Key>().associateBy { it.id }
        assertEquals("$id/r1c1", keys.getValue("r1c2").attrs.shortcut)
        assertEquals(emptyList(), LayoutCheck.loaded(LayoutReader.layout(c.text).ok(), sources, "0.6.1"))
    }

    @Test
    fun aVariantsNameIsRenamedApartFromItsKeys() {
        val text = k17.replace(
            Regex("(<key id=\"r1c1\"[^>]*?)/>"),
        ) { it.groupValues[1].replace("shortcut=\"g18/q\"", "shortcut=\"a\"") + "><variant when=\"landscape\" shortcut=\"b\"/></key>" }
        val c = done(convert(text))
        assertEquals(mapOf("fn/a" to "${c.id}/r1c1", "fn/b" to "${c.id}/r1c1-2", "g18/h" to "${c.id}/hjk"), c.renames)
    }

    // a <defaults>' name is every key's under it that names none of its own: renamed as a key's
    @Test
    fun aNameADefaultsGivesIsRenamedAsAKeys() {
        val text = k17.replace("<defaults kind=\"letter\"", "<defaults shortcut=\"mine\" kind=\"letter\"")
            .replaceFirst("<row>", "<row id=\"top\"><defaults shortcut=\"g18/nope\"/>")
        val bottom = """<fragment xmlns="https://youmo-ime/layout/1" id="grouped-bottom"><defaults shortcut="theirs"/>""" +
            testData("grouped-bottom.xml").substringAfter("<row", "").let { "<row$it" }
        val c = done(convert(text, fragments = mapOf("grouped-bottom" to bottom)))
        val id = c.id
        assertEquals("$id/keys", c.renames["fn/mine"])
        assertEquals("$id/top", c.renames["g18/nope"])
        assertTrue(c.renames.getValue("fn/theirs").startsWith("$id/row"), c.renames.toString())
        val made = LayoutReader.layout(c.text).ok()
        assertEquals("$id/keys", made.defaults.shortcut)
        assertEquals("$id/top", made.parts.filterIsInstance<Row>().first().defaults.shortcut)
        assertEquals(emptyList(), LayoutCheck.loaded(made, sources, "0.6.1"))
    }

    @Test
    fun theIdItIsGivenIsTheOneMadeAndItsKeysNamedUnder() {
        val c = done(PackLayouts.convert("薄荷", meta, "k18", k17.replace("shortcut=\"g18/q\"", "shortcut=\"mine\""), emptyMap(), sources, "0.6.1", "t1-k18-2"))
        assertEquals("t1-k18-2", c.id)
        assertEquals(mapOf("fn/mine" to "t1-k18-2/r1c1", "g18/h" to "t1-k18-2/hjk"), c.renames)
        assertEquals("t" + PackLayouts.hash("薄荷") + "-k18", PackLayouts.idOf("薄荷", "k18"))
    }

    // a group key of an app name writing other letters than that key's (0.6.0's HJK on g18/h) is a key of its own
    @Test
    fun aKeyOfTheAppsNameWithLettersOfItsOwnIsAKeyOfItsOwn() {
        val c = done(convert(k17))
        val read = LayoutReader.layout(c.text).ok()
        val keys = read.parts.filterIsInstance<Row>().flatMap { it.cells }.filterIsInstance<Key>().associateBy { it.id }
        assertEquals("${c.id}/hjk", keys.getValue("r2c4").attrs.shortcut)
        assertEquals("g18/sd", keys.getValue("r2c2").attrs.shortcut)
        assertEquals("g18/l", keys.getValue("r2c6").attrs.shortcut)
        assertTrue(c.problems.none { it.key.startsWith("held") }, c.problems.toString())
        val nodes = DefaultMarks.ownNodes(read, mapOf("grouped-bottom" to LayoutReader.fragment(testData("grouped-bottom.xml")).ok())) { id ->
            sources.layout(id)?.let { it to LayoutCheck.baseFragments(it, sources) }
        }.toMap()
        val hjk = KeyDefaults.of(KeyId("${c.id}/hjk"), KeyboardVariant(KeyboardVariant.Form.Grouped18), nodes.getValue("${c.id}/hjk"))!!
        assertTrue(listOf("h", "j", "k").all { l -> hjk.items.any { it.text == l } }, hjk.items.toString())
        val same = done(convert(k17.replace("letters=\"hjk\"", "letters=\"h\"")))
        assertTrue("shortcut=\"g18/h\"" in same.text)
        assertEquals(emptyMap(), same.renames)
        val semi = done(convert(k17.replace("letters=\"hjk\"", "letters=\"hjkl\"").replace("letters=\"l;\"", "letters=\";\"")))
        assertEquals(mapOf("g18/h" to "${semi.id}/hjkl", "g18/l" to "${semi.id}/keys"), semi.renames)
    }

    @Test
    fun oneForANewerAppIsNotMade() {
        val newer = k17.replace("<defaults kind", "<meta min-app=\"9.0.0\"/>\n<defaults kind")
        assertIs<PackLayouts.Converted.Not>(convert(newer))
        done(convert(k17.replace("<defaults kind", "<meta min-app=\"0.6.0\"/>\n<defaults kind")))
    }

    // a key of the app's name is the app's key: what it writes held and told, not typed
    @Test
    fun itsNewCommitsAreCounted() {
        assertEquals(emptyList(), done(convert(k17)).commits)
        val own = done(convert(k17.replace("swipe-up=\"keypad:1\"\nshortcut=\"g18/q\"", "swipe-up=\"commit:hello\"\nshortcut=\"fn/mine\"")))
        assertEquals(listOf("hello"), own.commits)
        val held = done(convert(k17.replace("swipe-up=\"keypad:1\"", "swipe-up=\"commit:hello\"")))
        assertEquals(emptyList(), held.commits)
        assertTrue(held.problems.any { it.key == "held" && it.args.first() == "g18/q" }, held.problems.toString())
    }

    // 0.6.0's number board stood for every pad: one without pads= is made for each it serves
    @Test
    fun aNumberBoardWithoutPadsIsMadeForEachPadItServes() {
        val number = testData("number.xml")
        val all = LayoutReader.layout(done(convert(number, id = "number")).text).ok()
        assertEquals(LayoutReader.PADS, all.pads)
        val noPoint = number.replace(Regex("<key [^>]*send=\"(sym:KP_Decimal|\\.)\"[^>]*/>"), "<gap/>")
        assertTrue(noPoint != number)
        val some = LayoutReader.layout(done(convert(noPoint, id = "number")).text).ok().pads.orEmpty()
        assertTrue("phone" in some && "datetime" in some && "full" in some, some.toString())
        assertFalse("decimal" in some || "signed-decimal" in some, some.toString())
        val declared = noPoint.replaceFirst("family=\"number\"", "family=\"number\" pads=\"full decimal\"")
        assertTrue(declared != noPoint)
        assertIs<PackLayouts.Converted.Not>(convert(declared, id = "number"))
        done(convert(declared.replace("pads=\"full decimal\"", "pads=\"full\""), id = "number"))
    }

    @Test
    fun onlyTheBoardsListedAndThoseTakenIn060AreMadeOnes() = assertRows(
        listOf<Pair<String, () -> Unit>>(
            "a board not listed, zhuyin" to { assertIs<PackLayouts.Converted.Not>(convert(testData("zhuyin.xml"), id = "zhuyin")) },
            "a file that does not read" to { assertIs<PackLayouts.Converted.Not>(convert("<keyboard", id = "k18")) },
            "a file of another id is not the pack's board" to { assertIs<PackLayouts.Converted.Not>(convert(k17, id = "k14")) },
            "what the app's must have to be typed on missing: as 0.6.0 refused it" to {
                assertIs<PackLayouts.Converted.Not>(convert(k17.replace(Regex("<key id=\"backspace\"[^>]*/>"), ""), id = "k18"))
            },
        ) + KeyData.LISTED.map<String, Pair<String, () -> Unit>> { id -> "the app's $id, listed" to { done(convert(testData("$id.xml"), id = id)) } },
        { it.first },
    ) { it.second() }

    @Test
    fun oneThe060CheckTakesIsMadeOneWithoutTheStrictChecksWords() {
        val text = k17.replace(Regex("<key id=\"r3c1\"[^>]*/>"), "<gap/>")
        val c = done(convert(text))
        assertFalse(LayoutCheck.check(c.text, sources, "0.6.1", strict = true).ok)
        assertTrue(c.problems.isNotEmpty())
        assertTrue(c.problems.none { it.key == "gap-look" || it.key == "bottom-left" }, c.problems.toString())
    }

    @Test
    fun theNameIsWithinTheLimitAndShowsNothingUnseen() {
        val long = done(convert(k17, theme = "一个名字非常非常非常非常非常长的主题‮"))
        assertTrue(long.name.length <= LayoutCheck.MAX_NAME)
        assertTrue(long.name.endsWith(" · 17 键"), long.name)
        assertTrue(long.name.none(LayoutCheck::hidden))
        assertEquals("17 键", long.own)
        val bare = done(convert(k17, theme = "", m = null))
        assertEquals("17 键", bare.name)
        assertEquals(null, LayoutReader.layout(bare.text).ok().meta)
    }
}
