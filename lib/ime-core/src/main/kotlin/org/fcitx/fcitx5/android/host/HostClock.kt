/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/**
 * The host thread's clock and timers, fcitx's event loop's `addTimeEvent` (`CLOCK_MONOTONIC`): the
 * pause before a refine (30 ms), the input method name overlay (1 s), the group change's overlay
 * (30 ms). A task runs on the host thread after the event that scheduled it, never inside it, and
 * in the order of their times (ties in the order scheduled), so a timer is strictly ordered with
 * the keys around it (design §4). The app's is a single-thread `ScheduledExecutorService`; tests
 * use a virtual one (testFixtures `VirtualHostClock`).
 */
interface HostClock {
    /** Monotonic milliseconds. */
    fun uptimeMillis(): Long

    /** Runs [task] [delayMillis] from now (0: after the current event), unless cancelled first. */
    fun schedule(delayMillis: Long, task: () -> Unit): Cancellable
}

/** A timer scheduled on a [HostClock], fcitx's `EventSourceTime` held: [cancel] drops it if it has not run. */
fun interface Cancellable {
    fun cancel()
}
