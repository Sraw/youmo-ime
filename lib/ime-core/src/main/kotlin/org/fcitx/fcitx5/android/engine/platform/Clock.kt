/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.platform

import org.fcitx.fcitx5.android.engine.learn.LearnClock

/**
 * The engine's one clock: the time and zone learning stamps its records with and the phrases are
 * dated by ([LearnClock]), and monotonic milliseconds, what a table reads how soon a space follows
 * a code that committed itself by. The device's ([org.fcitx.fcitx5.android.engine.platform.jvm.JvmClock]),
 * or a test's or a replay's, so the same keys type the same way.
 */
// no DefaultImpls bridge for LearnClock's calendar(): nothing compiled against an older Clock calls one
@JvmDefaultWithoutCompatibility
interface Clock : LearnClock {
    /** Monotonic milliseconds, from any start. */
    fun uptimeMillis(): Long
}
