/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Uat
import org.fcitx.fcitx5.android.scene.testData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// LayoutCheck's split-scaled (ScaledRows, L8), and the boards a .theme's layouts are offered of; apart from LayoutCheckTest
// as its size allows (detekt's LargeClass), on the same boards
class ScaledRowsTest {
    private val boards = listOf(
        "emoji-search", "emoji", "emoticon", "expanded-side", "k14", "k18", "nine", "number-row", "number",
        "qwerty", "symbols", "zhuyin-nine", "zhuyin",
    )
    private val sources = LayoutCheck.Sources(boards.associateWith { testData("$it.xml") }, mapOf("grouped-bottom" to testData("grouped-bottom.xml")))
    private val qwerty = testData("qwerty.xml").replaceFirst("id=\"qwerty\"", "id=\"my-qwerty\" based-on=\"qwerty\"")

    private fun check(text: String, strict: Boolean = true) = LayoutCheck.check(text, sources, "0.6.1", strict = strict)

    // L8: a split="halves" row of other than 9 or 10 keys to halve, which the engine scales when split, is told (once,
    // with the keys it has and where); a split="after" key is not one to halve, a board not split never is
    @Test
    @Uat("ST-180")
    fun aSplitRowThatCannotBeHalvedIsTold() {
        assertTrue(check(qwerty).warnings.none { it.key == "split-scaled" }, check(qwerty).warnings.toString())
        // Q and W after the halves: eight keys to halve in the first row
        val eight = qwerty.replace("<key id=\"q\" ", "<key id=\"q\" split=\"after\" ").replace("<key id=\"w\" ", "<key id=\"w\" split=\"after\" ")
        val told = check(eight).warnings.filter { it.key == "split-scaled" }
        assertEquals(1, told.size, told.toString())
        assertEquals("8", told.single().args[0])
        assertEquals(eight.lines().indexOfFirst { "<row split=\"halves\">" in it } + 1, told.single().line)
        assertEquals(emptyList(), check(eight).errors, "scaled: a warning, not an error")
        // a key more in the first row: split="after", not one to halve; only where 仓颉 has its * (table-extra=star), told there
        val p = "<key id=\"p\" label=\"{case:p}\" send=\"p\" shortcut=\"full/p\"><variant when=\"roots\" label=\"{root:p}\"/></key>"
        val after = qwerty.replace(p, "$p\n    <key id=\"x1\" label=\"1\" send=\"1\" split=\"after\" shortcut=\"my-qwerty/x1\"/>")
        assertTrue(check(after).warnings.none { it.key == "split-scaled" }, check(after).warnings.toString())
        val star = check(qwerty.replace(p, "$p\n    <key id=\"x1\" label=\"1\" send=\"1\" when=\"table-extra=star\" shortcut=\"my-qwerty/x1\"/>"))
            .warnings.filter { it.key == "split-scaled" }
        assertEquals(listOf("11"), star.map { it.args[0] }, star.toString())
        assertTrue("table(star)" in star.single().args[1], star.toString())
        assertTrue(check(eight.replace(" on-split=\"split\"", "")).warnings.none { it.key == "split-scaled" }, "a board not split: no row scaled")
        assertTrue(check(eight, strict = false).warnings.any { it.key == "split-scaled" }, "told of a theme pack's board too")
        // a row in a fragment it includes (eight digits): told at the <include>, not at the fragment's own line (review N1)
        val digits = (1..8).joinToString("") { "<key id=\"d$it\" label=\"$it\" send=\"$it\" shortcut=\"my-qwerty/d$it\"/>" }
        val withDigits = LayoutCheck.Sources(
            sources.layouts,
            sources.fragments + ("digits" to "<fragment xmlns=\"https://youmo-ime/layout/1\" id=\"digits\">\n<row split=\"halves\">$digits</row>\n</fragment>"),
        )
        val included = qwerty.replaceFirst("<row split=\"halves\">", "<include src=\"digits\"/>\n  <row split=\"halves\">")
        val r = LayoutCheck.check(included, withDigits, "0.6.1")
        assertEquals(emptyList(), r.errors)
        val atInclude = r.warnings.filter { it.key == "split-scaled" }
        assertEquals(listOf(included.lines().indexOfFirst { "digits" in it } + 1), atInclude.map { it.line }, atInclude.toString())
    }
}
