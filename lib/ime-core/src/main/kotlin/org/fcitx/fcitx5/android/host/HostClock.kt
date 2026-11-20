/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/** The host thread's timers (fcitx's `addTimeEvent`): run after the scheduling event, never inside, in time order (ties as scheduled), so timers are ordered with keys. */
interface HostClock {
    fun uptimeMillis(): Long

    /** 0: after the current event. */
    fun schedule(delayMillis: Long, task: () -> Unit): Cancellable
}

/** fcitx's `EventSourceTime`: [cancel] drops it if not run yet. */
fun interface Cancellable {
    fun cancel()
}
