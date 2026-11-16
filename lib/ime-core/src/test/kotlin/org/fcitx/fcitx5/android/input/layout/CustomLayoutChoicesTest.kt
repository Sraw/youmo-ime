/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.layout

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// a layout of the user's read to be drawn (061-custom-layout L3): what the keyboard options make of it
class CustomLayoutChoicesTest {

    private val dir = File(CustomLayoutFilesTest.LAYOUTS)
    private val boards = dir.listFiles()!!.filter { it.name.endsWith(".xml") }.associate { it.name.removeSuffix(".xml") to it.readText() }
    private val sources = LayoutCheck.Sources(boards - "grouped-bottom", boards.filterKeys { it == "grouped-bottom" })

    private fun own(id: String) = boards.getValue(id).replaceFirst("id=\"$id\"", "id=\"my-$id\" based-on=\"$id\"")

    private fun read(text: String) = CustomLayoutChoices.of(LayoutImport.layout(text)!!, sources, "0.6.1", "1-2")

    @Test
    fun theAppsBoardsMadeOnesOwnAreDrawnAsWhatTheyAre() {
        val qwerty = read(own("qwerty")).choice
        assertTrue(qwerty.drawn)
        assertEquals("letters", qwerty.family)
        assertEquals("qwerty", qwerty.basedOn)
        assertEquals("1-2", qwerty.stamp)
        // the full keyboard's ; and * for a table: a table may use it (Q6)
        assertTrue(qwerty.tableExtras)
        val k18 = read(own("k18")).choice
        assertEquals(NineKeyLetters.G18.tag().split(','), k18.groups)
        assertEquals(ImKeyboard.KEYS18, ImKeyboards.groupedAs(k18))
        assertEquals(ImKeyboard.KEYS14, ImKeyboards.groupedAs(read(own("k14")).choice))
        assertEquals(ImKeyboard.NINE_KEYS, ImKeyboards.baseOf(read(own("nine")).choice))
    }

    @Test
    fun oneWithoutATablesKeysIsNoTables() {
        val bare = own("qwerty").replace(Regex("<key id=\"star\"[^>]*/>"), "")
        assertFalse(read(bare).choice.tableExtras)
    }

    @Test
    fun oneThatFailsWhatItIsHeldToIsNotDrawnItsBaseKnown() {
        // a direction override typed: refused as it is read, though it was let in (an older build's, a restore's)
        val r = read(own("k18").replaceFirst("<row", "<row><key id=\"rlo\" label=\"x\" send=\"commit:&#x202E;\" shortcut=\"my-k18/rlo\"/></row>\n  <row"))
        assertFalse(r.choice.drawn)
        assertEquals(listOf("hidden-chars"), r.problems.map { it.key })
        assertEquals(ImKeyboard.KEYS18, ImKeyboards.baseOf(r.choice))
        assertEquals(ImKeyboard.KEYS18, ImKeyboards.effective(InputMethodCatalog.PINYIN, ImKeyboard.CUSTOM, ShuangpinScheme.ZIRANMA, r.choice))
    }
}
