/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolPanelTest {

    private val panel = SymbolPanel(categories = 4, first = 1)

    @Test
    fun itOpensOnTheCommonSymbolsThenOnWhatWasShownLast() {
        panel.open(recentEmpty = true)
        assertEquals(1, panel.selected)
        panel.select(3)
        panel.open(recentEmpty = true)
        assertEquals(3, panel.selected)
    }

    // while no category was ever picked, the recently used once there are any; a pick is remembered
    @Test
    fun tillACategoryIsPickedItOpensOnTheRecentlyUsedOnceThereAreAny() {
        assertRows(
            listOf(
                Triple(null, true, 1) to "nothing picked, nothing used: the common symbols",
                Triple(null, false, SymbolPanel.RECENT) to "nothing picked, a symbol used: the recently used",
                Triple(2, false, 2) to "a category picked: that one, recently used or not",
                Triple(1, false, 1) to "the common ones picked: they stay, though some were used since",
                Triple(SymbolPanel.RECENT, true, 1) to "the recently used picked, then emptied: the common symbols",
            ),
            { it.second },
        ) { (row, _) ->
            val p = SymbolPanel(categories = 4, first = 1)
            row.first?.let(p::select)
            p.open(recentEmpty = row.second)
            assertEquals(row.third, p.selected)
        }
    }

    // the pick kept across restarts, the app passing what it stored back in
    @Test
    fun aRememberedPickOpensAsAPickMadeInThisRun() {
        assertRows(
            listOf(
                Triple(3, false, 3) to "kept: opens there, though some were used since",
                Triple(SymbolPanel.RECENT, false, SymbolPanel.RECENT) to "the recently used kept: there while there are any",
                Triple(SymbolPanel.RECENT, true, 1) to "the recently used kept, now empty: the common symbols",
                Triple(9, false, SymbolPanel.RECENT) to "not in the list (renamed, gone): as none picked, the recently used",
                Triple(-1, true, 1) to "not in the list, nothing used: as none picked, the common symbols",
                Triple(null, false, SymbolPanel.RECENT) to "none kept: as before, the recently used",
            ),
            { it.second },
        ) { (row, _) ->
            val p = SymbolPanel(categories = 4, first = 1, remembered = row.first)
            p.open(recentEmpty = row.second)
            assertEquals(row.third, p.selected)
        }
    }

    @Test
    fun theRecentlyUsedAreListedOnceThereAreAny() {
        assertFalse(panel.shows(SymbolPanel.RECENT, recentEmpty = true))
        assertTrue(panel.shows(SymbolPanel.RECENT, recentEmpty = false))
        assertTrue(panel.shows(2, recentEmpty = true))
        // shown last, then emptied: the panel opens on the common ones
        panel.select(SymbolPanel.RECENT)
        panel.open(recentEmpty = false)
        assertEquals(SymbolPanel.RECENT, panel.selected)
        panel.open(recentEmpty = true)
        assertEquals(1, panel.selected)
    }

    @Test
    fun aPickGoesBackToTheKeyboardUnlessLocked() {
        assertTrue(panel.returnsAfterPick(locked = false))
        assertFalse(panel.returnsAfterPick(locked = true))
    }

    @Test
    fun noCategoryOutsideTheList() {
        assertThrows(IllegalArgumentException::class.java) { panel.select(4) }
        assertThrows(IllegalArgumentException::class.java) { SymbolPanel(categories = 2, first = 2) }
    }
}
