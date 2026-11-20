/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/** `AndroidEngine::refine_`: one timer, replaced by a new one, ended by any event ([cancel]); a long-press menu holds it off till the next event. */
class Refiner(private val clock: HostClock) {
    private var pending: Cancellable? = null

    val waiting get() = pending != null

    /** In place of one waiting. */
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
        /** `PauseBeforeRefine`: a key typed right after comes first; short against reaching for a candidate. */
        const val PAUSE_MILLIS = 30L
    }
}
