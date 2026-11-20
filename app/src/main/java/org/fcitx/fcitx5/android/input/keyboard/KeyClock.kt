/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmClock
import java.util.Calendar

/** One clock for the long press, the corners, a swipe's preview and what it types, so they agree on the minute. */
object KeyClock : LearnClock {
    @VisibleForTesting
    @Volatile
    var clock: LearnClock = JvmClock

    override fun now(): Long = clock.now()

    override fun offset(at: Long): Int = clock.offset(at)

    override fun calendar(): Calendar = clock.calendar()
}
