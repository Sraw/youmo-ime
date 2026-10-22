/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LearnClock]'s stamps: typing days by the local calendar, minutes that never go back but where the clock is put right. */
class LearnClockTest {
    private val clock = FakeClock()
    private val log = LearnLog(null, clock)

    private fun commit() = log.record(listOf(row("ni", "你", "ni")))!!

    @Test
    fun aTypingDayIsACalendarDayTypedOn() {
        assertEquals(1, commit().day)
        // 10:00 to 23:59 the same day
        clock.minutes(13 * 60 + 59)
        assertEquals(1, commit().day)
        // past local midnight, UTC+8: 16:00 UTC
        clock.minutes(1)
        assertEquals(2, commit().day)
        // a week without typing: one day more
        clock.days(7)
        assertEquals(3, commit().day)
    }

    @Test
    fun aClockBackByMinutesStampsTheLastMinute() {
        val first = commit()
        clock.minutes(-30)
        val second = commit()
        assertEquals(first.minute, second.minute)
        assertEquals(first.day, second.day)
    }

    @Test
    fun aClockBackOverMidnightCountsNoDayBack() {
        clock.minutes(13 * 60 + 59) // 23:59
        commit()
        clock.minutes(2) // 00:01, day 2
        assertEquals(2, commit().day)
        clock.minutes(-10) // 23:51 the day before
        val back = commit()
        assertEquals(2, back.day)
        assertEquals(LearnClock.localDay(clock, back.minute), back.localDay)
    }

    @Test
    fun aClockADayAheadPutRightTakesTheTrueTime() {
        commit()
        clock.days(3)
        val ahead = commit()
        assertEquals(2, ahead.day)
        clock.days(-3)
        clock.minutes(5)
        val right = commit()
        // put right: the true minute, no typing day more, the calendar day as it is now
        assertEquals(clock.at / LearnClock.MINUTE, right.minute.toLong())
        assertEquals(2, right.day)
        assertTrue(right.localDay < ahead.localDay)
        // and the next calendar day after it is a typing day again
        clock.days(1)
        assertEquals(3, commit().day)
    }

    // review-1a-core S1: a forget (or pin, undo) first after the clock is put right does not put it right itself
    @Test
    fun aClockPutRightThenAForgetStillStartsTheDaysAnew() {
        commit()
        clock.days(365)
        val ahead = commit()
        clock.days(-365)
        log.forget("你")
        log.pin(Code(Kinds.FULL, "ni"), "你", "ni", on = true)
        val right = commit()
        assertEquals(ahead.day, right.day)
        assertTrue(right.localDay < ahead.localDay)
        clock.days(1)
        assertEquals(ahead.day + 1, commit().day)
    }

    @Test
    fun aZoneChangeMovesTheCalendarDay() {
        clock.minutes(13 * 60) // 23:00 in UTC+8
        commit()
        clock.zone = 9 * FakeClock.HOUR // 00:00 in UTC+9
        assertEquals(2, commit().day)
        clock.zone = 8 * FakeClock.HOUR // back: no day back, none more
        assertEquals(2, commit().day)
    }

    @Test
    fun aPinOnANewDayCountsNoTypingDay() {
        commit()
        clock.days(1)
        log.pin(Code(Kinds.FULL, "dz"), "地址", "", on = true)
        assertEquals(1, log.state.day)
        assertEquals(2, commit().day)
    }

    @Test
    fun theCutIsLocalMidnightOrAnHourAgo() {
        // 10:00: midnight is earlier
        assertEquals(LearnClock.midnight(clock, LearnClock.localDay(clock, (clock.at / LearnClock.MINUTE).toInt()), clock.at), LearnClock.cut(clock))
        // 00:30: an hour ago is earlier
        clock.minutes(14 * 60 + 30)
        assertEquals((clock.at / LearnClock.MINUTE).toInt() - 60, LearnClock.cut(clock))
    }

    @Test
    fun beforeTheEpochFloors() {
        assertEquals(-1L, LearnClock.floorDiv(-1, LearnClock.DAY))
        assertEquals(0L, LearnClock.floorDiv(0, LearnClock.DAY))
        assertEquals(-2L, LearnClock.floorDiv(-LearnClock.DAY - 1, LearnClock.DAY))
    }
}
