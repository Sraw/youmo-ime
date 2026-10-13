/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

/**
 * Whether the settings should say a build's data is old: [builtAt] is the day of the newest data
 * release in it (MainFragment's dataDay), not the build's. The offline build fetches nothing
 * itself, so new words reach it only with a new version, one a month: half a year is six missed.
 */
object DataAge {
    const val STALE_DAYS = 183
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** [builtAt] and [now] in epoch milliseconds; a clock behind the build is no age at all. */
    fun isStale(builtAt: Long, now: Long): Boolean = now - builtAt > STALE_DAYS * DAY_MS
}
