/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.platform.jvm

import org.fcitx.fcitx5.android.engine.platform.Clock
import java.util.Calendar
import java.util.TimeZone

/** The device's [Clock]: the system's time and zone, and the JVM's monotonic clock. */
object JvmClock : Clock {
    private const val NANOS_PER_MILLI = 1_000_000L

    override fun now() = System.currentTimeMillis()

    override fun offset(at: Long) = TimeZone.getDefault().getOffset(at)

    // the device's own, as the phrases always read it
    override fun calendar(): Calendar = Calendar.getInstance()

    override fun uptimeMillis() = System.nanoTime() / NANOS_PER_MILLI
}
