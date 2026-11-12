/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.Uat
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

    // the key's own letters and digit as any item (060-row-unlock): moved, deleted, made the first
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
}
