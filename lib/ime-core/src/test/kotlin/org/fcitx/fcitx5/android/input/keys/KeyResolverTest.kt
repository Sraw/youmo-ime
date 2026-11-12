/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.assertRows
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
import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant.Form
import org.fcitx.fcitx5.android.scene.keys.NodeFacts
import org.fcitx.fcitx5.android.scene.layout.Popup
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyResolverTest {
    private fun layout(name: String): Layout = (LayoutReader.layout(SceneData.read("$name.xml")) as Parsed.Ok).value

    private val fragments: Map<String, Fragment> by lazy { mapOf("grouped-bottom" to (LayoutReader.fragment(SceneData.read("grouped-bottom.xml")) as Parsed.Ok).value) }

    private fun resolver(board: String, form: Form, lists: KeyLists = KeyLists.Empty, slots: SwipeSlots = SwipeSlots.Default) =
        resolver(board, KeyboardVariant(form), lists, slots)

    private fun resolver(board: String, variant: KeyboardVariant, lists: KeyLists = KeyLists.Empty, slots: SwipeSlots = SwipeSlots.Default) =
        KeyResolver(variant, lists, slots, DefaultMarks.nodes(layout(board), fragments))

    private fun popup(r: ResolvedKey) = r.popup as KeyPopup.Items

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

    // a first the user chose, stored lower case as typed, is still theirs under Shift: its capital
    @Test
    fun aChosenFirstShiftedIsItsCapital() {
        val lists = KeyLists.of(mapOf(KeyId("full/e") to UserList(false, listOf("è", "é"), First.Literal("é"))))
        val r = resolver("qwerty", Form.Full, lists)
        assertEquals("é", r.resolve(KeyId("full/e"), KeyState()).first?.text)
        assertEquals("É", r.resolve(KeyId("full/e"), KeyState(caps = true)).first?.text)
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
    fun nineOneGoesThroughThePunctuationItsDigitAKeypads() {
        val r = resolver("nine", Form.Nine)
        val one = r.resolve(KeyId("t9/1"), zh)
        assertEquals(Item("1", Commit.Keypad('1')), one.first)
        val p = one.popup as KeyPopup.Items
        assertEquals(1, p.inRow)
        // shown as Chinese punctuation types it (用户 2026-11-11: 中英文标点还是要自动转换), as it is in English
        assertEquals("。", p.labels[5])
        assertEquals(Commit.AsKey, p.items[5].commit)
        assertEquals(".", (r.resolve(KeyId("t9/1"), KeyState()).popup as KeyPopup.Items).labels[5])
        // the user's symbols the same, an added one as well
        val lists = KeyLists.of(mapOf(KeyId("t9/1") to UserList(false, listOf("1", ".", "§"), First.Literal("."))))
        val set = resolver("nine", Form.Nine, lists).resolve(KeyId("t9/1"), zh)
        assertEquals(Item("."), set.first)
        assertEquals("。", set.firstShown)
        assertEquals(listOf(Commit.Keypad('1'), Commit.AsKey, Commit.AsKey), (set.popup as KeyPopup.Items).items.map { it.commit })
    }

    private val two = KeyId("t9/2")

    private fun nine(vararg items: String, first: First = First.Default, variant: KeyboardVariant = KeyboardVariant(Form.Nine)) =
        resolver("nine", variant, KeyLists.of(mapOf(two to UserList(false, items.toList(), first)))).resolve(two, KeyState())

    // the stored list is the whole list (060-row-unlock §1): its digit deleted, the first is its first item as the full keyboard's
    @Test
    fun nineTwosDigitDeletedItsFirstIsA() {
        val a = nine("A", "B", "C", "a", "b", "c", "@")
        assertEquals(listOf("A", "B", "C", "a", "b", "c", "@"), items(a.popup))
        assertEquals(Item("A"), a.first)
        assertEquals("A", a.corner)
        assertEquals(0, popup(a).focus)
        assertEquals(6, popup(a).inRow)
        val lists = KeyLists.of(mapOf(two to UserList(false, listOf("A", "B", "C", "a", "@"))))
        assertEquals("A", resolver("nine", Form.Nine, lists).readings(KeyState())["t9/2"])
        // emptied: off, nothing said
        val off = resolver("nine", Form.Nine, KeyLists.Empty.with(two, UserList(false, emptyList()), KeyDefaults.nine('2', KeyboardVariant(Form.Nine))))
        assertNull(off.resolve(two, KeyState()).popup)
        assertNull(off.readings(KeyState())["t9/2"])
    }

    @Test
    fun itsLettersDeletedTheDigitStaysAKeypads() {
        val d = nine("2", "@", "!")
        assertEquals(Item("2", Commit.Keypad('2')), d.first)
        assertEquals(1, popup(d).inRow)
        // the digit back: a keypad's again, the first where the user leaves it
        val back = nine("A", "@", "2")
        assertEquals(Commit.Keypad('2'), popup(back).items[2].commit)
        assertEquals(Item("A"), back.first)
    }

    // the row: the leading items of the default's row or single ASCII letters and digits; -1 for none (§1.3)
    @Test
    fun theRowIsTheLeadingRowItems() {
        val moved = nine("A", "B", "C", "2", "a", "b", "@", "c", first = First.Literal("2"))
        assertEquals(6, popup(moved).inRow)
        assertEquals(3, popup(moved).focus)
        assertEquals(3, popup(moved).own)
        val symbolFirst = nine("@", "A", "B", "C", "2", "a", "b", "c", first = First.Literal("2"))
        assertEquals(-1, popup(symbolFirst).inRow)
        assertEquals(4, popup(symbolFirst).focus)
        // the first among the symbols: the row centres on its middle
        val atSign = nine("A", "B", "C", "2", "a", "b", "c", "@", first = First.Literal("@"))
        assertEquals(7, popup(atSign).inRow)
        assertEquals(3, popup(atSign).own)
        // a list of another 按键字母, shown on 双拼九键 (its letters NINE_SP's): one row all the same
        val custom = nine("A", "B", "D", "2", "a", "b", "d", first = First.Literal("2"), variant = KeyboardVariant(Form.Nine, KeyboardVariant.STANDARD_NINE + ('2' to "abc;")))
        assertEquals(7, popup(custom).inRow)
    }

    private val l = KeyId("g18/l")
    private val lStored = UserList(false, listOf("L", ":", "-", "l", ";", ")", "]"), First.Literal("-"))

    // the one exception (§2): 18 键's L shows `:` only where the scheme types `;`; `;` itself everywhere
    @Test
    fun eighteenLsColonOnlyOnASchemeTypingSemicolon() {
        val lists = KeyLists.of(mapOf(l to lStored))
        val pinyin = resolver("k18", KeyboardVariant(Form.Grouped18), lists).resolve(l, KeyState())
        assertEquals(listOf("L", "-", "l", ";", ")", "]"), items(pinyin.popup))
        val semi = popup(pinyin).items[3]
        assertEquals(Item(";"), semi)
        assertEquals(Item("-"), pinyin.first)
        val microsoft = resolver("k18", KeyboardVariant(Form.Grouped18, semicolon = true), lists).resolve(l, KeyState())
        assertEquals(listOf("L", ":", "-", "l", ";", ")", "]"), items(microsoft.popup))
        // its first `:`, skipped: the first item shown
        val colon = KeyLists.of(mapOf(l to lStored.copy(first = First.Literal(":"))))
        assertEquals(Item("L"), resolver("k18", KeyboardVariant(Form.Grouped18), colon).resolve(l, KeyState()).first)
        assertEquals(":", resolver("k18", KeyboardVariant(Form.Grouped18, semicolon = true), colon).resolve(l, KeyState()).first?.text)
    }

    @Test
    fun aGroupsCornerIsItsFirstTheUsersFirstWins() {
        val r = resolver("k14", Form.Grouped14)
        assertEquals(Item("1", Commit.Keypad('1')), r.resolve(KeyId("g14/qw"), KeyState()).first)
        val lists = KeyLists.of(mapOf(KeyId("g14/qw") to UserList(false, listOf("Q", "W", "1", "q", "w", "+"), First.Literal("+"))))
        val set = resolver("k14", Form.Grouped14, lists).resolve(KeyId("g14/qw"), KeyState())
        assertEquals(Item("+"), set.first)
        assertEquals(MarkContent.Symbol("+"), set.upMark)
        assertEquals(5, (set.popup as KeyPopup.Items).focus)
    }

    @Test
    fun noFirst() = assertRows(
        // the user turned its long press off: nothing drawn, no swipe of its first
        "off: no popup, no first, no mark" to {
            val q = resolver("qwerty", Form.Full, KeyLists.of(mapOf(KeyId("full/q") to UserList.Off))).resolve(KeyId("full/q"), KeyState())
            assertNull(q.popup)
            assertNull(q.first)
            assertFalse(q.swipe.hasFirst)
            assertEquals(MarkContent.Empty, q.upMark)
        },
        // a list with its first set to none: items kept, none first
        "a first set to none" to {
            val lists = KeyLists.of(mapOf(KeyId("full/w") to UserList(false, listOf("2"), First.None)))
            assertNull(resolver("qwerty", Form.Full, lists).resolve(KeyId("full/w"), KeyState()).first)
        },
        // a theme's `send=` of no action KeyOutput can make: the corner and the swipe's slot read the same none as
        // the keyboard's swipe, so the corner never shows what the swipe does not type; the item stays in the popup
        // (FB2 of R24, was aFirstNoBuildCanTypeIsNone)
        "a first KeyOutput cannot make" to {
            val node = NodeFacts(text = "q", popup = Popup("list", null, listOf("x" to "layout:", "y" to null), emptyMap()))
            val q = KeyResolver(KeyboardVariant(Form.Full), KeyLists.Empty, SwipeSlots.Default, listOf("full/q" to node))
                .resolve(KeyId("full/q"), KeyState())
            assertEquals("x", (q.popup as KeyPopup.Items).items.first().text)
            assertNull(q.first)
            assertNull(q.corner)
            assertFalse(q.swipe.hasFirst)
            assertEquals(MarkContent.Empty, q.upMark)
        },
    )

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
        assertEquals(listOf("J", "K", "L", "5", "j", "k", "l", "#", "(", ")"), five.items)
        assertEquals("5", five.first)
        assertNull(five.user)
        val lists = KeyLists.of(mapOf(KeyId("full/q") to UserList(false, listOf("!", "Q"), First.None), KeyId("t9/5") to UserList.Off))
        val q = resolver("qwerty", Form.Full, lists).edit(KeyId("full/q"))
        assertEquals(KeyEdit(listOf("!", "Q"), null, UserList(false, listOf("!", "Q"), First.None)), q)
        // off: nothing
        assertEquals(KeyEdit(emptyList(), null, UserList.Off), resolver("nine", Form.Nine, lists).edit(KeyId("t9/5")))
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
        // made once a state, a performance promise (every key's draw asks; KeyResolver's doc), not only equal
        assertSame(m, r.marks(KeyState()))
        assertEquals(DefaultMarks.shortcuts(layout("qwerty"), fragments), m.shortcuts)
        assertFalse(r.marks(KeyState(roots = { null })).charShortcuts)
    }

    // what a screen reader says of a key's corner: its first as drawn, only where a way draws it (060-keys-unify §3.4)
    @Test
    fun theReadingsAreTheFirstsTheWaysDraw() {
        val r = resolver("qwerty", Form.Full)
        val read = r.readings(zh)
        // made once a state, as marks() (a screen reader asks every key)
        assertSame(read, r.readings(zh))
        assertEquals("1", read["full/q"])
        assertEquals("@", read["full/a"])
        // as Chinese punctuation shows it
        assertEquals("～", r.readings(zh.copy(punctuation = zh.punctuation + ("~" to "～")))["full/n"])
        // ， has a first, but no way draws it: nothing said
        assertNull(read["fn/comma"])
        assertNull(read["fn/space"])
        // q's up a command: its first nowhere drawn, nothing said; set again to the first, said
        val command = resolver("qwerty", Form.Full, slots = SwipeSlots.Default.with("full/q", up = true, SwipeSlot.Empty))
        assertNull(command.readings(zh)["full/q"])
        val down = resolver("qwerty", Form.Full, slots = SwipeSlots.Default.with("full/q", up = true, SwipeSlot.Empty).with("full/q", up = false, SwipeSlot.First))
        assertEquals("1", down.readings(zh)["full/q"])
        // the 14 keys' groups by their names
        assertEquals("1", resolver("k14", Form.Grouped14).readings(KeyState())["g14/qw"])
    }

    // a way turned off, or its place 无, draws no corner: none said (060-s6-review L1), each way apart
    @Test
    fun theReadingsLeaveOutAWayOffOrPlacedNowhere() {
        val down = resolver("qwerty", Form.Full, slots = SwipeSlots.Default.with("full/q", up = true, SwipeSlot.Empty).with("full/q", up = false, SwipeSlot.First))
        assertEquals("1", down.readings(zh)["full/q"])
        assertNull(down.readings(zh.copy(ways = KeySwipe.Ways(up = true, down = false)))["full/q"])
        assertNull(down.readings(zh.copy(spots = KeyMarks.Spots(KeyMarks.At.TopRight, KeyMarks.At.None)))["full/q"])
        val r = resolver("qwerty", Form.Full)
        assertNull(r.readings(zh.copy(ways = KeySwipe.Ways(up = false, down = true)))["full/w"])
        assertNull(r.readings(zh.copy(spots = KeyMarks.Spots(KeyMarks.At.None, KeyMarks.At.Bottom)))["full/w"])
        assertEquals("2", r.readings(zh.copy(ways = KeySwipe.Ways(up = true, down = false)))["full/w"])
    }

    // a character key of `mark="false"` (注音's): no first of the defaults' drawn or said, one the user set both
    // (060-s6-review L4)
    @Test
    fun aMarkOffKeysFirstIsTheUsersAlone() {
        val group = KeyId("zy9/1")
        val r = resolver("zhuyin-nine", Form.ZhuyinNine)
        assertNull(r.resolve(group, KeyState()).corner)
        assertNull(r.readings(KeyState())["zy9/1"])
        val zhuyin = r.edit(group).items
        val own = resolver("zhuyin-nine", Form.ZhuyinNine, lists = KeyLists.of(mapOf(group to UserList(false, zhuyin + "?", First.None))))
        // a symbol added, no first set (none, as the group's default)
        assertNull(own.resolve(group, KeyState()).corner)
        val chosen = resolver("zhuyin-nine", Form.ZhuyinNine, lists = KeyLists.of(mapOf(group to UserList(false, zhuyin + "?", First.Literal("?")))))
        assertEquals("?", chosen.resolve(group, KeyState()).corner)
        assertEquals(MarkContent.Symbol("?"), chosen.resolve(group, KeyState()).upMark)
        // ˉ's ˙ (its defaults' first, a named key not fn/'s: a character key's mark=) not drawn, as the scene never drew it
        val tone = KeyId("zy9/tone-1")
        assertEquals("˙", r.resolve(tone, KeyState()).first?.text)
        assertEquals(MarkContent.Empty, r.resolve(tone, KeyState()).upMark)
    }

    @Test
    fun aLongFirstIsMarkedAsASetTextIs() {
        // a 0.5 backup's long presses: the corner as a swipe's text is drawn (two characters as seen and 「…」), the
        // first typed and said whole
        val long = "5%葡萄糖注射液100ml"
        val phone = "13895801474"
        val lists = KeyLists.of(mapOf(KeyId("full/l") to UserList(false, listOf(long)), KeyId("full/q") to UserList(false, listOf(phone))))
        val r = resolver("qwerty", Form.Full, lists)
        val l = r.resolve(KeyId("full/l"), KeyState())
        assertEquals(MarkContent.Symbol("5%…"), l.upMark)
        assertEquals(long, l.first?.text)
        assertEquals(long, l.corner)
        assertEquals(MarkContent.Symbol("13…"), r.resolve(KeyId("full/q"), KeyState()).upMark)
    }
}
