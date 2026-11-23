/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.scene.keys.ScrollDefaults
import org.fcitx.fcitx5.android.scene.keys.ScrollItem
import org.fcitx.fcitx5.android.scene.keys.ScrollItem.Command
import org.fcitx.fcitx5.android.scene.keys.ScrollItem.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// contract: scroll_lists' stored @1 format, which a backup, a later version and a downgrade read back
class ScrollListStoreTest {
    private fun roundTrip(s: ScrollListStore) = ScrollListStore.parse(s.serialize())

    @Test
    fun nothingStoredIsTheDefaults() {
        for (text in listOf(null, "", "garbage", "@1", "@1\nnumber")) {
            val s = ScrollListStore.parse(text)
            assertEquals("$text", ScrollDefaults.of("number"), s["number"])
            assertFalse("$text", s.newer)
        }
        assertEquals("@1", ScrollListStore.Empty.serialize())
    }

    @Test
    fun eachKindGoesAndComesBack() {
        val items = listOf(Text("#"), Text(",", asIs = true), Command("switch_input_method"), Text("a\tb\\c"))
        val s = roundTrip(ScrollListStore.Empty.with("nine", items))
        assertEquals(items, s["nine"])
        assertTrue(s.isSet("nine"))
        assertFalse(s.isSet("url"))
    }

    @Test
    fun aTextAndACommandOfOneNameAreTwo() {
        val s = ScrollListStore.Empty.with("number", listOf(Text("copy"), Command("copy"), Text("copy"), Text(" "), Text("+")))
        assertEquals(listOf(Text("copy"), Command("copy"), Text("+")), roundTrip(s)["number"])
    }

    @Test
    fun aTextIsClippedACommandNeverTwentyFourAtMost() {
        val long = "一二三四五六七八九十一二三四五六七"
        val s = ScrollListStore.Empty.with("url", listOf(Text(long)) + (1..30).map { Text("$it") })
        assertEquals(ScrollListStore.MAX_ITEMS, s["url"].size)
        assertEquals(long.take(KeyLists.MAX_ITEM), (s["url"].first() as Text).text)
    }

    @Test
    fun aCommandNotOfferedIsHiddenAndWrittenBack() {
        val s = ScrollListStore.parse("@1\nnumber\tt|+\tc|later_command\tc|stroke_h\tc|copy")
        assertEquals(listOf(Text("+"), Command("copy")), s["number"])
        val changed = s.with("number", listOf(Command("copy"), Text("-")))
        assertEquals(listOf(Text("-"), Command("copy")), changed.toFront("number", 1)["number"])
        assertEquals(listOf(Command("copy"), Text("-"), Command("later_command"), Command("stroke_h")), roundTrip(changed).stored("number"))
    }

    // a full list saved over a later version's: its commands still written back, not cut by the limit
    @Test
    fun aFullListKeepsTheCommandsNotOffered() {
        val s = ScrollListStore.parse("@1\nnumber\tc|later_a\t" + (1..18).joinToString("\t") { "t|$it" } + "\tc|later_b")
        val full = (1..ScrollListStore.MAX_ITEMS).map { Text("$it") }
        val changed = roundTrip(s.with("number", full))
        assertEquals(full, changed["number"])
        assertEquals(full + Command("later_a") + Command("later_b"), changed.stored("number"))
    }

    // nothing of it offered here: the default shown, the stored kept to write back
    @Test
    fun aListOfOnlyCommandsNotOfferedShowsTheDefault() {
        val s = ScrollListStore.parse("@1\nnumber\tc|later_command")
        assertEquals(ScrollDefaults.of("number"), s["number"])
        assertEquals(listOf(Command("later_command")), s.stored("number"))
        assertEquals(ScrollDefaults.of("number")!!.drop(1) + Command("later_command"), roundTrip(s.removed("number", 0)).stored("number"))
        // emptied, as removed() leaves them too
        assertEquals(listOf(Command("later_command")), roundTrip(s.with("number", emptyList())).stored("number"))
    }

    @Test
    fun theDefaultsOrNoneAreNoSetting() {
        val s = ScrollListStore.Empty.with("phone", listOf(Text("#")))
        assertEquals("@1", s.with("phone", ScrollDefaults.of("phone")!!).serialize())
        assertEquals("@1", s.with("phone", emptyList()).serialize())
        assertEquals("@1", s.without(listOf("phone")).serialize())
    }

    @Test
    fun theMenusRemoveAndToFront() {
        val s = ScrollListStore.Empty.removed("datetime", 1)
        assertEquals(listOf(Text("-"), Text(":")), s["datetime"])
        assertEquals(listOf(Text(":"), Text("-")), s.toFront("datetime", 1)["datetime"])
    }

    @Test
    fun aLaterVersionsIsReadAsTheDefaultsAndKept() = assertRows(
        "a later version's, a kind this one does not know" to { laterKept("@2\nnumber\tx|+") },
        "a version past an Int is still a later one" to { laterKept("@99999999999\nnumber\tt|x") },
    )

    private fun laterKept(text: String) {
        val s = ScrollListStore.parse(text)
        assertTrue(s.newer)
        assertEquals(ScrollDefaults.of("number"), s["number"])
        assertEquals(text, s.with("number", listOf<ScrollItem>(Text("1"))).removed("number", 0).toFront("number", 1).serialize())
    }

    @Test
    fun theEditorKeepsATextAndACommandOfOneNameApart() {
        val items = listOf(Text("copy"), Command("copy"), Text(",", asIs = true), Text("."))
        val (strings, marks) = ScrollListStore.editing(items)
        assertEquals(4, strings.distinct().size)
        assertEquals("copy", ScrollListStore.commandIn(strings[1]))
        assertEquals(null, ScrollListStore.commandIn(strings[0]))
        assertEquals(mapOf("," to ItemMark.AsIs), marks)
        assertEquals(items, ScrollListStore.edited(strings, marks))
        // a variables mark is not a scroll list's
        assertEquals(listOf(Text("x")), ScrollListStore.edited(listOf("x"), mapOf("x" to ItemMark.Variables)))
    }

    @Test
    fun onlyTheNineKeysListsKeepAsIsAndNoStrokeIsOffered() {
        assertEquals(listOf("nine", "nine-english"), ScrollDefaults.IDS.filter(ScrollListStore::keepsAsIs))
        val commands = ScrollListStore.COMMANDS
        assertTrue(commands.containsAll(listOf(KeyCommand.Copy, KeyCommand.SwitchInputMethod, KeyCommand.SelectToStart, KeyCommand.SelectToEnd)))
        assertTrue(commands.none { it.group == KeyCommand.Group.Stroke || it == KeyCommand.SwipeSelect })
    }
}
