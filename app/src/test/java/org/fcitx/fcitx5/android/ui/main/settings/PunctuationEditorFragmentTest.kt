/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import org.fcitx.fcitx5.android.data.punctuation.PunctuationMapEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Of a key's cards, the first is what the key types. A card whose key was changed used to become
 * that first one without being asked to, and a key of several characters was saved though fcitx
 * drops it.
 */
class PunctuationEditorFragmentTest {

    private fun card(key: String, mapping: String) = PunctuationMapEntry(key, mapping, "")

    private val comma1 = card(",", "，")
    private val comma2 = card(",", "、")
    private val period = card(".", "。")

    @Test
    fun aKeyIsOneCharacterAsFcitxCountsThem() {
        assertTrue(isPunctuationKey("，"))
        assertTrue("one code point in two chars", isPunctuationKey("😀"))
        assertFalse(isPunctuationKey("ab"))
        assertFalse(isPunctuationKey(""))
    }

    @Test
    fun aCardChangedToAKeyWithCardsGoesAfterThemUnlessAskedToBeFirst() {
        // the edited card was at 0, ahead of the comma's two
        val others = listOf(comma1, comma2)
        assertEquals(2, punctuationCardPosition(others, ",", at = 0, first = false))
        assertEquals(0, punctuationCardPosition(others, ",", at = 0, first = true))
    }

    @Test
    fun uncheckingTheFirstCardHandsThePlaceToTheNext() {
        // the first comma card was at 0; the others are the period and the second comma
        val others = listOf(period, comma2)
        assertEquals(2, punctuationCardPosition(others, ",", at = 0, first = false))
    }

    @Test
    fun aCardStaysWhereItIsWhenItsPlaceAgreesWithTheBox() {
        val others = listOf(comma1, period)
        assertEquals("not first, and not asked to be", 2, punctuationCardPosition(others, ",", at = 2, first = false))
        assertEquals("first already: it stays ahead of another key's card", 0, punctuationCardPosition(listOf(period, comma2), ",", at = 0, first = true))
        assertEquals("the only card of its key", 1, punctuationCardPosition(others, ";", at = 1, first = false))
    }

    @Test
    fun aNewCardGoesLastOrFirstOfItsKey() {
        val others = listOf(comma1, period)
        assertEquals(2, punctuationCardPosition(others, ",", at = 2, first = false))
        assertEquals(0, punctuationCardPosition(others, ",", at = 2, first = true))
    }

    @Test
    fun onlyTheFirstOfSeveralCardsOfAKeyIsCheckedAsFirst() {
        val entries = listOf(comma1, period, comma2)
        assertTrue(entries.isFirstOfSeveral(0))
        assertFalse(entries.isFirstOfSeveral(2))
        assertFalse("a key's only card", entries.isFirstOfSeveral(1))
    }

    @Test
    fun theFirstBoxIsLeftCheckedOnlyWhileTheCardKeepsItsKey() {
        val entries = listOf(comma1, comma2, period)
        assertTrue(entries.firstBoxChecked(0, ","))
        assertTrue("read as the key is, trimmed", entries.firstBoxChecked(0, " , "))
        assertFalse(entries.firstBoxChecked(0, "."))
        assertFalse("not first", entries.firstBoxChecked(1, ","))
        assertFalse("a new card", entries.firstBoxChecked(null, ","))
        // the first comma card changed to the period, its box left alone: after the period's card,
        // which the period still types
        val others = listOf(comma2, period)
        assertEquals(2, punctuationCardPosition(others, ".", at = 0, first = entries.firstBoxChecked(0, ".")))
    }
}
