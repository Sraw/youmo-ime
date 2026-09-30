/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

class CustomPhrasesTest {

    // what fcitx5-chinese-addons' own test takes as now: Tuesday 2023-07-11 23:16:06
    private val now: Calendar = GregorianCalendar(2023, Calendar.JULY, 11, 23, 16, 6)

    @Test
    fun aPhraseIsOfferedWhereItsOrderSays() {
        val phrases = CustomPhrases.parse(
            """
            ; a comment
            # and another
            yx,3=a@b.c
            yx,1=邮箱
            yx,-2=turned off
            yx,1=邮箱
            not a phrase
            yx,0=no order
            y1,1=not a key
            ,1=no key
            yx,1x=no order
            yx,99999999999=too big
            dh,2=电话
            """.trimIndent(),
        )
        // turned off and repeated ones left out, the rest in order
        assertEquals(listOf(0 to "邮箱", 2 to "a@b.c"), phrases.lookup("yx", now))
        assertEquals(listOf(1 to "电话"), phrases.lookup("dh", now))
        assertEquals(emptyList<Pair<Int, String>>(), phrases.lookup("y", now))
        assertTrue(CustomPhrases.EMPTY.isEmpty)
    }

    @Test
    fun anEmptyValueTakesTheLinesAfterIt() {
        val phrases = CustomPhrases.parse("sig,1=\nfirst\n# not a comment here\n\nlast\nsig,2=one line\noff,-1=\nhidden\nx,1=y\n")
        assertEquals(listOf(0 to "first\n# not a comment here\n\nlast", 1 to "one line"), phrases.lookup("sig", now))
        assertEquals(listOf(0 to "y"), phrases.lookup("x", now))
        assertEquals(emptyList<Pair<Int, String>>(), phrases.lookup("off", now))
        assertEquals(emptyList<Pair<Int, String>>(), CustomPhrases.parse("k,1=\"\"\nfoo\nk,2=bar").lookup("foo", now))
        assertEquals(listOf(1 to "bar"), CustomPhrases.parse("k,1=\"\"\nfoo\nk,2=bar").lookup("k", now))
        assertEquals(listOf(0 to "a="), CustomPhrases.parse("k,1=a=\nfoo").lookup("k", now))
        // the last newline ends the last line, as libime reads it, with either ending
        for (end in listOf("\n", "\r\n", "")) {
            assertEquals(listOf(0 to "first\nlast"), CustomPhrases.parse("sig,1=\nfirst\nlast$end").lookup("sig", now))
        }
    }

    @Test
    fun aQuotedValueIsUnescaped() {
        assertEquals("a\"b\\c\nd", CustomPhrases.unquote("\"a\\\"b\\\\c\\nd\""))
        // what fcitx's editor writes for a line pasted from Windows, a tab and the like
        assertEquals("a\r\nb\tc\u000c\u000b", CustomPhrases.unquote("\"a\\r\\nb\\tc\\f\\v\""))
        // another character after a backslash is itself, as fcitx reads it
        assertEquals("ax", CustomPhrases.unquote("\"a\\x\""))
        // not quoted, or not escaped right: as it is
        for (raw in listOf("abc", "\"", "\"a\"b\"", "\"a\\\"")) assertEquals(raw, CustomPhrases.unquote(raw))
        assertEquals(listOf(0 to "two\nlines"), CustomPhrases.parse("q,1=\"two\\nlines\"").lookup("q", now))
    }

    @Test
    fun aDynamicPhraseIsFilledInWhenOffered() {
        val phrases = CustomPhrases.parse(
            "rq,1=#\$year-\$month_mm-\${day_dd}\nrq,2=#\${year_cn}年\${month_cn}月\${day_cn}日 星期\$weekday_cn\n" +
                "sj,1=#\$fullhour:\$minute:\$second \$ampm\nsj,2=#\$ampm_cn\$halfhour_cn点\$minute_cn分\$second_cn秒\n" +
                "d,1=#\$\$5 \$9 \$unknown! \${open\n",
        )
        assertEquals(listOf(0 to "2023-07-11", 1 to "二〇二三年七月十一日 星期二"), phrases.lookup("rq", now))
        assertEquals(listOf(0 to "23:16:06 PM", 1 to "下午十一点十六分零六秒"), phrases.lookup("sj", now))
        assertEquals(listOf(0 to "\$5 \$9 ! \${open"), phrases.lookup("d", now))
    }

    @Test
    fun everyVariableIsFilledIn() {
        val morning = GregorianCalendar(2009, Calendar.JANUARY, 4, 0, 5, 0)
        val expected = mapOf(
            "year" to "2009", "year_yy" to "09", "month" to "1", "month_mm" to "01", "day" to "4", "day_dd" to "04",
            "weekday" to "0", "fullhour" to "00", "halfhour" to "12", "ampm" to "AM", "minute" to "05", "second" to "00",
            "year_cn" to "二〇〇九", "year_yy_cn" to "〇九", "month_cn" to "一", "day_cn" to "四", "weekday_cn" to "日",
            "fullhour_cn" to "零", "halfhour_cn" to "十二", "ampm_cn" to "上午", "minute_cn" to "零五", "second_cn" to "零",
        )
        assertEquals(expected.keys, CustomPhrases.VARIABLES.keys)
        for ((name, value) in expected) assertEquals(name, value, CustomPhrases.evaluate("\${$name}", morning))
        assertEquals("二十", CustomPhrases.chineseNumber(20, leadingZero = false))
        assertEquals("三十一", CustomPhrases.chineseNumber(31, leadingZero = true))
        assertEquals("", CustomPhrases.evaluate("", morning))
        assertEquals("a\$", CustomPhrases.evaluate("a\$", morning))
    }

    // a candidate ending in … reads only part of the input
    private fun CustomPhrases.place(key: String, candidates: List<String>) =
        place(key, { now }, candidates, { it.removeSuffix("…") }, { !it.endsWith("…") }, { "+$it" })

    @Test
    fun phrasesGoWhereTheirOrderSaysAndACandidateOfTheSameTextMovesThere() {
        val phrases = CustomPhrases.parse("ab,1=甲\nab,3=乙\nab,9=丙\nab,2=丁\nab,1=戊\nab,2=己")
        // of one order, in the file's order; one of the phrase's text that reads less is left out
        assertEquals(listOf("+甲", "+戊", "+丁", "+己", "乙", "x", "y", "+丙"), phrases.place("ab", listOf("x", "乙", "y", "甲…")))
        assertEquals(listOf("x"), phrases.place("abc", listOf("x")))
        // the time is asked only for a key with phrases
        assertEquals(listOf("x"), phrases.place("abc", { error("asked") }, listOf("x"), { it }, { true }, { it }))
        assertEquals(listOf("x"), CustomPhrases.parse("ab,-1=甲").place("ab", listOf("x")))
    }
}
