/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/**
 * The pause before a snapshot that refines is refined, `AndroidEngine::refine_`
 * (`androidengine.cpp:26-28,689-721`): one timer at a time, a new one replacing it, any event sent
 * to a session ending it ([cancel]), a long-press menu shown holding it off till the next event.
 */
class Refiner(private val clock: HostClock) {
    private var pending: Cancellable? = null

    /** A refine is waiting. */
    val waiting get() = pending != null

    /** [task] after [delayMillis], in place of one waiting. */
    fun later(delayMillis: Long, task: () -> Unit) {
        cancel()
        var timer: Cancellable? = null
        timer = clock.schedule(delayMillis) {
            if (pending === timer) pending = null
            task()
        }
        pending = timer
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }

    companion object {
        /**
         * `PauseBeforeRefine` (`androidengine.cpp:28`): long enough for a key typed right after it
         * to come first, short against the time a user takes to reach for a candidate.
         */
        const val PAUSE_MILLIS = 30L
    }
}
