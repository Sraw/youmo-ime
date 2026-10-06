/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

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
