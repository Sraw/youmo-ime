/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupEditorTest {

    // a 14 key's: its row (1 Q W) the keyboard's, its symbols the user's
    private fun editor() = PopupEditor(listOf("1", "q", "w"), listOf("!", "@", "#"), "1")

    @Test
    fun aTapSelectsAndNothingMoves() {
        val e = editor()
        e.tap(4)
        assertEquals(listOf("1", "q", "w", "!", "@", "#"), e.items)
        assertEquals(4, e.selected)
        e.tap(4)
        assertEquals(-1, e.selected)
    }

    // the row greyed: chosen as the first, never moved or deleted (060-keys-unify S5)
    @Test
    fun aRowItemCanOnlyBeMadeTheFirst() {
        val e = editor()
        e.tap(1)
        assertTrue(e.isRow(1))
        assertFalse(e.canDelete)
        assertFalse(e.canMoveLeft)
        assertFalse(e.canMoveRight)
        e.delete()
        e.moveRight()
        assertEquals(listOf("1", "q", "w", "!", "@", "#"), e.items)
        assertTrue(e.canMakeFirst)
        e.makeFirst()
        assertEquals("q", e.first)
        assertFalse(e.canMakeFirst)
    }

    @Test
    fun symbolsMoveAmongThemselvesOnly() {
        val e = editor()
        e.tap(3)
        assertFalse("not into the row", e.canMoveLeft)
        e.moveLeft()
        assertEquals(listOf("!", "@", "#"), e.symbols)
        e.moveRight()
        assertEquals(listOf("@", "!", "#"), e.symbols)
        assertEquals(4, e.selected)
        e.tap(5)
        assertFalse(e.canMoveRight)
    }

    @Test
    fun theFirstDeletedPassesToTheFirstItemLeft() {
        val e = PopupEditor(emptyList(), listOf("a", "b"), "b")
        e.tap(1)
        e.delete()
        assertEquals("a", e.first)
        assertEquals(0, e.selected)
        e.delete()
        assertNull(e.first)
        assertEquals(-1, e.selected)
        e.add("x y")
        assertEquals("a list of none takes its first", "x", e.first)
    }

    // review 7: a first deleted falls back to the default first where still there, else to the first symbol, never to the row's first
    @Test
    fun aDeletedFirstFallsBackToTheDefaultFirstThenTheFirstSymbol() {
        val e = PopupEditor(listOf("1", "q"), listOf("!", "@", "#"), "@", defaultFirst = "q")
        e.tap(3)
        e.delete()
        assertEquals("the default first, a row item", "q", e.first)
        val f = PopupEditor(listOf("1", "q"), listOf("!", "@", "#"), "@", defaultFirst = "%")
        f.tap(3)
        f.delete()
        assertEquals("no default first in the list: the first symbol", "!", f.first)
        val g = PopupEditor(listOf("1"), listOf("@"), "@")
        g.tap(1)
        g.delete()
        assertNull("no symbol left: none, not the row's 1", g.first)
    }

    // re-review 2: a key turned off (its row, no first) edited on takes its default first, never `set !`
    @Test
    fun anOffKeyEditedOnTakesItsDefaultFirst() {
        val e = PopupEditor(listOf("J", "K", "5"), emptyList(), null, defaultFirst = "5", off = true)
        e.add("x")
        assertEquals("5", e.first)
        val f = PopupEditor(listOf("J", "K"), emptyList(), null, defaultFirst = null, off = true)
        f.add("x")
        assertEquals("no default first: the first symbol", "x", f.first)
        val g = PopupEditor(listOf("J", "K", "5"), emptyList(), null, defaultFirst = "5")
        g.add("x")
        assertNull("a key on with none chosen keeps none", g.first)
    }

    // an off key's added character deleted again: off as it started, no first; a first chosen keeps it on
    @Test
    fun anOffKeyAddedToAndBackIsOffAgain() {
        val e = PopupEditor(listOf("J", "K", "5"), emptyList(), null, defaultFirst = "5", off = true)
        e.add("x")
        e.tap(3)
        e.delete()
        assertNull(e.first)
        assertEquals(emptyList<String>(), e.symbols)
        val f = PopupEditor(listOf("J", "K", "5"), emptyList(), null, defaultFirst = "5", off = true)
        f.add("x")
        f.tap(1)
        f.makeFirst()
        f.tap(3)
        f.delete()
        assertEquals("K", f.first)
    }

    @Test
    fun addKeepsEachItemOnceAndNoneOfTheRow() {
        val e = editor()
        e.add("é é w ü !")
        assertEquals(listOf("!", "@", "#", "é", "ü"), e.symbols)
        assertEquals("1", e.first)
    }

    @Test
    fun aFirstNotThereIsNone() {
        assertNull(PopupEditor(listOf("1"), listOf("a"), "z").first)
        // a symbol of the row's is the row's alone
        assertEquals(listOf("a"), PopupEditor(listOf("1"), listOf("1", "a"), null).symbols)
    }
}
