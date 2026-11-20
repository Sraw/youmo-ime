/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.os.SystemClock
import timber.log.Timber

/**
 * When fcitx switches input method itself, the service tells the system, and the system calls
 * `onCurrentInputMethodSubtypeChanged` back a little later: an echo, which must not switch fcitx again.
 * Any other call is the user's pick.
 *
 * Several switches can be told before the first echo is back (fcitx busy loading an engine), and echoes
 * come in order: remembering only the last took the echo of the one before for the user's pick. One not
 * back by [ECHO_WITHIN_MS] never comes. Main thread only.
 */
class SubtypeEchoes(private val now: () -> Long = SystemClock::uptimeMillis) {

    private class Told(val inputMethod: String, val at: Long)

    private val pending = ArrayDeque<Told>()

    /**
     * [had] null if not one of ours. False if the system has [inputMethod] already: it calls back only when
     * its subtype changes, not for a sub-mode's change, nor for the switch the user's pick made.
     */
    fun shouldTell(inputMethod: String, had: String?): Boolean {
        val time = now()
        forgetOverdue(time)
        // [had], not the newest switch queued: it has each switch once made, and the user's picks too
        if (inputMethod == had) {
            Timber.d("SubtypeEchoes: im=$inputMethod is the system's subtype already, not told")
            return false
        }
        if (pending.size == MAX_PENDING) forgetOldest(time, "more than $MAX_PENDING pending")
        pending.addLast(Told(inputMethod, time))
        Timber.d("SubtypeEchoes: im=$inputMethod told, the system had im=$had, ${pending.size} pending")
        return true
    }

    /** True for an echo, which is forgotten with any told before it that never came back. */
    fun isEcho(inputMethod: String): Boolean {
        val time = now()
        forgetOverdue(time)
        val at = pending.indexOfFirst { it.inputMethod == inputMethod }
        if (at < 0) return false
        repeat(at) { forgetOldest(time, "a later echo is back") }
        pending.removeFirst()
        return true
    }

    /**
     * As it does for a keyboard no longer enabled: no echo comes, and awaited it would take a later pick for
     * itself. The newest switch to it is forgotten; any told before stay awaited.
     */
    fun refused(inputMethod: String) {
        val at = pending.indexOfLast { it.inputMethod == inputMethod }
        if (at < 0) return
        val told = pending.removeAt(at)
        Timber.d("SubtypeEchoes: forgot im=${told.inputMethod}, told ${now() - told.at} ms ago: refused")
    }

    private fun forgetOverdue(time: Long) {
        while (pending.firstOrNull()?.let { time - it.at > ECHO_WITHIN_MS } == true) forgetOldest(time, "overdue")
    }

    private fun forgetOldest(time: Long, why: String) {
        val told = pending.removeFirst()
        Timber.d("SubtypeEchoes: forgot im=${told.inputMethod}, told ${time - told.at} ms ago: $why")
    }

    private companion object {
        const val ECHO_WITHIN_MS = 10_000L

        /** Bounds memory only. */
        const val MAX_PENDING = 16
    }
}
