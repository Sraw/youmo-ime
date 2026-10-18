/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

class PhraseBookTest {

    @Test
    fun eachChangeIsSavedWhole() {
        val saved = ArrayList<String>()
        val book = PhraseBook(CustomPhrases.parse("yx,1=邮箱")) { saved += it.all.joinToString(" ") }
        book.pin("yx", "信箱")
        book.pin("dh", "电话")
        book.remove("yx", "邮箱")
        assertEquals(listOf("yx,1=信箱 yx,1=邮箱", "dh,1=电话 yx,1=信箱 yx,1=邮箱", "dh,1=电话 yx,1=信箱"), saved)
        assertEquals(book.phrases.all.joinToString(" "), saved.last())
    }

    private val saturday: Calendar = GregorianCalendar(2026, Calendar.OCTOBER, 17, 9, 5, 0)

    private fun PhraseBook.offered(key: String) = phrases.offered(key, saturday).map { "${it.index}:${it.text}" }

    @Test
    fun theDateTimeAndWeekdayAreBuiltInAfterTheFirstCandidate() {
        val book = PhraseBook(builtIn = CustomPhrases.BUILT_IN)
        assertEquals(listOf("1:2026年10月17日", "2:2026-10-17", "3:二〇二六年十月十七日"), book.offered("rq"))
        assertEquals(listOf("1:09:05", "2:09时05分"), book.offered("sj"))
        assertEquals(listOf("1:星期六", "2:周六"), book.offered("xq"))
    }

    @Test
    fun theUsersPhrasesUnderAKeyTakeItsPlace() {
        val book = PhraseBook(CustomPhrases.parse("rq,1=日期"), CustomPhrases.BUILT_IN)
        assertEquals(listOf("0:日期"), book.offered("rq"))
        assertEquals(listOf("1:09:05", "2:09时05分"), book.offered("sj"))
    }

    @Test
    fun aBuiltInPhraseDeletedIsTurnedOffInTheUsersFileAndStaysGone() {
        val saved = ArrayList<String>()
        val book = PhraseBook(builtIn = CustomPhrases.BUILT_IN) { saved += it.all.joinToString(" ") }
        book.remove("xq", "#星期\$weekday_cn")
        assertEquals(listOf("xq,-2=#星期\$weekday_cn xq,3=#周\$weekday_cn"), saved)
        assertEquals(listOf("2:周六"), book.offered("xq"))
        book.remove("xq", "#周\$weekday_cn")
        assertEquals(emptyList<String>(), book.offered("xq"))
    }

    @Test
    fun pinningUnderABuiltInKeyKeepsItsPhrases() {
        val saved = ArrayList<String>()
        val book = PhraseBook(builtIn = CustomPhrases.BUILT_IN) { saved += it.all.joinToString(" ") }
        book.pin("sj", "时间")
        assertEquals(listOf("0:时间", "1:09:05", "2:09时05分"), book.offered("sj"))
        assertEquals("sj,1=时间 sj,2=#\$fullhour:\$minute sj,3=#\$fullhour时\$minute分", saved.single())
    }

    @Test
    fun theBuiltInOnesAreNotOfferedWhereTheyAreOff() {
        val book = PhraseBook(CustomPhrases.parse("yx,1=邮箱"), CustomPhrases.BUILT_IN)
        assertEquals(listOf("yx"), book.offered(builtIns = false).all.map { it.key })
        // pinned there, the built-in ones are kept for full pinyin, not offered here
        book.pin("sj", "三家")
        assertEquals(listOf("0:三家"), book.offered(builtIns = false).offered("sj", saturday).map { "${it.index}:${it.text}" })
        assertEquals(listOf("0:三家", "1:09:05", "2:09时05分"), book.offered("sj"))
    }

    @Test
    fun theBuiltInOnesAChangeInFullPinyinMadeTheUsersAreNotOfferedElsewhere() {
        val book = PhraseBook(builtIn = CustomPhrases.BUILT_IN)
        book.pin("sj", "时间")
        book.remove("xq", "#周\$weekday_cn")
        val elsewhere = book.offered(builtIns = false)
        assertEquals(listOf("0:时间"), elsewhere.offered("sj", saturday).map { "${it.index}:${it.text}" })
        assertEquals(emptyList<CustomPhrases.Offered>(), elsewhere.offered("xq", saturday))
    }

    @Test
    fun builtInOnesDeletedAfterAPinStayGoneWithThePin() {
        val book = PhraseBook(builtIn = CustomPhrases.BUILT_IN)
        book.pin("sj", "时间")
        book.remove("sj", "#\$fullhour:\$minute")
        book.remove("sj", "#\$fullhour时\$minute分")
        book.remove("sj", "时间")
        assertEquals(emptyList<String>(), book.offered("sj"))
        // kept turned off, for the settings to turn on again
        assertEquals(listOf(false, false), book.phrases.all.filter { it.key == "sj" }.map { it.enabled })
    }

    @Test
    fun theUsersOwnPhraseReadingAsABuiltInOneIsDeleted() {
        val saved = ArrayList<String>()
        val book = PhraseBook(CustomPhrases.parse("rq,1=日期\nrq,2=#\$year年\$month月\$day日"), CustomPhrases.BUILT_IN) { saved += it.all.joinToString(" ") }
        book.remove("rq", "#\$year年\$month月\$day日")
        // turned off, not deleted: once adopted, a built-in one reads the same, and must stay gone
        assertEquals(listOf("rq,1=日期 rq,-2=#\$year年\$month月\$day日"), saved)
        assertEquals(listOf("0:日期"), book.offered("rq"))
    }

    @Test
    fun theUsersOwnLastPhraseDeletedGivesTheKeyBackToTheBuiltInOnes() {
        val book = PhraseBook(CustomPhrases.parse("sj,1=三家"), CustomPhrases.BUILT_IN)
        book.remove("sj", "三家")
        assertEquals(listOf("1:09:05", "2:09时05分"), book.offered("sj"))
    }
}
