/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

/** Whether settings should say the data is old; [builtAt] is the newest data release's day, not the build's. */
object DataAge {
    const val STALE_DAYS = 183
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Epoch ms; a clock behind the build is no age. */
    fun isStale(builtAt: Long, now: Long): Boolean = now - builtAt > STALE_DAYS * DAY_MS
}
