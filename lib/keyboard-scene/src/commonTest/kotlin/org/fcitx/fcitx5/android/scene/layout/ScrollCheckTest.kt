/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.skin.assertRows
import org.fcitx.fcitx5.android.scene.testData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// the scroll column as the checker holds it, and the number board it took the marks keys of
class ScrollCheckTest {
    private val boards = listOf(
        "emoji-search", "emoji", "emoticon", "expanded-side", "k14", "k18", "nine", "number-row", "number",
        "qwerty", "symbols", "zhuyin-nine", "zhuyin",
    )
    private val sources = LayoutCheck.Sources(boards.associateWith { testData("$it.xml") }, mapOf("grouped-bottom" to testData("grouped-bottom.xml")))

    private fun own(id: String) = testData("$id.xml").replaceFirst("id=\"$id\"", "id=\"my-$id\" based-on=\"$id\"")

    private fun check(text: String, strict: Boolean = true) = LayoutCheck.check(text, sources, "0.6.1", strict = strict)

    // a number board on every pad
    private val every = own("number").replaceFirst("family=\"number\"", "family=\"number\" pads=\"${LayoutReader.PADS.joinToString(" ")}\"")

    // a board made from 0.6.2's number board, its + - * / and the rest where the scroll column now is: as it was
    @Test
    fun aBoardMadeFrom062sNumberBoardIsCheckedAsThen() {
        for (strict in listOf(true, false)) {
            val r = check(number062, strict)
            assertEquals(emptyList(), r.errors, "strict $strict")
            assertEquals(emptyList(), r.warnings, "strict $strict")
            assertEquals(emptyList(), r.commits, "strict $strict")
            assertEquals("0.6.1", r.minApp, "strict $strict: based-on= is all it asks")
        }
    }

    // what a pad lacks, its keys and the column's list there by default counted: the full pad shows its list=, the dialler's
    // and the date's their own
    @Test
    fun aScrollColumnHoldsWhatAPadLacks() {
        assertTrue(check(every).warnings.none { it.key == "scroll-tight" || it.key == "bottom-left" || it.key == "narrow" }, check(every).warnings.toString())
        assertRows(
            listOf(
                Triple("the app's column: each pad's marks in it", every, emptyList()),
                Triple("no column: each pad lacks its marks", every.replace(Regex("<slot id=\"side\"[^>]*/>"), ""), listOf(listOf("full", "-"), listOf("phone", "+ * #"), listOf("datetime", "- / :"))),
                Triple("a nine column: that list has no -", every.replace("kind=\"scroll\" list=\"number\"", "kind=\"scroll\" list=\"nine\""), listOf(listOf("full", "-"))),
                Triple("the number column and no . key: that list has no .", every.replace(Regex("<key id=\"\\.\"[^>]*/>"), "<gap width=\"12\" when=\"pad=full|datetime\"/>"), listOf(listOf("full", "."))),
                Triple("ST-225's file: a nine column on every pad", testData("../../../../../uat/fixtures/layouts/uat-number-nine-column.layout"), listOf(listOf("full", "-"))),
            ),
            { it.first },
        ) { (_, board, lacks) -> assertEquals(lacks, check(board).warnings.filter { it.key == "pad-lacks" }.map { it.args }) }
    }

    // a column's items under 20 dp along it: told once, at its line
    @Test
    fun aScrollColumnTooTightIsTold() {
        val board = own("number")
        fun tight(show: String, width: String) = board.replace("kind=\"scroll\" list=\"number\" width=\"16\"", "kind=\"scroll\" list=\"number\" direction=\"horizontal\" show=\"$show\" width=\"$width\"")
        val second = board.replace("</keyboard>", "<row>\n<slot kind=\"scroll\" list=\"number\" direction=\"horizontal\" show=\"8\" width=\"16\"/><gap width=\"fill\"/></row>\n</keyboard>")
        assertRows(
            listOf(
                Triple("eight in 16 %", tight("8", "16"), "<slot id=\"side\""),
                // the narrowest screen is 344 dp: 23 % in four is 19.8 dp an item, 24 % 20.6
                Triple("four in 23 %", tight("4", "23"), "<slot id=\"side\""),
                Triple("four in 24 %", tight("4", "24"), null),
                Triple("the app's column", board, null),
                Triple("two columns: each told at its own line", second, "show=\"8\""),
            ),
            { it.first },
        ) { (_, text, at) ->
            val told = at?.let { mark -> LayoutCheck.Finding(text.lines().indexOfFirst { mark in it } + 1, "scroll-tight") }
            assertEquals(listOfNotNull(told), check(text).warnings.filter { it.key == "scroll-tight" })
        }
    }

    // the nine keys' side column is the nine-side slot: a scroll column of the same list does not stand for it
    @Test
    fun aScrollColumnIsNotTheNineKeysSideColumn() {
        val nine = own("nine").replace("kind=\"nine-side\"", "kind=\"scroll\" list=\"nine\"")
        val r = check(nine)
        assertEquals(emptyList(), r.errors)
        assertTrue(r.warnings.any { it.key == "lacks" && it.args[0] == "nine-side" }, r.warnings.toString())
        assertEquals("0.6.3", r.minApp)
    }

    // ST-221's file: the app's 26 keys with a column above the bottom row, imported as it is
    @Test
    fun theUatScrollLayoutIsTaken() {
        val r = LayoutCheck.check(testData("../../../../../uat/fixtures/layouts/uat-qwerty-scroll.layout"), sources, "0.6.3")
        assertEquals(emptyList(), r.errors)
        assertEquals("0.6.3", r.minApp)
        assertTrue(r.warnings.none { it.key == "scroll-tight" || it.key == "lacks" }, r.warnings.toString())
    }

    companion object {
        // the number board as 0.6.2 shipped it, its keys of no name in a row of their own: one made from it is one's own
        val number062: String by lazy { testData("layout-check/number-062.xml").replaceFirst("id=\"number\"", "id=\"my-number\" based-on=\"number\"") }
    }
}
