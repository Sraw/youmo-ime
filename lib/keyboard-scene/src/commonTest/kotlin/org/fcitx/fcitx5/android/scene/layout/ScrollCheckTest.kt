/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

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

    // the column shows each pad's own marks, so a pad's board with one is not told it lacks them
    @Test
    fun aScrollColumnHoldsWhatAPadLacks() {
        val every = own("number").replaceFirst("family=\"number\"", "family=\"number\" pads=\"${LayoutReader.PADS.joinToString(" ")}\"")
        assertTrue(check(every).warnings.none { it.key == "pad-lacks" || it.key == "scroll-tight" || it.key == "bottom-left" || it.key == "narrow" }, check(every).warnings.toString())
        val bare = check(every.replace(Regex("<slot id=\"side\"[^>]*/>"), "")).warnings.filter { it.key == "pad-lacks" }.map { it.args[0] }
        assertEquals(listOf("full", "phone", "datetime"), bare)
    }

    // a column's items under 20 dp along it: told once, at its line
    @Test
    fun aScrollColumnTooTightIsTold() {
        val board = own("number")
        val line = board.lines().indexOfFirst { "<slot id=\"side\"" in it } + 1
        fun tight(show: String, width: String) = check(board.replace("kind=\"scroll\" list=\"number\" width=\"16\"", "kind=\"scroll\" list=\"number\" direction=\"horizontal\" show=\"$show\" width=\"$width\""))
            .warnings.filter { it.key == "scroll-tight" }
        assertEquals(listOf(LayoutCheck.Finding(line, "scroll-tight")), tight("8", "16"))
        // the narrowest screen is 344 dp: 23 % in four is 19.8 dp an item, 24 % 20.6
        assertEquals(listOf(LayoutCheck.Finding(line, "scroll-tight")), tight("4", "23"))
        assertEquals(emptyList(), tight("4", "24"))
        assertTrue(check(board).warnings.none { it.key == "scroll-tight" })
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
