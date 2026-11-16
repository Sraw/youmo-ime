/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.reading.CandidateCard
import org.fcitx.fcitx5.android.engine.reading.CharCard
import org.fcitx.fcitx5.android.engine.reading.WordCard
import org.fcitx.fcitx5.android.input.popup.CardNav.Focus
import org.fcitx.fcitx5.android.input.popup.CardNav.Head
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardNavTest {

    private val char = CharCard("行", emptyList(), null, null, null, null)
    private val word = CandidateCard.Word("yín háng", WordCard("银行", listOf("yín", "háng"), listOf("", ""), emptyList()))

    @Test
    @Uat("PY-124", "AX-16")
    fun aWordOpensItsCharactersAndComesBack() {
        val nav = CardNav(word)
        assertEquals(Head.COMPACT, nav.head)
        assertFalse(nav.expanded)
        assertEquals(Focus.NONE, nav.focusTarget)
        // not before it is open
        assertFalse(nav.pick(0))
        assertFalse(nav.back())
        assertTrue(nav.open())
        assertEquals(Head.WORD to Focus.WORD, nav.head to nav.focusTarget)
        assertTrue(nav.expanded)
        assertFalse(nav.open())
        assertTrue(nav.pick(1))
        assertEquals(Triple(Head.WORD_CHAR, 1, Focus.BACK), Triple(nav.head, nav.char, nav.focusTarget))
        assertFalse(nav.pick(0))
        assertTrue(nav.back())
        assertEquals(Triple(Head.WORD, -1, Focus.WORD), Triple(nav.head, nav.char, nav.focusTarget))
    }

    // opened from the line the window grows anew from the candidate; a character and the way back keep it where it is
    @Test
    @Uat("PY-144")
    fun onlyOpeningTheLineGrowsTheWindowAnew() {
        val nav = CardNav(word)
        assertFalse(nav.regrows)
        assertTrue(nav.open())
        assertTrue(nav.regrows)
        assertTrue(nav.pick(0))
        assertFalse(nav.regrows)
        assertTrue(nav.back())
        assertFalse(nav.regrows)
        assertTrue(nav.pick(1))
        assertFalse(nav.regrows)
    }

    // a character's card or the way back that took the window to the candidate's other side grows it anew from there
    @Test
    @Uat("PY-144")
    fun aStepToTheCandidatesOtherSideGrowsTheWindowAnew() {
        val nav = CardNav(word)
        assertTrue(nav.open())
        // opened: grown whatever the side
        assertTrue(nav.growsAnew(wasAbove = false, above = false))
        assertTrue(nav.pick(1))
        assertFalse(nav.growsAnew(wasAbove = true, above = true))
        assertFalse(nav.growsAnew(wasAbove = false, above = false))
        assertTrue(nav.growsAnew(wasAbove = false, above = true))
        assertTrue(nav.back())
        assertTrue(nav.growsAnew(wasAbove = true, above = false))
        assertFalse(nav.growsAnew(wasAbove = null, above = true))
    }

    @Test
    @Uat("PY-126")
    fun aCharacterOpensItsCard() {
        val nav = CardNav(CandidateCard.Char("háng / xíng…", char))
        assertTrue(nav.open())
        assertEquals(Head.CHAR to Focus.CARD, nav.head to nav.focusTarget)
        assertFalse(nav.pick(0))
    }

    @Test
    @Uat("PY-133")
    fun aLongCandidatesLineOpensNothing() {
        val nav = CardNav(CandidateCard.Line("我们去银行办业务吧", "wǒ men qù yín háng bàn yè wù ba"))
        assertEquals(Head.LINE, nav.head)
        assertFalse(nav.open())
        assertEquals(Head.LINE, nav.head)
        assertFalse(nav.expanded)
        assertFalse(nav.twoColumns(landscape = true, floating = false, paged = false))
    }

    @Test
    @Uat("HW-40")
    fun aCharacterLookedUpIsItsCardAtOnceAndNoCardItsTitle() {
        val looked = CardNav(CandidateCard.Looked(char))
        assertEquals(Head.LOOKED, looked.head)
        assertTrue(looked.expanded)
        assertFalse(looked.open())
        val none = CardNav(null)
        assertEquals(Head.TITLE, none.head)
        assertFalse(none.expanded)
        assertFalse(none.open())
    }

    @Test
    @Uat("LS-32", "HK-47")
    fun twoColumnsForACardDockedInLandscapeAlone() {
        val nav = CardNav(word)
        // the compact line: as narrow as a title
        assertFalse(nav.twoColumns(landscape = true, floating = false, paged = false))
        nav.open()
        assertTrue(nav.twoColumns(landscape = true, floating = false, paged = false))
        assertFalse(nav.twoColumns(landscape = true, floating = true, paged = false))
        assertFalse(nav.twoColumns(landscape = true, floating = false, paged = true))
        assertFalse(nav.twoColumns(landscape = false, floating = false, paged = false))
    }
}
