/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.keys.ItemMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupEditorTest {

    // a 14 key's: its own items (Q 1 q w) and the symbols, one list
    private fun editor() = PopupEditor(listOf("Q", "1", "q", "w", "!", "@", "#"), "1")

    @Test
    fun aTapSelectsAndNothingMoves() {
        val e = editor()
        e.tap(4)
        assertEquals(listOf("Q", "1", "q", "w", "!", "@", "#"), e.items)
        assertEquals(4, e.selected)
        e.tap(4)
        assertEquals(-1, e.selected)
    }

    // the key's own letters and digit as any item: moved, deleted, made the first
    @Test
    fun aRowItemMovesDeletesAndIsMadeTheFirst() {
        val e = editor()
        e.tap(2)
        assertTrue(e.canMoveLeft)
        e.moveRight()
        assertEquals(listOf("Q", "1", "w", "q", "!", "@", "#"), e.items)
        assertEquals(3, e.selected)
        e.makeFirst()
        assertEquals("q", e.first)
        assertFalse(e.canMakeFirst)
        e.tap(0)
        e.delete()
        assertEquals(listOf("1", "w", "q", "!", "@", "#"), e.items)
        assertEquals(0, e.selected)
    }

    @Test
    fun aDraggedItemGoesWhereItIsDropped() = assertRows(
        "another item dragged past the selected one: the selection stays on its item" to {
            val e = editor().apply { tap(4) }
            e.move(6, 0)
            assertEquals(listOf("#", "Q", "1", "q", "w", "!", "@"), e.items)
            assertEquals(5, e.selected)
        },
        "the selected item dragged: the selection goes with it" to {
            val e = editor().apply { tap(4) }
            e.move(4, 6)
            assertEquals(listOf("Q", "1", "q", "w", "@", "#", "!"), e.items)
            assertEquals(6, e.selected)
        },
        "nothing selected stays so" to {
            val e = editor()
            e.move(1, 2)
            assertEquals(-1, e.selected)
        },
        "a drop out of the list moves nothing" to {
            val e = editor().apply { tap(4) }
            e.move(0, 7)
            e.move(-1, 0)
            assertEquals(editor().items, e.items)
            assertEquals(4, e.selected)
        },
    )

    @Test
    fun aLongerListTakesItsOwnMost() {
        val e = PopupEditor(emptyList(), null, max = 24)
        assertTrue(e.add((1..24).joinToString(" ")))
        assertEquals(24, e.items.size)
        assertFalse(e.add("x"))
        assertFalse(PopupEditor(emptyList(), null).add((1..17).joinToString(" ")))
    }

    @Test
    fun theEndsMoveNoFurther() {
        val e = editor()
        e.tap(0)
        assertFalse(e.canMoveLeft)
        e.moveLeft()
        assertEquals("Q", e.items.first())
        e.tap(6)
        assertFalse(e.canMoveRight)
    }

    // as the full keyboard's: the first deleted, the list's first item now (九键 2's digit deleted: A)
    @Test
    fun theFirstDeletedPassesToTheFirstItemLeft() {
        val e = PopupEditor(listOf("A", "B", "C", "2", "a", "b", "c", "@"), "2")
        e.tap(3)
        e.delete()
        assertEquals("A", e.first)
        val f = PopupEditor(listOf("a", "b"), "b")
        f.tap(1)
        f.delete()
        assertEquals("a", f.first)
        assertEquals(0, f.selected)
        f.delete()
        assertNull(f.first)
        assertEquals(-1, f.selected)
        f.add("x y")
        assertEquals("a list of none takes its first", "x", f.first)
    }

    @Test
    fun aKeyWithNoFirstKeepsNoneWhenAddedTo() {
        val e = PopupEditor(listOf("ㄅ", "ㄉ"), null)
        e.add("@")
        assertNull(e.first)
    }

    @Test
    fun addKeepsEachItemOnce() {
        val e = editor()
        e.add("é é w ü !")
        assertEquals(listOf("Q", "1", "q", "w", "!", "@", "#", "é", "ü"), e.items)
        assertEquals("1", e.first)
    }

    // a key holds 16 items: what does not fit is left out and said so
    @Test
    @Uat("KB-82")
    fun addStopsAtSixteen() {
        val e = editor()
        assertTrue(e.add((1..9).joinToString(" ") { "s$it" }))
        assertEquals(16, e.items.size)
        assertFalse(e.add("x"))
        assertEquals(16, e.items.size)
        e.tap(0)
        e.delete()
        assertFalse(e.add("y z"))
        assertEquals("y", e.items.last())
        assertEquals(16, e.items.size)
    }

    @Test
    fun aFirstNotThereIsNone() {
        assertNull(PopupEditor(listOf("1", "a"), "z").first)
        assertEquals(listOf("1", "a"), PopupEditor(listOf("1", "a", "1"), null).items)
    }

    // an English mark a Chinese table converts, the user's table's keys too
    @Test
    fun whatCanBeKeptAsIs() {
        val keys = setOf(",", ".", "@")
        assertTrue(PopupEditor.canKeepAsIs(",", keys))
        for (no in listOf("a", "1", "，", "ab", "#")) assertFalse(no, PopupEditor.canKeepAsIs(no, keys))
        // `@` in the user's table alone: still one
        assertTrue(PopupEditor.canKeepAsIs("@", keys))
        assertFalse(PopupEditor.canKeepAsIs("@", setOf(",")))
    }

    @Test
    fun marksTurnedOnAndOffGoWithTheirItemAndNeverTogether() {
        val keys = setOf(",", ".")
        val e = PopupEditor(listOf("a", ",", "."), null)
        e.tap(0)
        assertFalse(e.canMark(ItemMark.AsIs, keys))
        assertTrue(e.canMark(ItemMark.Variables, keys))
        e.tap(1)
        assertTrue(e.canMark(ItemMark.AsIs, keys))
        e.toggle(ItemMark.AsIs)
        assertEquals(ItemMark.AsIs, e.selectedMark)
        // the other one greyed while it is on
        assertFalse(e.canMark(ItemMark.Variables, keys))
        e.toggle(ItemMark.Variables)
        assertEquals(mapOf("," to ItemMark.AsIs), e.marks)
        e.moveRight()
        assertEquals(listOf("a", ".", ","), e.items)
        assertEquals(ItemMark.AsIs, e.selectedMark)
        e.toggle(ItemMark.AsIs)
        assertNull(e.selectedMark)
        e.toggle(ItemMark.Variables)
        assertEquals(mapOf("," to ItemMark.Variables), e.marks)
        e.delete()
        assertEquals(emptyMap<String, ItemMark>(), e.marks)
    }

    @Test
    fun theMarksAsStoredStartTheEditor() {
        val e = PopupEditor(listOf("\$day", ","), null, mapOf("\$day" to ItemMark.Variables, "gone" to ItemMark.AsIs))
        assertEquals(mapOf("\$day" to ItemMark.Variables), e.marks)
    }

    // the field one item, blanks round it gone, a line break a space, 64 at most
    @Test
    fun anItemOfVariablesAdded() {
        val e = PopupEditor(listOf("a"), "a")
        assertTrue(e.add("  \$year 年\n\$month月 ", ItemMark.Variables))
        assertEquals(listOf("a", "\$year 年 \$month月"), e.items)
        assertEquals(mapOf("\$year 年 \$month月" to ItemMark.Variables), e.marks)
        e.add("x".repeat(70), ItemMark.Variables)
        assertEquals("x".repeat(64), e.items.last())
        assertEquals(ItemMark.Variables, e.marks["x".repeat(64)])
        // nothing but blanks: nothing added, and true (false only means "some did not fit"), so the box is not told it is full
        val before = e.items.toList()
        assertTrue(e.add("  ", ItemMark.Variables))
        assertEquals(before, e.items)
    }

    // the marks of them that can have it, the others added as ever
    @Test
    fun itemsAddedAsIs() {
        val e = PopupEditor(emptyList(), null)
        e.add(", a . ，", ItemMark.AsIs, setOf(",", "."))
        assertEquals(listOf(",", "a", ".", "，"), e.items)
        assertEquals(mapOf("," to ItemMark.AsIs, "." to ItemMark.AsIs), e.marks)
    }

    // turned off, an item of variables over 16 would be cut where stored and no longer be the first
    @Test
    fun aLongItemOfVariablesKeepsItsMark() {
        val long = "\$year 年 \$month 月 \$day 日"
        val e = PopupEditor(listOf("a", long, "\$day"), long, mapOf(long to ItemMark.Variables, "\$day" to ItemMark.Variables))
        e.tap(1)
        assertFalse(e.canMark(ItemMark.Variables, emptySet()))
        assertFalse(e.canMark(ItemMark.AsIs, emptySet()))
        // a short one can still have it turned off
        e.tap(2)
        assertTrue(e.canMark(ItemMark.Variables, emptySet()))
    }

    @Test
    fun anItemThereAlreadyTakesTheMarkAdded() {
        val e = PopupEditor(listOf("a", ",", "\$day"), null, mapOf("," to ItemMark.Variables))
        assertTrue(e.add("\$day", ItemMark.Variables))
        assertEquals(listOf("a", ",", "\$day"), e.items)
        assertEquals(ItemMark.Variables, e.marks["\$day"])
        // one with a mark keeps it
        e.add(", .", ItemMark.AsIs, setOf(",", "."))
        assertEquals(mapOf("," to ItemMark.Variables, "\$day" to ItemMark.Variables, "." to ItemMark.AsIs), e.marks)
    }
}
