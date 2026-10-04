/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

/**
 * Whether the settings should say a build's data is old (dev/TRAINING-PLAN.md 12.3). The offline
 * build cannot fetch anything itself, so new words reach it only with a new version or a word pack
 * the user downloads; the data is refreshed every quarter, so half a year is two refreshes missed.
 */
object DataAge {
    const val STALE_DAYS = 183
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** [builtAt] and [now] in epoch milliseconds; a clock behind the build is no age at all. */
    fun isStale(builtAt: Long, now: Long): Boolean = now - builtAt > STALE_DAYS * DAY_MS
}
