/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.phrase.TextVariables.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.GregorianCalendar

class TextVariablesTest {
    private val now: Calendar = GregorianCalendar(2023, Calendar.JULY, 11, 23, 16, 6)

    @Test
    fun everyVariableIsFilledIn() {
        val morning = GregorianCalendar(2009, Calendar.JANUARY, 4, 0, 5, 0)
        val expected = mapOf(
            "year" to "2009", "year_yy" to "09", "month" to "1", "month_mm" to "01", "day" to "4", "day_dd" to "04",
            "weekday" to "0", "fullhour" to "00", "halfhour" to "12", "ampm" to "AM", "minute" to "05", "second" to "00",
            "year_cn" to "二〇〇九", "year_yy_cn" to "〇九", "month_cn" to "一", "day_cn" to "四", "weekday_cn" to "日",
            "fullhour_cn" to "零", "halfhour_cn" to "十二", "ampm_cn" to "上午", "minute_cn" to "零五", "second_cn" to "零",
        )
        assertEquals(expected.keys, TextVariables.VARIABLES.keys)
        assertEquals(TextVariables.VARIABLES.keys.toList(), TextVariables.NAMES)
        for ((name, value) in expected) assertEquals(name, value, TextVariables.expand("\${$name}", morning))
        assertEquals("二十", TextVariables.chineseNumber(20, leadingZero = false))
        assertEquals("三十一", TextVariables.chineseNumber(31, leadingZero = true))
        assertEquals("", TextVariables.expand("", morning))
        assertEquals("a\$", TextVariables.expand("a\$", morning))
        assertEquals("2009年1月4日", CustomPhrases.evaluate("\$year年\$month月\$day日", morning))
    }

    @Test
    fun aDollarThatNamesNothingAndANameNotKnown() {
        assertEquals("\$5 \$9 ! \${open", TextVariables.expand("\$\$5 \$9 \$unknown! \${open", now))
        assertEquals("2023\$", TextVariables.expand("\$year\$", now))
        assertEquals("ab", TextVariables.expand("a\${lua:x}b", now))
        assertEquals("ab", TextVariables.expand("a\${}b", now))
        assertEquals("23:16", TextVariables.expand("\${fullhour}:\$minute", now))
    }

    // what the editor marks is what typing does: one scan for both
    @Test
    fun problemsAreWhatExpandDoes() {
        assertEquals(listOf(Problem("\$yaer", dropped = true)), TextVariables.problems("\$yaer年"))
        assertEquals(listOf(Problem("\$5", dropped = false)), TextVariables.problems("\$5"))
        assertEquals(listOf(Problem("\$", dropped = false)), TextVariables.problems("price \$"))
        assertEquals(listOf(Problem("\${lua:x}", dropped = true)), TextVariables.problems("\${lua:x}"))
        assertEquals(listOf(Problem("\${open", dropped = false)), TextVariables.problems("a\${open"))
        assertEquals(emptyList<Problem>(), TextVariables.problems("\$year年\$\$\${month}"))
        for (t in listOf("\$yaer年", "\$5", "price \$", "\${lua:x}", "a\${open", "\$\$x", "\$year\$\$5 \${day")) {
            val expanded = TextVariables.expand(t, now)
            for (p in TextVariables.problems(t)) {
                // a dropped one is not in what is typed, one kept is, as written
                if (p.dropped) assertFalse("$t: ${p.written}", p.written in expanded) else assertTrue("$t: ${p.written}", p.written in expanded)
            }
        }
    }

    @Test
    fun whetherATextFillsAnythingIn() {
        assertTrue(TextVariables.fillsIn("\$year"))
        assertTrue(TextVariables.fillsIn("\$yaer"))
        assertTrue(TextVariables.fillsIn("US\$\$5"))
        assertFalse(TextVariables.fillsIn("US\$5"))
        assertFalse(TextVariables.fillsIn("\$5 \$"))
        assertFalse(TextVariables.fillsIn("plain"))
    }

    // the help's table names each of them, in the editor's order
    // contract: docs/words.md's table of the variables (its 变量 column) against TextVariables.NAMES
    @Test
    fun theHelpTablesEveryVariable() {
        val lines = File("../../docs/words.md").readLines().dropWhile { !it.startsWith("| 变量 |") }.takeWhile { it.startsWith("|") }
        val named = lines.flatMap { line -> Regex("`\\$([a-z_]+)`").findAll(line.split("|")[1]).map { it.groupValues[1] } }
        assertEquals(TextVariables.NAMES, named)
    }
}
