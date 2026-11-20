/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import java.util.Calendar
import java.util.SimpleTimeZone

/** The time and zone learning stamps records with: the device's or a test's. Read on the learning thread and the log-folding one. */
interface LearnClock {
    fun now(): Long

    /** The zone's offset from UTC at [at], in milliseconds. */
    fun offset(at: Long): Int

    /** The calendar [now] in [offset]'s zone, for the date and time phrases (`$year`, `rq`, `sj`, `xq`): a replay uses its clock's, never the system's. */
    fun calendar(): Calendar {
        val at = now()
        return Calendar.getInstance(SimpleTimeZone(offset(at), "learn")).apply { timeInMillis = at }
    }

    /**
     * A record's write time as the log keeps it: the epoch [minute], never before the last one's unless the
     * clock was a day or more ahead and is put right; the typing [day], one more on the first commit of a
     * later calendar day; the calendar day ([localDay]) it counts as, never going back unless put right.
     */
    class Stamp(val minute: Int, val day: Int, val localDay: Int)

    companion object {
        const val MINUTE = 60_000L
        const val DAY = 86_400_000L
        const val DAY_MINUTES = 1440

        /** The stamp of a record written now after [state]; [commit]: a record that makes a typing day (any commit, English too), others carry the day. */
        fun stamp(clock: LearnClock, state: LearnState, commit: Boolean): Stamp {
            val now = floorDiv(clock.now(), MINUTE).toInt()
            // only a commit puts a clock right: others carry no local day, and the next commit, finding the minute
            // already put right, would not restart the days
            val putRight = commit && state.lastMinute != Int.MIN_VALUE && now < state.lastMinute - DAY_MINUTES
            val minute = if (putRight || state.lastMinute == Int.MIN_VALUE) now else maxOf(state.lastMinute, now)
            val local = localDay(clock, minute)
            val localDay = if (putRight || state.localDay == Int.MIN_VALUE) local else maxOf(state.localDay, local)
            val day = when {
                !commit -> state.day
                state.day == 0 -> 1
                !putRight && localDay > state.localDay -> state.day + 1
                else -> state.day
            }
            return Stamp(minute, day, if (commit) localDay else state.localDay)
        }

        fun localDay(clock: LearnClock, minute: Int): Int {
            val at = minute * MINUTE
            return floorDiv(at + clock.offset(at), DAY).toInt()
        }

        /** The epoch minute [localDay] starts in [clock]'s zone, as of [near] (an offset may change that day). */
        fun midnight(clock: LearnClock, localDay: Int, near: Long): Int = floorDiv(localDay * DAY - clock.offset(near), MINUTE).toInt()

        /** The fold's cut: the earlier of local midnight and an hour ago, so what may still be cleared stays after it. */
        fun cut(clock: LearnClock): Int {
            val now = clock.now()
            val minute = floorDiv(now, MINUTE).toInt()
            return minOf(midnight(clock, localDay(clock, minute), now), minute - HOUR_MINUTES)
        }

        private const val HOUR_MINUTES = 60

        // by hand, not Math.floorDiv: API floor below 24
        internal fun floorDiv(x: Long, y: Long): Long {
            val q = x / y
            return if ((x % y != 0L) && ((x xor y) < 0)) q - 1 else q
        }
    }
}
