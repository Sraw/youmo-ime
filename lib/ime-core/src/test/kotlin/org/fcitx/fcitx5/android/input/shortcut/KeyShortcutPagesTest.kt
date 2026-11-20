/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.scene.SceneData
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.UserList
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyShortcutPagesTest {

    @Test
    fun eachPageHasItsKeyboardsKeysNamedAsTheKeyboardNamesThem() {
        assertEquals(listOf(10, 9, 7), KeyShortcutPages.Full.rows.map { it.size })
        assertEquals("full/a", KeyShortcutPages.Full.rows[1][0].name)
        assertEquals(listOf("t9/1", "t9/2", "t9/3"), KeyShortcutPages.Nine.rows[0].map { it.name })
        assertEquals(listOf(5, 5, 4), KeyShortcutPages.Grouped14.rows.map { it.size })
        assertEquals("g14/cv", KeyShortcutPages.Grouped14.rows[2][1].name)
        assertEquals(listOf(7, 6, 5), KeyShortcutPages.Grouped18.rows.map { it.size })
        assertEquals("the ; left off, as the keyboard's", "g18/l", KeyShortcutPages.Grouped18.rows[1].last().name)
        assertEquals("num/0", KeyShortcutPages.Number.rows.last().single().name)
    }

    // a layout of the user's in use: its tab made from it, the keys it shares with the app's marked
    @Test
    fun aLayoutsTabIsMadeFromItsKeys() {
        val text = SceneData.read("k18.xml").replaceFirst("id=\"k18\"", "id=\"my-k17\" based-on=\"k18\"")
            .replace(Regex("<key id=\"r2c4\"[^>]*/>\\s*<key id=\"r2c5\"[^>]*/>"), "<key id=\"hjk\" label=\"HJK\" letters=\"hjk\" shortcut=\"my-k17/hjk\"/>")
            .replace("</keyboard>", "<row><key id=\"ha\" label=\"哈\" send=\"commit:哈\" shortcut=\"my-k17/ha\"/><key id=\"x\" icon=\"emoji\" a11y=\"a11y_key_emoji\" send=\"commit:x\" shortcut=\"my-k17/x\"/>" +
                "<key id=\"q2\" label=\"q\" send=\"q\" shortcut=\"g18/q\"/><key id=\"sp\" role=\"space\" shortcut=\"space\"/></row>\n</keyboard>")
        val layout = LayoutReader.layout(text).let { it as? Parsed.Ok ?: error(it.toString()) }.value
        val bottom = (LayoutReader.fragment(SceneData.read("grouped-bottom.xml")) as Parsed.Ok).value
        val page = KeyShortcutPages.of("my-k17", "我的 17 键", layout, mapOf("grouped-bottom" to bottom))
        assertEquals("layout:my-k17", page.form)
        assertEquals("my-k17", page.layout)
        assertEquals("我的 17 键", page.title)
        assertNull(KeyShortcutPages.Grouped18.layout)
        // function keys left to their tab; a name met again (g18/q) once; the bottom row's none of a character's
        assertEquals(listOf(7, 5, 5, 2), page.rows.map { it.size })
        assertEquals(listOf("A", "SD", "FG", "HJK", "L"), page.rows[1].map { it.label })
        assertEquals(listOf("my-k17/ha", "my-k17/x"), page.rows[3].map { it.name })
        assertEquals("an icon's key: its name's key id", listOf("哈", "x"), page.rows[3].map { it.label })
        // the 18 keys' WE: 18 键's, shared; its own HJK: none
        val pages = KeyShortcutPages.all(listOf(page))
        assertEquals(listOf("full", "t9", "g14", "g18", "zy9", "zyfull", "num", "layout:my-k17", "fn"), pages.map { it.form })
        assertEquals(KeyShortcutPages.Grouped18, KeyShortcutPages.sharedWith(page, "g18/we", pages))
        assertNull(KeyShortcutPages.sharedWith(page, "my-k17/hjk", pages))
        // another layout's with the same name: that layout's tab
        val other = page.copy(form = "layout:another", title = "另一块")
        assertEquals(other, KeyShortcutPages.sharedWith(page, "my-k17/hjk", KeyShortcutPages.all(listOf(page, other))))
        assertEquals(page, KeyShortcutPages.sharedWith(KeyShortcutPages.Grouped18, "g18/we", pages))
        // a group key of the app's name drawn with letters of its own: labelled by them, not as 18 键's tab has it
        val drawn = KeyShortcutPages.of("my-k17", "我的 17 键", LayoutReader.layout(text.replace("letters=\"we\"", "letters=\"ew\"").replace("label=\"{group:we}\"", "label=\"{group:ew}\""))
            .let { it as? Parsed.Ok ?: error(it.toString()) }.value, mapOf("grouped-bottom" to bottom))
        assertEquals(KeyShortcutPages.Key("EW", "g18/we"), drawn.rows[0].single { it.name == "g18/we" })
        assertEquals("WE", page.rows[0].single { it.name == "g18/we" }.label)
    }

    @Test
    fun zhuyinKeysAreNamedByTheirQwertyKey() {
        val first = KeyShortcutPages.ZhuyinFull.rows.map { it.first() }
        assertEquals(listOf("ㄅ", "ㄆ", "ㄇ", "ㄈ"), first.map { it.label })
        assertEquals(listOf("zyfull/1", "zyfull/q", "zyfull/a", "zyfull/z"), first.map { it.name })
        assertEquals(listOf(11, 10, 10, 10), KeyShortcutPages.ZhuyinFull.rows.map { it.size })
        assertEquals("ㄤ on ;", "zyfull/;", KeyShortcutPages.ZhuyinFull.rows[2].last().name)
    }

    @Test
    @Uat("T9-61")
    fun zhuyinNinesGroupsAreNamedByTheirPlaceThreeARow() {
        val page = KeyShortcutPages.ZhuyinNine
        // a tone first, then the groups, ？ ！ ～ last, the ， in place (the first row's backspace left off)
        assertEquals(listOf(4, 5, 5, 5), page.rows.map { it.size })
        assertEquals(
            listOf("zy9/tone-1") + (1..3).map { "zy9/$it" } + listOf("zy9/tone-2") + (4..6).map { "zy9/$it" } + "zy9/question" +
                "zy9/tone-3" + (7..9).map { "zy9/$it" } + "zy9/exclam" + "zy9/tone-4" + listOf("zy9/10", "zy9/11", "zy9/tilde"),
            page.names,
        )
        assertEquals(listOf("ˉ", "ㄅㄉㄚ", "ㄍㄐㄞ", "ㄓㄗㄢㄦ"), page.rows.first().map { it.label })
        assertEquals(listOf("ˋ", "ㄈㄌㄝ", "ㄖㄥㄩ", "，", "～"), page.rows.last().map { it.label })
        // the ， is the function key's, edited on its tab
        assertEquals(listOf("fn/comma"), page.rows.flatten().filter(page::elsewhere).map { it.name })
        // no preset: a group's symbols are no letters
        assertTrue(page.names.all { KeyShortcutPreset.of(it) == KeyShortcut.None })
        // its tab before 大千's
        assertEquals(KeyShortcutPages.all.indexOf(KeyShortcutPages.ZhuyinFull) - 1, KeyShortcutPages.all.indexOf(page))
    }

    @Test
    fun zhuyinNinesShortRowIsLeft() {
        assertEquals(listOf(KeyShortcutPages.ZhuyinNine), KeyShortcutPages.all.filter { it.leftAligned })
    }

    @Test
    fun aFunctionKeyHasItsWayUpThenDown() {
        val space = KeyShortcutPages.Function.rows.first()
        assertEquals(listOf("fn/space.up", "fn/space.down"), space.map { it.name })
        assertTrue(KeyShortcutPages.all.all { page -> page.names.all { KeyShortcuts.formOf(it) == page.form } })
        assertEquals(KeyShortcutPages.all.size, KeyShortcutPages.all.map { it.form }.toSet().size)
    }

    // the count on a keyboard's card: the keys its tab outlines
    @Test
    fun aPagesEditedKeysAreItsSlotsSetAndItsListsOfTheUsers() {
        val pages = KeyShortcutPages
        val none = SwipeSlots.Default
        assertTrue(pages.all.all { pages.edited(it, none, KeyLists.Empty) == 0 })
        // q's two ways one key; a's long press another; 九键's 5 is another page's
        val slots = none.with("full/q", true, SwipeSlot.Empty).with("full/q", false, SwipeSlot.Text("x"))
        val lists = KeyLists.of(mapOf(KeyId("full/a") to UserList.Off, KeyId("t9/5") to UserList.Off))
        assertEquals(2, pages.edited(pages.Full, slots, lists))
        assertEquals(1, pages.edited(pages.Nine, slots, lists))
        assertEquals(0, pages.edited(pages.Grouped14, slots, lists))
        // the same key set and listed counts once
        assertEquals(1, pages.edited(pages.Full, slots, KeyLists.of(mapOf(KeyId("full/q") to UserList.Off))))
    }

    @Test
    fun aFunctionKeyCountsOnceForItsWaysAndItsListAndNotOnAnotherTab() {
        val pages = KeyShortcutPages
        val space = SwipeSlots.functionKey("space")
        val slots = SwipeSlots.Default.with(space, true, SwipeSlot.Empty).with(space, false, SwipeSlot.Empty)
        // ，'s long press: the function keys' tab's, though 注音九键 draws it in place
        val lists = KeyLists.of(mapOf(KeyId.function("comma") to UserList.Off, KeyId.function("space") to UserList.Off))
        assertEquals(2, pages.edited(pages.Function, slots, lists))
        assertEquals(0, pages.edited(pages.ZhuyinNine, slots, lists))
        // a list stored for a key whose long press is an action is none of the page's
        assertEquals(1, pages.edited(pages.Function, SwipeSlots.Default, lists))
    }
}
