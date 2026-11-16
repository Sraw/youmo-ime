/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The key editor's three folding cards (dev/design/061-settings-cards.md §5.1). */
class AccordionTest {

    @Test
    fun aTitleTappedOpensItsCardAndFoldsTheOpenOne() {
        val a = Accordion(3, 0)
        a.tap(2)
        assertEquals(2, a.open)
        a.tap(1)
        assertEquals(1, a.open)
    }

    @Test
    fun theOpenCardsTitleTappedFoldsItLeavingNoneOpen() {
        val a = Accordion(3, 1)
        a.tap(1)
        assertNull(a.open)
        a.tap(1)
        assertEquals(1, a.open)
    }

    @Test
    fun anErrorOpensItsCardWhateverWasOpen() {
        val none = Accordion(3, null)
        none.showError(2)
        assertEquals(2, none.open)
        // already open: stays open (a tap would fold it)
        none.showError(2)
        assertEquals(2, none.open)
        val other = Accordion(2, 0)
        other.showError(1)
        assertEquals(1, other.open)
    }

    @Test
    fun theTitlesStayPutWhileTheOpenCardHasItsLeastRoom() {
        // 360×640: about 420 dp for the cards, three titles and gaps 160: 260 left
        assertTrue(Accordion.fixedTitles(availablePx = 420, titlesPx = 160, minContentPx = 160))
        // exactly the least room
        assertTrue(Accordion.fixedTitles(320, 160, 160))
        assertFalse(Accordion.fixedTitles(319, 160, 160))
        // a phone on its side: about 250 dp
        assertFalse(Accordion.fixedTitles(250, 160, 160))
    }

    @Test
    fun theEditorOpensOnTheFirstCardOrNoneWhenTheWholeScrolls() {
        assertEquals(0, Accordion.opening(fixedTitles = true))
        assertNull(Accordion.opening(fixedTitles = false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun noCardsIsNoAccordion() {
        Accordion(0, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aCardOutOfRangeIsRefused() {
        Accordion(2, null).tap(2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun anOpeningCardOutOfRangeIsRefused() {
        Accordion(2, 2)
    }
}
