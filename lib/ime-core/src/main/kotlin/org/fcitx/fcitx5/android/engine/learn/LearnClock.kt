/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.learn

import java.util.TimeZone

/**
 * The time and the zone learning stamps its records with: the device's, or a test's. Read on the
 * thread that learns and on the one that folds the log.
 */
interface LearnClock {
    /** Milliseconds since the epoch. */
    fun now(): Long

    /** The zone's offset from UTC at [at], in milliseconds. */
    fun offset(at: Long): Int

    object System : LearnClock {
        override fun now() = java.lang.System.currentTimeMillis()
        override fun offset(at: Long) = TimeZone.getDefault().getOffset(at)
    }

    /**
     * When a record is written, as the log keeps it (impl/design-1a.md §4): the epoch [minute],
     * never before the last one's but where the clock was wrong a day or more ahead and is put
     * right; the typing [day], one more on the first commit of a later calendar day; the calendar
     * day ([localDay]) it counts as, never going back but where put right.
     */
    class Stamp(val minute: Int, val day: Int, val localDay: Int)

    companion object {
        const val MINUTE = 60_000L
        const val DAY = 86_400_000L
        const val DAY_MINUTES = 1440

        /**
         * The stamp of a record written now after what [state] holds; [commit]: one that makes a
         * typing day (a commit, English's too), the others carry the day as it is.
         */
        fun stamp(clock: LearnClock, state: LearnState, commit: Boolean): Stamp {
            val now = floorDiv(clock.now(), MINUTE).toInt()
            // only a commit puts a clock right: one that is not carries no local day, and the next
            // commit, finding the minute put right already, would not start the days anew
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

        /** The calendar day, in [clock]'s zone, of the epoch [minute]. */
        fun localDay(clock: LearnClock, minute: Int): Int {
            val at = minute * MINUTE
            return floorDiv(at + clock.offset(at), DAY).toInt()
        }

        /** The epoch minute of the start of [localDay] in [clock]'s zone, as of [near] (an offset may change that day). */
        fun midnight(clock: LearnClock, localDay: Int, near: Long): Int = floorDiv(localDay * DAY - clock.offset(near), MINUTE).toInt()

        /**
         * The fold's cut (PLAN §2.2 T): the earlier of local midnight and an hour ago, what U-88
         * may still take back staying after it.
         */
        fun cut(clock: LearnClock): Int {
            val now = clock.now()
            val minute = floorDiv(now, MINUTE).toInt()
            return minOf(midnight(clock, localDay(clock, minute), now), minute - HOUR_MINUTES)
        }

        private const val HOUR_MINUTES = 60

        // Math.floorDiv is API 24
        internal fun floorDiv(x: Long, y: Long): Long {
            val q = x / y
            return if ((x % y != 0L) && ((x xor y) < 0)) q - 1 else q
        }
    }
}
