/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.platform

import org.fcitx.fcitx5.android.engine.learn.LearnClock

/** The engine's one clock: learning's time and zone ([LearnClock]) and monotonic ms for table timing; swappable for tests and replays so the same keys type the same. */
@JvmDefaultWithoutCompatibility
interface Clock : LearnClock {
    /** Monotonic milliseconds, from any start. */
    fun uptimeMillis(): Long
}
