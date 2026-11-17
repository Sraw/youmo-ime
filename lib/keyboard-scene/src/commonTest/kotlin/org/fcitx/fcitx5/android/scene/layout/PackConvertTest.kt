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
import org.fcitx.fcitx5.android.scene.theme.Meta
import org.fcitx.fcitx5.android.scene.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// a theme pack's layout of a board the user picks made one of the user's layouts (061-custom-layout §2.3, §11): one
// function for the app's import, upgrade and restore and the layout maker's page, held here alike on the JVM and in JS
// (the same files below for both)
class PackConvertTest {
    private val boards = listOf(
        "emoji-search", "emoji", "emoticon", "expanded-side", "k14", "k18", "nine", "number-row", "number",
        "qwerty", "symbols", "zhuyin-nine", "zhuyin",
    )
    private val sources = LayoutCheck.Sources(boards.associateWith { testData("$it.xml") }, mapOf("grouped-bottom" to testData("grouped-bottom.xml")))
    private val k17 by lazy { testData("../../../../../web/theme-maker/examples/k17-motion/layouts/k18.xml") }
    private val meta = Meta("小明", "1.0", "CC-BY-4.0", null, null)

    private fun convert(text: String, id: String = "k18", theme: String = "薄荷", fragments: Map<String, String> = emptyMap(), m: Meta? = meta) =
        PackLayouts.convert(theme, m, id, text, fragments, sources, "0.6.1")

    private fun done(c: PackLayouts.Converted) = assertIs<PackLayouts.Converted.Done>(c, (c as? PackLayouts.Converted.Not)?.why)

    @Test
    fun theHashIsFnv1aOfTheUtf8() {
        // the published values of FNV-1a 32: the JVM and JS give the same id
        assertEquals("811c9dc5", PackLayouts.hash(""))
        assertEquals("e40c292c", PackLayouts.hash("a"))
        assertEquals(8, PackLayouts.hash("薄荷 17 键").length)
    }

    @Test
    fun theExamplePackMakesTheSameFileEverywhere() {
        val c = done(convert(k17, theme = "薄荷 17 键"))
        assertEquals("t${PackLayouts.hash("薄荷 17 键")}-k18", c.id)
        assertEquals("薄荷 17 键 · 17 键", c.name)
        // its names all the app's: none renamed
        assertEquals(emptyMap(), c.renames)
        val file = "$TEST_DATA/convert/k17-motion.layout"
        if (recording()) writeText(file, c.text)
        assertEquals(readText(file), c.text, "convert/k17-motion.layout: -Psnapshots.record=true")
        val read = LayoutReader.layout(c.text).ok()
        assertEquals("k18", read.basedOn)
        assertEquals(LayoutMeta("小明", null, "CC-BY-4.0", null, null), read.meta)
        // drawn as the user's own are: what the app asks of one as it is drawn holds
        assertEquals(emptyList(), LayoutCheck.loaded(read, sources, "0.6.1"))
        // the same pack again: the same id and text (an update, not one more)
        assertEquals(c.text, done(convert(k17, theme = "薄荷 17 键")).text)
    }

    @Test
    fun thePacksOwnBottomRowIsWrittenIn() {
        val bottom = testData("grouped-bottom.xml").replace("<fragment", "<!-- mine -->\n<fragment")
            .replaceFirst(Regex("label=\"[^\"]*\""), "label=\"PK\"")
        val with = done(convert(k17, fragments = mapOf("grouped-bottom" to bottom)))
        assertFalse("<include" in with.text)
        assertTrue("label=\"PK\"" in with.text)
        // the app's rows read the same as the pack's would: every key of the fragment on the layout
        val keys = LayoutReader.layout(with.text).ok().parts.filterIsInstance<Row>().flatMap { it.cells }.filterIsInstance<Key>().map { it.id }
        val fragmentKeys = LayoutReader.fragment(bottom).ok().rows.flatMap { it.cells }.filterIsInstance<Key>().map { it.id }
        assertTrue(keys.containsAll(fragmentKeys))
        // none of its own: the app's included
        val without = done(convert(k17))
        assertTrue("<include src=\"grouped-bottom\"/>" in without.text)
        // a fragment of another name is not the pack's (the app reads grouped-bottom alone)
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
        // a bare name is fn/'s as read; the two keys sharing one still share it
        assertEquals(mapOf("fn/mine" to "$id/r1c1", "g18/nope" to "$id/r1c3"), c.renames)
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
        assertEquals(mapOf("fn/a" to "${c.id}/r1c1", "fn/b" to "${c.id}/r1c1-2"), c.renames)
    }

    // a <defaults>' name (the layout's, a row's, the pack's fragment's) is every key's under it that names none of its
    // own: renamed as a key's (L7 review S4: kept as it was, the made one was refused by the next start's check)
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
        assertEquals(mapOf("fn/mine" to "t1-k18-2/r1c1"), c.renames)
        assertEquals("t" + PackLayouts.hash("薄荷") + "-k18", PackLayouts.idOf("薄荷", "k18"))
    }

    // a pack's layout's own <meta min-app> newer than the app: not made (L7 review NIT3; the made one writes none)
    @Test
    fun oneForANewerAppIsNotMade() {
        val newer = k17.replace("<defaults kind", "<meta min-app=\"9.0.0\"/>\n<defaults kind")
        assertIs<PackLayouts.Converted.Not>(convert(newer))
        done(convert(k17.replace("<defaults kind", "<meta min-app=\"0.6.0\"/>\n<defaults kind")))
    }

    // what its keys type that the base's do not, counted for the import's confirmation (§13; L7 review S1)
    @Test
    fun itsNewCommitsAreCounted() {
        assertEquals(emptyList(), done(convert(k17)).commits)
        val c = done(convert(k17.replace("swipe-up=\"keypad:1\"", "swipe-up=\"commit:hello\"")))
        assertEquals(listOf("hello"), c.commits)
    }

    // 0.6.0's number board stood for every pad: one without pads= is made for each it serves, so a phone's, a date's and
    // a number's fields keep its look (L7 review S3); a pad it lacks a key of is left out, and one that declares pads is
    // not made where one lacks (as L5's restore, review N1)
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
    fun onlyTheBoardsListedAndThoseTakenIn060AreMadeOnes() {
        assertIs<PackLayouts.Converted.Not>(convert(testData("zhuyin.xml"), id = "zhuyin"))
        assertIs<PackLayouts.Converted.Not>(convert("<keyboard", id = "k18"))
        // a file of another id is not the pack's board
        assertIs<PackLayouts.Converted.Not>(convert(k17, id = "k14"))
        // what the app's must have to be typed on: as 0.6.0 refused it
        assertIs<PackLayouts.Converted.Not>(convert(k17.replace(Regex("<key id=\"backspace\"[^>]*/>"), ""), id = "k18"))
        for (id in KeyData.LISTED) done(convert(testData("$id.xml"), id = id))
    }

    // §3.2's two strengths (Q16): one 0.6.0 took is made one though the strict check refuses it, with no gap or bottom
    // row warning
    @Test
    fun oneThe060CheckTakesIsMadeOneWithoutTheStrictChecksWords() {
        // a group of letters lost: the strict check's error, 0.6.0's not
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
        // the pack's layout's own name, what the app's notices say beside the theme's
        assertEquals("17 键", long.own)
        // no theme name: the layout's alone; no meta: none written
        val bare = done(convert(k17, theme = "", m = null))
        assertEquals("17 键", bare.name)
        assertEquals(null, LayoutReader.layout(bare.text).ok().meta)
    }
}
