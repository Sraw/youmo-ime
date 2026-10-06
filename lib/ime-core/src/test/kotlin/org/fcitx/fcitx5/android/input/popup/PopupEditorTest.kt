/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupEditorTest {

    private fun editor() = PopupEditor(listOf("2", "W", "w"))

    @Test
    fun aTapSelectsAndNothingMoves() {
        val e = editor()
        e.tap(2)
        assertEquals(listOf("2", "W", "w"), e.items)
        assertEquals(2, e.selected)
        e.tap(2)
        assertEquals(-1, e.selected)
    }

    @Test
    fun makeFirstAndMovesFollowTheCharacter() {
        val e = editor()
        e.tap(2)
        e.makeFirst()
        assertEquals(listOf("w", "2", "W"), e.items)
        assertEquals(0, e.selected)
        e.moveRight()
        assertEquals(listOf("2", "w", "W"), e.items)
        assertEquals(1, e.selected)
        e.moveLeft()
        assertEquals(listOf("w", "2", "W"), e.items)
    }

    @Test
    fun whatDoesNotApplyIsOff() {
        val e = editor()
        assertFalse(e.canDelete)
        e.tap(0)
        assertFalse(e.canMakeFirst)
        assertFalse(e.canMoveLeft)
        assertTrue(e.canMoveRight)
        e.tap(2)
        assertFalse(e.canMoveRight)
        e.moveRight()
        assertEquals(listOf("2", "W", "w"), e.items)
    }

    @Test
    fun deleteSelectsTheNextOrNone() {
        val e = editor()
        e.tap(1)
        e.delete()
        assertEquals(listOf("2", "w"), e.items)
        assertEquals(1, e.selected)
        e.delete()
        assertEquals(0, e.selected)
        e.delete()
        assertEquals(-1, e.selected)
        assertTrue(e.items.isEmpty())
    }

    @Test
    fun addKeepsEachCharacterOnce() {
        val e = editor()
        e.add("é é w ü")
        assertEquals(listOf("2", "W", "w", "é", "ü"), e.items)
    }
}
