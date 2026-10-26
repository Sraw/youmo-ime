/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.learn.LearnClock

/**
 * A [HostClock] that moves only when told ([advance]), and the [LearnClock] learning reads, on the
 * same time (UTC, from [epochMillis]): a test or a replay runs the same keys to the same result.
 */
class VirtualHostClock(private val epochMillis: Long = 0) : HostClock, LearnClock {
    private class Timer(val at: Long, val seq: Long, val task: () -> Unit) {
        var cancelled = false
    }

    private var now = 0L
    private var seq = 0L
    private val timers = ArrayList<Timer>()

    override fun uptimeMillis() = now

    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        val timer = Timer(now + maxOf(delayMillis, 0), seq++, task)
        timers += timer
        return Cancellable { timer.cancelled = true }
    }

    override fun now() = epochMillis + now

    override fun offset(at: Long) = 0

    /** Timers not yet run and not cancelled, due or not. */
    val pending get() = timers.count { !it.cancelled }

    /** Moves the clock [millis] on, running each timer due on the way at its time, in order. */
    fun advance(millis: Long) {
        val until = now + millis
        while (true) {
            timers.removeAll { it.cancelled }
            val next = timers.filter { it.at <= until }.minWithOrNull(compareBy<Timer>({ it.at }, { it.seq })) ?: break
            timers.remove(next)
            // never back: a timer due before a task's [spend] ran out runs late, as on a busy thread
            now = maxOf(now, next.at)
            next.task()
        }
        now = maxOf(now, until)
    }

    /** Time a task takes as it runs: the clock moves on [millis] without running timers, as a busy thread's does. */
    fun spend(millis: Long) {
        now += millis
    }
}
