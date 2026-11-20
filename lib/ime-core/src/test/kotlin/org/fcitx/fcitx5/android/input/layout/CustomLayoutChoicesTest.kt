/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.layout

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.scene.SceneGroups
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// a user layout read to be drawn: what the keyboard options make of it
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
        // the full keyboard's ; and * for a table: a table may use it
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

    // only boards lacking keys: the import's strict check, the drawing, a restore and the grouped letters take them alike
    @Test
    fun aBoardLackingKeysIsTakenAlikeOnTheFourPaths() {
        class Row(val why: String, val id: String, val text: String)
        assertRows(
            listOf(
                Row("letters without ⌫", "qwerty", own("qwerty").replace(Regex("<key id=\"backspace\"[^>]*?/>"), "")),
                Row("letters without q", "qwerty", own("qwerty").replace(Regex("<key id=\"q\"[^>]*>[\\s\\S]*?</key>"), "")),
                Row("nine without 0", "nine", own("nine").replace(Regex("<key id=\"0\"[^>]*/>"), "<gap width=\"18\"/>")),
                Row("grouped without w and e", "k18", own("k18").replace(Regex("<key id=\"r1c2\"[^>]*/>"), "<gap/>")),
                Row("the phone pad without 5", "number", own("number").replaceFirst("based-on=\"number\"", "based-on=\"number\" pads=\"phone\"").replace("send=\"sym:KP_5\"", "send=\"commit:x\"")),
                Row("symbols without the grid", "symbols", own("symbols").replace(Regex("<slot id=\"grid\"[^>]*/>"), "<gap width=\"82\"/>")),
            ),
            { it.why },
        ) { row ->
            check(row.text != own(row.id))
            val r = LayoutCheck.check(row.text, sources, "0.6.3")
            assertTrue(r.errors.toString(), r.ok)
            assertTrue(r.warnings.toString(), r.warnings.any { it.key == "lacks" })
            assertTrue(read(row.text).choice.drawn)
            assertTrue(CustomLayoutFiles.restorable("my-${row.id}.xml", row.text.toByteArray(), sources, "0.6.3"))
            if (row.id == "k18") {
                val layout = LayoutImport.layout(row.text)!!
                assertTrue(SceneGroups.taken(layout, NineKeyLetters.G18, own = true) is SceneGroups.Taken.Own)
            }
        }
    }

    @Test
    fun aNumberBoardIsForThePadsItNamesAndDrawnThere() {
        // 123's alone where it names none; the pads it names, each held to its keys
        val full = read(own("number")).choice
        assertTrue(full.drawn)
        assertEquals("number", full.family)
        assertEquals(listOf("full"), full.pads)
        val two = read(own("number").replaceFirst("based-on=\"number\"", "based-on=\"number\" pads=\"full phone\"")).choice
        assertEquals(listOf("full", "phone"), two.pads)
        assertTrue(two.drawn)
        // the symbols' none
        val symbols = read(own("symbols")).choice
        assertEquals("symbols", symbols.family)
        assertEquals(emptyList<String>(), symbols.pads)
        assertTrue(symbols.drawn)
    }
}
