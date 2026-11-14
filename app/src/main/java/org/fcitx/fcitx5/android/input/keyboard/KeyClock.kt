/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmClock
import java.util.Calendar

/**
 * The time the keys' date and time variables are filled in with (061-text-variables): one clock for the long press
 * (KeyResolver), the corners, a swipe's preview and what it types, so they agree on the minute; the device's, or a
 * test's ([clock], read at each ask, so resolvers made before it is set read it too).
 */
object KeyClock : LearnClock {
    @VisibleForTesting
    @Volatile
    var clock: LearnClock = JvmClock

    override fun now(): Long = clock.now()

    override fun offset(at: Long): Int = clock.offset(at)

    override fun calendar(): Calendar = clock.calendar()
}
