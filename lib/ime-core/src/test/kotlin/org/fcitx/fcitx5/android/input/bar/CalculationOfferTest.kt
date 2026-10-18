/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculationOfferTest {

    private val worked = Calculation.Worked(result = "42", sum = "12*3.5=")

    // "12*3.5=" typed into an empty field: the `=` ends at 7
    private fun offer() = CalculationOffer(worked, at = 7)

    @Test
    fun theCursorAtTheEqualsSignKeepsIt() {
        assertTrue(offer().keeps(7, 7))
    }

    @Test
    fun lateReportsOfTheKeysBeforeTheEqualsSignKeepIt() {
        val offer = offer()
        assertTrue(offer.keeps(5, 5))
        assertTrue(offer.keeps(6, 6))
        assertTrue(offer.keeps(7, 7))
    }

    @Test
    fun movingBackOnceTheEqualsSignWasReachedDropsIt() {
        val offer = offer()
        assertTrue(offer.keeps(7, 7))
        assertFalse(offer.keeps(6, 6))
    }

    @Test
    fun aSelectionOrTheCursorPastTheEqualsSignDropsIt() {
        assertFalse(offer().keeps(3, 7))
        assertFalse(offer().keeps(7, 7 + 1))
        assertFalse(offer().keeps(8, 8))
    }

    @Test
    fun aTapPutsTheResultInOnlyWhileTheSumIsStillBeforeTheCursor() {
        val offer = offer()
        assertTrue(offer.stillThere("12*3.5="))
        // the `=` deleted before its report came
        assertFalse(offer.stillThere("x12*3.5"))
        assertFalse(offer.stillThere(null))
    }

    @Test
    fun aFieldThatMayNotBeReadIsNotRead() {
        var asked = 0
        val read = { _: Int -> asked++; "1+1=" }
        assertNull(CalculationOffer.read(sensitive = true, notText = false, chars = 128, textBefore = read))
        assertNull(CalculationOffer.read(sensitive = false, notText = true, chars = 128, textBefore = read))
        assertEquals(0, asked)
    }

    @Test
    fun anOrdinaryFieldIsReadForTheSum() {
        val worked = CalculationOffer.read(sensitive = false, notText = false, chars = 128) { "1+1=" }
        assertEquals("2", worked?.result)
    }

    @Test
    fun aReadThatFillsTheWindowMayHaveCutTheFirstNumber() {
        // "...1234+1=" read as its last 6 characters: "234+1=" is not the sum written
        assertNull(CalculationOffer.read(sensitive = false, notText = false, chars = 6) { "234+1=" })
        assertEquals("235", CalculationOffer.read(sensitive = false, notText = false, chars = 7) { "234+1=" }?.result)
    }
}
