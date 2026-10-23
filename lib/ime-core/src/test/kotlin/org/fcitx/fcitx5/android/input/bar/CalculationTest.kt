/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculationTest {

    private fun of(text: String) = Calculation.of(text)?.result

    @Test
    fun whatASumWorksOutToAndHowItIsWritten() = assertRows(
        "a sum before the equals sign is worked out" to {
            assertEquals("42", of("12*3.5="))
            assertEquals("3", of("1+2＝"))
            assertEquals("7", of("1+2*3="))
            assertEquals("9", of("(1+2)*3="))
            assertEquals("2.5", of("5/2="))
        },
        "decimals come out as written" to {
            assertEquals("0.3", of("0.1+0.2="))
            assertEquals("0.3333333333333333", of("1/3="))
            assertEquals("0.6666666666666667", of("2/3="))
            assertEquals("1", of("0.5*2="))
        },
        "the operators a Chinese keyboard types are read" to {
            assertEquals("6", of("2×3="))
            assertEquals("6", of("2x3="))
            assertEquals("2", of("6÷3="))
            assertEquals("5", of("２＋３＝"))
            assertEquals("9", of("（１＋２）＊３＝"))
            assertEquals("-1", of("2－3="))
        },
        "the sum is the run before the equals sign that is one" to {
            assertEquals("36", of("价格12*3="))
            assertEquals("2", of("2026年1+1="))
            assertEquals("2", of("box1+1="))
            assertEquals("2", of("好的，1+1="))
            assertEquals("3", of("1 + 2 ="))
            // a space between two numbers parts them
            assertEquals("5", of("1 2+3="))
            assertEquals("5", of("1  2 + 3="))
        },
        "percents powers and signs" to {
            assertEquals("30", of("200*15%="))
            assertEquals("1024", of("2^10="))
            assertEquals("-4", of("-2^2="))
            assertEquals("0.5", of("2^-1="))
            assertEquals("1", of("--1*1="))
            assertEquals("-6", of("-2*3="))
        },
        "thousands separators are read only where they are that" to {
            assertEquals("2000", of("1,000*2="))
            assertEquals("5", of("1,2+3="))
        },
        "a huge result is written with its exponent" to {
            assertEquals("1E+20", of("10^20="))
            assertEquals("1000000000000000", of("10^15="))
        },
        "steps are not rounded to what is shown" to {
            assertEquals("1", of("1/3*3="))
            assertEquals("10", of("10/3*3="))
        },
        "zero is written plain" to {
            assertEquals("0", of("0.5-0.5="))
            assertEquals("0", of("1.50*0="))
        },
        "a tiny result is written with its exponent" to {
            assertEquals("1E-999", of("10^-999="))
            assertEquals("0.000001", of("10^-6="))
            assertEquals("1E-7", of("10^-7="))
            assertEquals("0.03333333333333333", of("1/30="))
            // a power of a power is bounded by its result, not only by its exponent
            assertNull(of("(10^-999)^999="))
            assertNull(of("((10^-999)^999)^999="))
        },
    )

    @Test
    fun whatIsNoSumOrCannotBeWorkedOutOffersNothing() = assertRows(
        "no sum no result" to {
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
        },
        "what cannot be worked out offers nothing" to {
            assertNull(of("1/0="))
            assertNull(of("2^1000="))
            assertNull(of("2^0.5="))
            assertNull(of("0^-1="))
            assertNull(of("(((10^999)^999)^999)^999="))
        },
        "the longest sum decides even where it cannot be worked out" to {
            assertNull(of("1/0+5="))
            assertNull(of("2^1000+1="))
            assertNull(of("2(3+4)="))
            assertNull(of("1.5.2+1="))
            assertNull(of("1.2.3+1="))
            assertNull(of("50%2+1="))
        },
    )

    @Test
    fun aNumberMayStartWithItsPoint() {
        assertEquals("1.5", of(".5+1="))
        assertEquals(Calculation.Worked("1.5", ".5+1="), Calculation.of("价格.5+1="))
    }

    @Test
    fun aWindowThatMayHaveCutTheFirstNumberOffersNoSumAtItsStart() {
        assertNull(Calculation.of("23+1=", cut = true))
        // the window opening on an operator, a thousands comma or a space: still maybe mid-number
        assertNull(Calculation.of("+23+1=", cut = true))
        assertNull(Calculation.of(",345+1=", cut = true))
        assertNull(Calculation.of(" 23+1=", cut = true))
        assertEquals(Calculation.Worked("2", "1+1="), Calculation.of("年1+1=", cut = true))
        assertEquals(Calculation.Worked("24", "23+1="), Calculation.of("23+1="))
    }

    @Test
    fun theSumIsKnownAsWrittenForTheResultToTakeItsPlace() {
        assertEquals(Calculation.Worked("5", "1+2*2="), Calculation.of("价格1+2*2="))
        assertEquals(Calculation.Worked("3", "1 + 2 ＝"), Calculation.of("好 1 + 2 ＝"))
        assertEquals(Calculation.Worked("2", "1+1="), Calculation.of("box1+1="))
    }
}
