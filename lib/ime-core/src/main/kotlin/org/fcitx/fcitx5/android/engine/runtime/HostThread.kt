/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

/** Checks [Engines] is called on its creating thread (everything there is unlocked); a call off it is [told], if given. Fixed at creation, so a stray first call is the one reported. */
internal class HostThread(private val told: ((String) -> Unit)?) {
    private val host: Thread = Thread.currentThread()

    fun check() {
        val told = told ?: return
        val now = Thread.currentThread()
        if (host !== now) told("Engines called on ${now.name}, not ${host.name}, the thread it runs on")
    }

    companion object {
        /** Set by this module's test task: there a call off the host thread throws. */
        const val STRICT = "youmo.engines.hostThreadStrict"

        /** Throws in this module's tests, unchecked elsewhere. */
        fun ofTests(): ((String) -> Unit)? =
            if (System.getProperty(STRICT) == "true") { message -> throw IllegalStateException(message) } else null
    }
}
