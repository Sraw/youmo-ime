/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculationTest {

    private fun of(text: String) = Calculation.of(text)?.result

    @Test
    fun aSumBeforeTheEqualsSignIsWorkedOut() {
        assertEquals("42", of("12*3.5="))
        assertEquals("3", of("1+2＝"))
        assertEquals("7", of("1+2*3="))
        assertEquals("9", of("(1+2)*3="))
        assertEquals("2.5", of("5/2="))
    }

    @Test
    fun decimalsComeOutAsWritten() {
        assertEquals("0.3", of("0.1+0.2="))
        assertEquals("0.3333333333333333", of("1/3="))
        assertEquals("0.6666666666666667", of("2/3="))
        assertEquals("1", of("0.5*2="))
    }

    @Test
    fun theOperatorsAChineseKeyboardTypesAreRead() {
        assertEquals("6", of("2×3="))
        assertEquals("6", of("2x3="))
        assertEquals("2", of("6÷3="))
        assertEquals("5", of("２＋３＝"))
        assertEquals("9", of("（１＋２）＊３＝"))
        assertEquals("-1", of("2－3="))
    }

    @Test
    fun theSumIsTheRunBeforeTheEqualsSignThatIsOne() {
        assertEquals("36", of("价格12*3="))
        assertEquals("2", of("2026年1+1="))
        assertEquals("2", of("box1+1="))
        assertEquals("2", of("好的，1+1="))
        assertEquals("3", of("1 + 2 ="))
        // a space between two numbers parts them
        assertEquals("5", of("1 2+3="))
        assertEquals("5", of("1  2 + 3="))
    }

    @Test
    fun percentsPowersAndSigns() {
        assertEquals("30", of("200*15%="))
        assertEquals("1024", of("2^10="))
        assertEquals("-4", of("-2^2="))
        assertEquals("0.5", of("2^-1="))
        assertEquals("1", of("--1*1="))
        assertEquals("-6", of("-2*3="))
    }

    @Test
    fun thousandsSeparatorsAreReadOnlyWhereTheyAreThat() {
        assertEquals("2000", of("1,000*2="))
        assertEquals("5", of("1,2+3="))
    }

    @Test
    fun noSumNoResult() {
        assertNull(of("5="))
        assertNull(of("-5="))
        assertNull(of("50%="))
        assertNull(of("="))
        assertNull(of(""))
        assertNull(of("1+2"))
        assertNull(of("你好="))
        assertNull(of("1+="))
        // an open parenthesis left: the sum after it
        assertEquals("3", of("(1+2="))
    }

    @Test
    fun whatCannotBeWorkedOutOffersNothing() {
        assertNull(of("1/0="))
        assertNull(of("2^1000="))
        assertNull(of("2^0.5="))
        assertNull(of("0^-1="))
        assertNull(of("(((10^999)^999)^999)^999="))
    }

    @Test
    fun aHugeResultIsWrittenWithItsExponent() {
        assertEquals("1E+20", of("10^20="))
        assertEquals("1000000000000000", of("10^15="))
    }

    @Test
    fun theSumIsKnownAsWrittenForTheResultToTakeItsPlace() {
        assertEquals(Calculation.Worked("5", "1+2*2="), Calculation.of("价格1+2*2="))
        assertEquals(Calculation.Worked("3", "1 + 2 ＝"), Calculation.of("好 1 + 2 ＝"))
        assertEquals(Calculation.Worked("2", "1+1="), Calculation.of("box1+1="))
    }
}
