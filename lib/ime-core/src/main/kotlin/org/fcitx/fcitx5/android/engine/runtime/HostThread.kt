/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

/**
 * Whether [Engines] is called on the thread that made it, as made with it (review B 3.1): everything
 * there is the fcitx thread's, unlocked, and nothing but this holds it. A call off it is [told], if
 * given (the debug build); with none, nothing is checked. Held from the start rather than taken by the
 * first call, so a stray first call elsewhere is the one told, not every call after it.
 */
internal class HostThread(private val told: ((String) -> Unit)?) {
    private val host: Thread = Thread.currentThread()

    fun check() {
        val told = told ?: return
        val now = Thread.currentThread()
        if (host !== now) told("Engines called on ${now.name}, not ${host.name}, the thread it runs on")
    }

    companion object {
        /** Set by this module's test task (its build): there a call off the host thread throws. */
        const val STRICT = "youmo.engines.hostThreadStrict"

        /** What a call off the host thread does by default: throws in this module's tests, unchecked elsewhere. */
        fun ofTests(): ((String) -> Unit)? =
            if (System.getProperty(STRICT) == "true") { message -> throw IllegalStateException(message) } else null
    }
}
