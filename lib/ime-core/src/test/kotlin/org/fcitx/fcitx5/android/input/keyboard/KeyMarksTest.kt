/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.KeyMarks.Placed
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks.Position
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks.ShortcutAt
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyMarksTest {

    private val bools = listOf(false, true)
    private val ways = listOf(true, false, null)

    private fun place(symbolWay: Boolean?, at: ShortcutAt, position: Position = Position.TopRight, landscape: Boolean = false, compact: Boolean = false) =
        KeyMarks.place("，", symbolWay, "全选", true, at, position, landscape, compact)

    @Test
    fun theShortcutWhereChosenTheSymbolByItsWay() {
        // the default: 滑动 上滑快捷键，下滑符号, 快捷键标记位置 左上
        assertEquals(Placed.of(Slot.TopLeft to "全选", Slot.BottomRight to "，"), place(false, ShortcutAt.Default))
        assertEquals(Placed.of(Slot.TopLeft to "全选", Slot.TopRight to "，"), place(true, ShortcutAt.TopLeft))
        assertEquals("no swipe to it: top right", Placed.of(Slot.BottomLeft to "全选", Slot.TopRight to "，"), place(null, ShortcutAt.BottomLeft))
        assertEquals(Placed.of(Slot.Bottom to "全选", Slot.BottomRight to "，"), place(false, ShortcutAt.Bottom))
    }

    @Test
    fun aSymbolInTheShortcutsCornerTakesTheOtherRightOne() {
        // z: 「，」 down, bottom right; the shortcut chosen there, it goes up
        assertEquals(Placed.of(Slot.BottomRight to "全选", Slot.TopRight to "，"), place(false, ShortcutAt.BottomRight))
        assertEquals(Placed.of(Slot.TopRight to "全选", Slot.BottomRight to "，"), place(true, ShortcutAt.TopRight))
        assertEquals(Placed.of(Slot.TopRight to "全选", Slot.BottomRight to "，"), place(null, ShortcutAt.TopRight))
        // either alone where it would be
        assertEquals(Placed.of(Slot.BottomRight to "，"), KeyMarks.place("，", false, null, true, ShortcutAt.BottomRight, Position.TopRight, false))
    }

    @Test
    fun underTheLetterUnlessTheShortcutIsThere() {
        assertEquals(Placed.of(Slot.TopLeft to "全选", Slot.Below to "，"), place(false, ShortcutAt.TopLeft, Position.Bottom))
        assertEquals(Placed.of(Slot.BottomRight to "全选", Slot.Below to "，"), place(false, ShortcutAt.BottomRight, Position.Bottom))
        // the bottom edge's middle and under the letter one place: the symbol its way's corner
        assertEquals(Placed.of(Slot.Bottom to "全选", Slot.BottomRight to "，"), place(false, ShortcutAt.Bottom, Position.Bottom))
        assertEquals(Placed.of(Slot.Bottom to "全选", Slot.TopRight to "，"), place(true, ShortcutAt.Bottom, Position.Bottom))
        // no room under it: landscape's and 紧凑's keys
        assertEquals(Placed.of(Slot.TopLeft to "全选", Slot.BottomRight to "，"), place(false, ShortcutAt.TopLeft, Position.Bottom, landscape = true))
        assertEquals(Placed.of(Slot.TopLeft to "全选", Slot.BottomRight to "，"), place(false, ShortcutAt.TopLeft, Position.Bottom, compact = true))
    }

    @Test
    fun noneDrawsNothing() {
        for (at in ShortcutAt.entries) for (way in ways) {
            assertEquals(Placed<String>(), place(way, at, Position.None))
            assertEquals(Placed<String>(), KeyMarks.placeFunction("清除", "恢复", true, true, at, Position.None))
        }
    }

    private data class Case(
        val at: ShortcutAt, val symbolWay: Boolean?, val shortcutWay: Boolean?, val position: Position,
        val landscape: Boolean, val compact: Boolean, val symbol: String?, val shortcut: String?,
    ) {
        fun place(shortcutWay: Boolean? = this.shortcutWay) = KeyMarks.place(symbol, symbolWay, shortcut, shortcutWay, at, position, landscape, compact)
    }

    // every combination: the shortcut in its chosen slot, the symbol on the right or under, never the
    // two in one place, the symbol where it would be alone unless the shortcut is there
    @Test
    fun everyCombinationOneMarkASlot() {
        val n = 5 * 3 * 3 * 3 * 2 * 2 * 2 * 2
        val cases = (0 until n).map { i ->
            val r = intArrayOf(i)
            Case(
                ShortcutAt.entries.pick(r), ways.pick(r), ways.pick(r), Position.entries.pick(r),
                bools.pick(r), bools.pick(r), listOf("，", null).pick(r), listOf("全选", null).pick(r),
            )
        }
        assertEquals(n, cases.toSet().size)
        cases.forEach(::check)
    }

    // the next digit of [r] in this list's base, as its item
    private fun <T> List<T>.pick(r: IntArray): T = this[r[0] % size].also { r[0] /= size }

    private fun check(c: Case) {
        val placed = c.place()
        if (c.position == Position.None) return assertEquals("$c", Placed<String>(), placed)
        val shortcutDrawn = c.shortcut != null && c.shortcutWay != null
        assertEquals("$c", if (shortcutDrawn) c.at.slot else null, placed.slotOf("全选"))
        val s = placed.slotOf("，")
        if (c.symbol == null) return assertEquals("$c", null, s)
        assertTrue("$c", s == Slot.Below || s!!.right)
        val alone = KeyMarks.symbolSlot(c.symbolWay, c.position, c.landscape, c.compact)
        val clash = shortcutDrawn && (alone == c.at.slot || (alone == Slot.Below && c.at.slot == Slot.Bottom))
        if (!clash) assertEquals("$c", alone, s)
        if (!shortcutDrawn) return
        assertNotEquals("$c", c.at.slot, s)
        assertTrue("$c", !(s == Slot.Below && c.at.slot == Slot.Bottom))
        // the way that swipes to the shortcut moves nothing: 0.5.1's symbols that moved as 滑动 changed
        assertEquals("$c", placed, c.place(!c.shortcutWay!!))
    }

    @Test
    fun aFunctionKeysUpWhereChosenItsDownMirrored() {
        val expected = mapOf(
            ShortcutAt.TopLeft to (Slot.TopLeft to Slot.BottomLeft),
            ShortcutAt.BottomLeft to (Slot.BottomLeft to Slot.TopLeft),
            ShortcutAt.TopRight to (Slot.TopRight to Slot.BottomRight),
            ShortcutAt.BottomRight to (Slot.BottomRight to Slot.TopRight),
            ShortcutAt.Bottom to (Slot.Bottom to Slot.Top),
        )
        for (at in ShortcutAt.entries) for (position in listOf(Position.TopRight, Position.Bottom)) {
            val (up, down) = expected.getValue(at)
            assertEquals(Placed.of(up to "清除", down to "恢复"), KeyMarks.placeFunction("清除", "恢复", true, true, at, position))
            assertEquals("the down one alone where chosen", Placed.of(up to "恢复"), KeyMarks.placeFunction("清除", "恢复", false, true, at, position))
            assertEquals(Placed.of(up to "清除"), KeyMarks.placeFunction("清除", "恢复", true, false, at, position))
            assertEquals(Placed.of(up to "清除"), KeyMarks.placeFunction("清除", null, true, true, at, position))
            assertEquals(Placed<String>(), KeyMarks.placeFunction<String>(null, null, true, true, at, position))
        }
    }

    @Test
    fun slotsMirrorTopForBottom() {
        for (s in Slot.entries) {
            assertEquals(s.left, s.mirrored.left)
            assertEquals(s.right, s.mirrored.right)
            if (s != Slot.Below) assertEquals(s, s.mirrored.mirrored)
        }
        assertTrue(Slot.Bottom.centred && Slot.Top.centred && !Slot.TopLeft.centred)
    }
}
