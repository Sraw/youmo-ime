/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.input.keyboard.KeySwipe
import org.fcitx.fcitx5.android.input.scene.SceneData
import org.fcitx.fcitx5.android.input.shortcut.SwipeOrientation
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.keys.Commit
import org.fcitx.fcitx5.android.scene.keys.DefaultMarks
import org.fcitx.fcitx5.android.scene.keys.Item
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant.Form
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyResolverTest {
    private fun layout(name: String): Layout = (LayoutReader.layout(SceneData.read("$name.xml")) as Parsed.Ok).value

    private val fragments: Map<String, Fragment> by lazy { mapOf("grouped-bottom" to (LayoutReader.fragment(SceneData.read("grouped-bottom.xml")) as Parsed.Ok).value) }

    private fun resolver(board: String, form: Form, lists: KeyLists = KeyLists.Empty, slots: SwipeSlots = SwipeSlots.Default, language: KeyboardVariant.Language = KeyboardVariant.Language.Pinyin) =
        KeyResolver(KeyboardVariant(form, language), lists, slots, DefaultMarks.nodes(layout(board), fragments))

    private val zh = KeyState(punctuation = mapOf("." to "。", "," to "，", "\"" to "“"))

    private fun items(p: KeyPopup?) = (p as KeyPopup.Items).labels

    @Test
    fun aLetterAsBuiltInAndShifted() {
        val r = resolver("qwerty", Form.Full)
        val q = r.resolve(KeyId("full/q"), KeyState())
        assertEquals(listOf("1", "Q"), items(q.popup))
        assertEquals(Item("1"), q.first)
        assertEquals(MarkContent.Symbol("1"), q.upMark)
        assertEquals(listOf("1", "q"), items(r.resolve(KeyId("full/q"), KeyState(caps = true)).popup))
    }

    @Test
    fun aUsersListShiftedIsItsCapitals() {
        val lists = KeyLists.of(mapOf(KeyId("full/q") to UserList(false, listOf("!", "q", "ß"))))
        val r = resolver("qwerty", Form.Full, lists)
        assertEquals(listOf("!", "q", "ß"), items(r.resolve(KeyId("full/q"), KeyState()).popup))
        assertEquals(listOf("!", "Q", "ß"), items(r.resolve(KeyId("full/q"), KeyState(caps = true)).popup))
        // the first the list's own first
        assertEquals(Item("!"), r.resolve(KeyId("full/q"), KeyState()).first)
    }

    @Test
    fun commaIsTheSameOnEveryKeyboardAndShownAsThePunctuation() {
        for ((board, form) in listOf("qwerty" to Form.Full, "k14" to Form.Grouped14, "handwriting" to Form.Handwriting, "zhuyin" to Form.ZhuyinFull)) {
            val c = resolver(board, form).resolve(KeyId("fn/comma"), zh)
            assertEquals(board, Item("."), c.first)
            assertEquals(board, "。", c.firstShown)
            assertEquals(board, "。", items(c.popup).first())
            // its slots: no shortcut, a char's tap where empty (D3)
            assertTrue(board, c.swipe.char)
        }
    }

    @Test
    fun nineOneIsAsIsItsDigitAKeypads() {
        val r = resolver("nine", Form.Nine)
        val one = r.resolve(KeyId("t9/1"), zh)
        assertEquals(Item("1", Commit.Keypad('1')), one.first)
        val p = one.popup as KeyPopup.Items
        assertEquals(1, p.inRow)
        // shown as typed, never the punctuation's
        assertEquals(".", p.labels[5])
        assertEquals(Commit.AsIs, p.items[5].commit)
        // the user's symbols are as-is too, an added one as well
        val lists = KeyLists.of(mapOf(KeyId("t9/1") to UserList(false, listOf(".", "§"), First.Literal("."))))
        val set = resolver("nine", Form.Nine, lists).resolve(KeyId("t9/1"), zh)
        assertEquals(Item(".", Commit.AsIs), set.first)
        assertEquals(listOf(Commit.Keypad('1'), Commit.AsIs, Commit.AsIs), (set.popup as KeyPopup.Items).items.map { it.commit })
    }

    @Test
    fun englishNineOnesDefaultIsItsOwnTheUsersShared() {
        val en = resolver("nine", Form.Nine, language = KeyboardVariant.Language.English)
        assertEquals(":", items(en.resolve(KeyId("t9/1"), KeyState()).popup)[1])
        val lists = KeyLists.of(mapOf(KeyId("t9/1") to UserList(false, listOf("@"))))
        assertEquals(listOf("1", "@"), items(resolver("nine", Form.Nine, lists, language = KeyboardVariant.Language.English).resolve(KeyId("t9/1"), KeyState()).popup))
    }

    @Test
    fun aRowStaysTheUsersSymbolsAboveIt() {
        val lists = KeyLists.of(mapOf(KeyId("t9/5") to UserList(false, listOf("%", "j", "5"))))
        val five = resolver("nine", Form.Nine, lists).resolve(KeyId("t9/5"), KeyState())
        assertEquals(listOf("J", "K", "L", "5", "j", "k", "l", "%"), items(five.popup))
        assertEquals(Item("5", Commit.Keypad('5')), five.first)
        assertEquals(3, (five.popup as KeyPopup.Items).focus)
    }

    @Test
    fun aGroupsCornerIsItsFirstTheUsersFirstWins() {
        val r = resolver("k14", Form.Grouped14)
        assertEquals(Item("1", Commit.Keypad('1')), r.resolve(KeyId("g14/qw"), KeyState()).first)
        val lists = KeyLists.of(mapOf(KeyId("g14/qw") to UserList(false, listOf("+"), First.Literal("+"))))
        val set = resolver("k14", Form.Grouped14, lists).resolve(KeyId("g14/qw"), KeyState())
        assertEquals(Item("+"), set.first)
        assertEquals(MarkContent.Symbol("+"), set.upMark)
        assertEquals(5, (set.popup as KeyPopup.Items).focus)
    }

    @Test
    fun offOrNoneLeavesNoFirst() {
        val lists = KeyLists.of(mapOf(KeyId("full/q") to UserList.Off, KeyId("full/w") to UserList(false, listOf("2"), First.None)))
        val r = resolver("qwerty", Form.Full, lists)
        val q = r.resolve(KeyId("full/q"), KeyState())
        assertNull(q.popup)
        assertNull(q.first)
        assertFalse(q.swipe.hasFirst)
        assertEquals(MarkContent.Empty, q.upMark)
        assertNull(r.resolve(KeyId("full/w"), KeyState()).first)
    }

    @Test
    fun aZhuyinKeyHasNoneUntilTheUserGivesIt() {
        val none = resolver("zhuyin", Form.ZhuyinFull).resolve(KeyId("zyfull/1"), KeyState())
        assertNull(none.popup)
        val lists = KeyLists.of(mapOf(KeyId("zyfull/1") to UserList(false, listOf("①"))))
        val set = resolver("zhuyin", Form.ZhuyinFull, lists).resolve(KeyId("zyfull/1"), KeyState())
        assertEquals(Item("①"), set.first)
        assertEquals(MarkContent.Symbol("①"), set.upMark)
    }

    @Test
    fun rootsShowTheRootsAndTheLetterInTheCorner() {
        val r = resolver("qwerty", Form.Full).resolve(KeyId("full/q"), KeyState(roots = { if (it == 'q') "金钅" else null }))
        assertEquals(KeyPopup.Roots("金钅"), r.popup)
        assertEquals(MarkContent.Symbol("q"), r.upMark)
    }

    @Test
    fun theSlotsAndTheirMarks() {
        val slots = SwipeSlots.Default.with("full/q", false, SwipeSlot.Text("hi"))
        val r = resolver("qwerty", Form.Full, slots = slots)
        val q = r.resolve(KeyId("full/q"), KeyState())
        assertEquals(KeySwipe.Key(true, SwipeSlot.First, SwipeSlot.Text("hi")), q.swipe)
        assertEquals(MarkContent.Text("hi", "h…"), q.downMark)
        assertEquals(MarkContent.Icon("cursor-move"), r.resolve(KeyId("fn/space"), KeyState()).upMark)
        val down = resolver("qwerty", Form.Full, slots = SwipeSlots.Default.applied(SwipeOrientation.FirstDown)).resolve(KeyId("full/q"), KeyState())
        assertEquals(MarkContent.Symbol("1"), down.downMark)
    }

    @Test
    fun theEditorStartsFromTheKeyAsItPopsUp() {
        val nine = resolver("nine", Form.Nine)
        val five = nine.edit(KeyId("t9/5"))
        assertEquals(listOf("J", "K", "L", "5", "j", "k", "l"), five.row)
        assertEquals("5", five.first)
        assertNull(five.user)
        val lists = KeyLists.of(mapOf(KeyId("full/q") to UserList(false, listOf("!", "Q"), First.None), KeyId("t9/5") to UserList.Off))
        val q = resolver("qwerty", Form.Full, lists).edit(KeyId("full/q"))
        assertEquals(KeyEdit(emptyList(), listOf("!", "Q"), null, UserList(false, listOf("!", "Q"), First.None)), q)
        // off: the row as the keyboard makes it, nothing above
        val off = resolver("nine", Form.Nine, lists).edit(KeyId("t9/5"))
        assertEquals(KeyEdit(five.row, emptyList(), null, UserList.Off), off)
    }

    @Test
    fun aKeysDefaultsOnEachVariant() {
        val variants = listOf(KeyboardVariant(Form.Nine), KeyboardVariant(Form.Nine, KeyboardVariant.Language.English))
        val (pinyin, english) = resolver("nine", Form.Nine).defaultsOn(KeyId("t9/1"), variants)
        assertEquals("…", pinyin!!.symbols.first().text)
        assertEquals(":", english!!.symbols.first().text)
    }

    @Test
    fun aTablesLabelledLettersHaveThemselvesInTheirCorners() {
        val r = resolver("qwerty", Form.Full)
        val q = r.resolve(KeyId("full/q"), KeyState(letterCorners = true))
        assertEquals(MarkContent.Symbol("q"), q.upMark)
        // its long press as ever
        assertEquals(r.resolve(KeyId("full/q"), KeyState()).popup, q.popup)
    }

    @Test
    fun theMarksAreMadeOnceAStateAndAreTheWebsByDefault() {
        val r = resolver("qwerty", Form.Full)
        val m = r.marks(KeyState())
        assertSame(m, r.marks(KeyState()))
        assertEquals(DefaultMarks.shortcuts(layout("qwerty"), fragments), m.shortcuts)
        assertFalse(r.marks(KeyState(roots = { null })).charShortcuts)
    }
}
