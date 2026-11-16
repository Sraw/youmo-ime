/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Fixtures
import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Expand
import org.fcitx.fcitx5.android.scene.engine.ExpandedKey
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.testData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// a pack's qwerty.xml of before 0.6.1 given the app's 分词 in Shift's place while pinyin is typed (FB12,
// dev/design/061-theme-separator-migration.md §2): two places of its text, the rest as it was
class PackSeparatorTest {
    private val v060 = testData("qwerty-060.xml")
    private val bottom = testData("grouped-bottom.xml")
    private val appFragments = mapOf("grouped-bottom" to LayoutReader.fragment(bottom).ok())

    private fun appText(id: String) = bottom.takeIf { id == "grouped-bottom" }

    private fun rewrite(text: String, own: Map<String, String> = emptyMap()) =
        PackSeparator.addSeparator(text, { own[it] }, ::appText, Fixtures.qwerty, appFragments)

    private fun done(text: String, own: Map<String, String> = emptyMap()): String =
        rewrite(text, own).let { assertIs<PackSeparator.Rewrite.Done>(it, (it as? PackSeparator.Rewrite.Kept)?.why).text }

    private fun kept(text: String, why: String, own: Map<String, String> = emptyMap()) {
        val r = rewrite(text, own)
        assertIs<PackSeparator.Rewrite.Kept>(r, why)
        assertTrue(why in r.why, "${r.why}, not $why")
    }

    private val shift060 = "<key id=\"shift\" role=\"shift\" kind=\"function\" width=\"1.5\" icon=\"{shift-icon}\" a11y=\"a11y_key_shift\" shortcut=\"shift\"\n" +
        "         send=\"caps\" double-tap=\"caps:lock\" when=\"!table\"/>"

    // the 0.6.0 file with its Shift written as [shift]
    private fun withShift(shift: String) = v060.replace(shift060, shift).also { check(it != v060 || shift == shift060) }

    @Test
    fun theFileOf060BecomesTheAppsOwnTwoPlacesChanged() {
        val out = done(v060)
        // read, it is the app's qwerty of now (its comment aside, which the app's has longer)
        assertEquals(LayoutWriter.write(Fixtures.qwerty), LayoutWriter.write(LayoutReader.layout(out).ok()))
        val separator = "<key id=\"apostrophe\" role=\"apostrophe\" kind=\"function\" width=\"1.5\" label=\"分词\" text-size=\"16\" " +
            "a11y=\"a11y_key_separator\" send=\"'\" shortcut=\"separator\" when=\"composing !table\"/>"
        val expected = v060.replace(shift060, shift060.replace("when=\"!table\"", "when=\"!table !composing\"") + "\n    " + separator)
        assertEquals(expected, out)
    }

    @Test
    fun whatShiftAndSeparatorAreWhereShiftHadNoWhen() {
        val shift = "<key id=\"shift\" role=\"shift\" kind=\"function\" width=\"1.5\" icon=\"{shift-icon}\" a11y=\"a11y_key_shift\" send=\"caps\"/>"
        val out = done(withShift(shift))
        assertTrue("<key id=\"shift\" role=\"shift\" kind=\"function\" width=\"1.5\" icon=\"{shift-icon}\" a11y=\"a11y_key_shift\" send=\"caps\" when=\"!composing, table\"/>\n    <key id=\"apostrophe\"" in out, out)
        assertTrue("when=\"composing !table\"/>\n    <key id=\"lookup\"" in out)
    }

    // Shift's when= and 分词's, each group of Shift's, against every value of what they ask: Shift or 分词 where Shift was,
    // never both, 分词 exactly while pinyin is typed (composing, no table)
    @Test
    fun theConditionsSplitWhereShiftWasAndNowhereElse() {
        val whens = listOf(
            null, "!table", "table", "table=wbx", "!table=wbx", "!composing", "composing", "landscape, compact",
            "!table landscape, table=wbx", "composing !table", "!table=wbx compact", "table !composing, landscape",
        )
        val tables = listOf(null, "wbx", "cj")
        for (w in whens) {
            val original = w?.let { Condition.parse(it).ok() }
            val groups = original?.anyOf ?: listOf(emptyList())
            val shift = Condition(PackSeparator.shiftGroups(groups))
            val separator = Condition(PackSeparator.separatorGroups(groups))
            for (table in tables) for (bits in 0 until 8) {
                val flags = mapOf("composing" to (bits and 1 != 0), "landscape" to (bits and 2 != 0), "compact" to (bits and 4 != 0))
                val facts = Condition.Facts { name, values ->
                    if (name == "table") table != null && (values == null || table in values) else flags[name] == true
                }
                val was = original?.holds(facts) ?: true
                val s = shift.anyOf.isNotEmpty() && shift.holds(facts)
                val p = separator.anyOf.isNotEmpty() && separator.holds(facts)
                val at = "when=$w table=$table $flags"
                assertEquals(was, s || p, at)
                assertFalse(s && p, at)
                assertEquals(was && flags["composing"] == true && table == null, p, at)
            }
        }
        // as written: the app's own words
        assertEquals("!table !composing", Condition(PackSeparator.shiftGroups(Condition.parse("!table").ok().anyOf)).toString())
        assertEquals("!table=wbx !composing, !table=wbx table", Condition(PackSeparator.shiftGroups(Condition.parse("!table=wbx").ok().anyOf)).toString())
        assertEquals("!table=wbx composing !table", Condition(PackSeparator.separatorGroups(Condition.parse("!table=wbx").ok().anyOf)).toString())
    }

    @Test
    fun separatorTakesWhereShiftIsAndHowItLooksAsWritten() {
        val shift = "<key id=\"shift\" role=\"shift\" kind=\"function\" width=\"fill\" rows=\"1\" split=\"after\" class=\"big\" face=\"keep\" " +
            "icon=\"{shift-icon}\" a11y=\"a11y_key_shift\" send=\"caps\" when=\"!table\"/>"
        val out = done(withShift(shift))
        val line = out.lines().single { "id=\"apostrophe\"" in it }
        for (a in listOf("kind=\"function\"", "width=\"fill\"", "rows=\"1\"", "split=\"after\"", "class=\"big\"", "face=\"keep\"")) assertTrue(a in line, "$a: $line")
        assertFalse("icon=" in line, line)
        // none written, none taken: the row's defaults are both's
        val bare = done(withShift("<key id=\"shift\" role=\"shift\" icon=\"{shift-icon}\" a11y=\"a11y_key_shift\" send=\"caps\" when=\"!table\"/>"))
        assertFalse("width=" in bare.lines().single { "id=\"apostrophe\"" in it })
    }

    @Test
    fun theTextAroundIsKeptHoweverWritten() {
        // single quotes and space about `=`, a line break in the tag
        val quoted = done(withShift("<key id='shift' role='shift' width='1.5' send='caps'\n         when = '!table'/>"))
        assertTrue("when = '!table !composing'/>\n    <key id=\"apostrophe\"" in quoted, quoted)
        // `>` in a value, children: after its end tag
        val children = done(withShift("<key id=\"shift\" role=\"shift\" width=\"1.5\" label=\"a&gt;b\" send=\"caps\" when=\"!table\"><variant when=\"roots\" label=\">\"/></key>"))
        assertTrue("<variant when=\"roots\" label=\">\"/></key>\n    <key id=\"apostrophe\"" in children, children)
        // no space before `/>`
        val tight = done(withShift("<key id=\"shift\" role=\"shift\" width=\"1.5\" send=\"caps\"/>"))
        assertTrue("send=\"caps\" when=\"!composing, table\"/>" in tight, tight)
        // CRLF, CR, mixed: the break of Shift's line
        assertTrue(done(v060.replace("\n", "\r\n")).let { "\r\n    <key id=\"apostrophe\"" in it && "\n" !in it.replace("\r\n", "") })
        assertTrue(done(v060.replace("\n", "\r")).let { "\r    <key id=\"apostrophe\"" in it && "\n" !in it })
        val mixed = v060.replace(shift060, shift060.replace("\n", "\r\n"))
        assertTrue("\n    <key id=\"apostrophe\"" in done(mixed))
        // one line: a space
        val oneLine = v060.replace(Regex("\\n\\s*"), " ")
        val out = done(oneLine)
        assertTrue("when=\"!table !composing\"/> <key id=\"apostrophe\"" in out, out)
        assertFalse('\n' in out)
    }

    @Test
    fun aRowsConditionIsSeparatorsToo() {
        val text = v060.replace("<row split=\"halves\">\n    <!-- 码表时", "<row split=\"halves\" when=\"!landscape\">\n    <!-- 码表时")
        check(text != v060)
        // the row hidden in landscape: kept, both under it, the app's other rows unchanged
        val out = done(text)
        val from = out.indexOf("when=\"!landscape\">")
        val row = out.substring(from, out.indexOf("</row>", from))
        assertTrue("id=\"shift\"" in row && "id=\"apostrophe\"" in row, row)
        // in landscape neither, typing or not
        val layout = LayoutReader.layout(out).ok()
        for (composing in listOf(false, true)) {
            val env = Env(2340.0, 600.0, 2.625, landscape = true, input = Input(ime = "pinyin", composing = composing))
            val ids = Expand.rows(layout, appFragments, env).ok().flatMap { r -> r.items.mapNotNull { (it as? ExpandedKey)?.key?.id } }
            assertFalse("shift" in ids || "apostrophe" in ids, ids.toString())
        }
    }

    // two Shifts in two rows: whether they show together is their rows' conditions too
    @Test
    fun twoShiftsInRowsThatNeverShowTogetherGetOneEach() {
        val portrait = v060.replace("<row split=\"halves\">\n    <!-- 码表时", "<row split=\"halves\" when=\"!landscape\">\n    <!-- 码表时")
        check(portrait != v060)
        fun withRow(rowWhen: String) =
            portrait.replace("</keyboard>", "  <row when=\"$rowWhen\"><key id=\"shift2\" role=\"shift\" width=\"1.5\" send=\"caps\" when=\"!table\"/></row>\n</keyboard>")
        assertEquals(2, Regex("id=\"apostrophe\"").findAll(done(withRow("landscape"))).count())
        // both rows there in portrait unless compact: both would be 分词 at once
        kept(withRow("!compact"), "two Shifts")
    }

    @Test
    fun twoShiftsGetOneEachWhereTheyNeverShowTogether() {
        val two = withShift(
            "<key id=\"shift\" role=\"shift\" width=\"1.5\" send=\"caps\" when=\"!table !landscape\"/>\n" +
                "    <key id=\"shift\" role=\"shift\" width=\"2\" send=\"caps\" when=\"!table landscape\"/>",
        )
        val out = done(two)
        assertEquals(2, Regex("id=\"apostrophe\"").findAll(out).count())
        assertTrue("width=\"2\" label=\"分词\"" in out)
        // two that show together while typed: both would be 分词 at once, and the other Shift types '
        val both = withShift(
            "<key id=\"shift\" role=\"shift\" width=\"1.5\" send=\"caps\" when=\"!table\"/>\n" +
                "    <key id=\"shift2\" role=\"shift\" width=\"1.5\" send=\"caps\" when=\"!table\"/>",
        )
        kept(both, "two Shifts")
        // the other one never there while typed (table only): one 分词, the other left alone
        val other = withShift(
            "<key id=\"shift\" role=\"shift\" width=\"1.5\" send=\"caps\" when=\"!table\"/>\n" +
                "    <key id=\"shift2\" role=\"shift\" width=\"1.5\" send=\"caps\" when=\"table looking-up\"/>",
        )
        assertEquals(1, Regex("id=\"apostrophe\"").findAll(done(other)).count())
    }

    @Test
    fun aPackWith分词OrWhatShiftCannotGiveWayToIsKept() {
        kept(done(v060), "a key apostrophe")
        kept(withShift(shift060 + "\n    <key id=\"sep\" label=\"'\" send=\"'\" shortcut=\"separator\" when=\"!table\"/>"), "a 分词")
        kept(withShift(shift060 + "\n    <key id=\"sep\" label=\"'\" send=\"'\" when=\"composing\"/>"), "a ' key while typing")
        kept(
            withShift(shift060 + "\n    <key id=\"sep\" label=\"'\" send=\"'\" when=\"!composing\"><variant when=\"composing\" shortcut=\"fn/separator\"/></key>"),
            "a 分词",
        )
        kept(withShift("<key id=\"shift\" role=\"shift\" width=\"1.5\" send=\"caps\" when=\"table\"/>"), "never shows")
        kept(withShift("<key id=\"shift\" role=\"shift\" width=\"1.5\" send=\"caps\" when=\"!table !composing\"/>"), "never shows")
        kept(withShift("<key id=\"shift\" role=\"shift\" width=\"1.5\" send=\"caps\" when=\"composing !table\"/>"), "only while pinyin")
        kept(v060.replace(Regex("<key id=\"shift\"[^>]*>"), ""), "no Shift")
        kept("<keyboard", "does not read")
        kept(v060.replace("family=\"letters\"", "family=\"nine\""), "not drawn")
        kept(v060.replace("</keyboard>", "<!--" + "x".repeat(PackLayouts.MAX_BYTES.toInt()) + "-->\n</keyboard>"), "bytes")
        // a ' key of its own, not while typing: given 分词 all the same
        done(withShift(shift060 + "\n    <key id=\"quote\" label=\"'\" send=\"'\" width=\"0\" when=\"field=never\"/>"))
    }

    @Test
    fun a分词InThePacksOwnFragmentCounts() {
        val including = v060.replace("</keyboard>", "  <include src=\"grouped-bottom\"/>\n</keyboard>")
        val own = bottom.replace("</row>", "<key id=\"sep\" send=\"'\" shortcut=\"separator\" when=\"field=never\"/></row>")
        check(own != bottom)
        kept(including, "a 分词", mapOf("grouped-bottom" to own))
    }

    // a Shift in the pack's own fragment is not in this file: left as it is, not one 分词 beside it
    @Test
    fun aShiftInThePacksOwnFragmentIsKept() {
        val including = v060.replace("</keyboard>", "  <include src=\"grouped-bottom\"/>\n</keyboard>")
        val own = bottom.replace("</row>", "<key id=\"shift2\" role=\"shift\" send=\"caps\" when=\"field=never\"/></row>")
        check(own != bottom)
        kept(including, "a Shift in a fragment", mapOf("grouped-bottom" to own))
    }

    @Test
    fun aKeyNamedShiftBySendAloneIsOne() {
        val out = done(withShift("<key id=\"up\" kind=\"function\" width=\"1.5\" send=\"caps:lock\" when=\"!table\"/>"))
        assertTrue("send=\"caps:lock\" when=\"!table !composing\"/>" in out, out)
    }

    // the check after: a rewrite that would draw another width, or both at once, or 分词 where Shift was not, is not given
    @Test
    fun theCheckAfterFindsWhatWouldBeDrawnOtherwise() {
        val original = LayoutReader.layout(v060).ok()
        val shifts = original.parts.filterIsInstance<Row>().flatMap { it.cells }.filterIsInstance<Key>().filter { it.id == "shift" }
        fun check(text: String) = PackSeparator.checked(text, original, emptyMap(), { null }, ::appText, Fixtures.qwerty, appFragments, shifts)
        val good = done(v060)
        assertIs<PackSeparator.Rewrite.Done>(check(good))
        val wider = good.replace("width=\"1.5\" label=\"分词\"", "width=\"2\" label=\"分词\"")
        val both = good.replace("when=\"!table !composing\"", "when=\"!table\"")
        val always = good.replace("when=\"composing !table\"", "when=\"!table\"")
        for (bad in listOf(wider, both, always)) {
            check(bad != good)
            assertIs<PackSeparator.Rewrite.Kept>(check(bad), bad)
        }
    }
}
