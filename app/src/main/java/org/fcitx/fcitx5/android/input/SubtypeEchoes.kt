/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.os.SystemClock
import timber.log.Timber

/**
 * The service's own subtype changes on their way back. When fcitx switches input method itself
 * (a switch key, a shortcut), the service tells the system the new subtype, and the system calls
 * `onCurrentInputMethodSubtypeChanged` with it a little later: an echo, which must not switch
 * fcitx again. Any other call is the user's pick in the system's picker.
 *
 * Several switches can be told before the first echo is back -- fcitx busy loading an engine
 * runs the switches queued behind it back to back -- and the echoes come in the order told.
 * Remembering only the last switch took the echo of the one before it for the user's pick, and
 * switched fcitx back to it.
 *
 * An echo is back within milliseconds, later only behind a stalled main thread; one that is not
 * back by [ECHO_WITHIN_MS] never comes, and is forgotten before it can take a later pick of the
 * user's for itself. The window is wide: a late echo taken for a pick switches fcitx back, while
 * a lost echo is rare, the system being told only of a change of its subtype.
 *
 * Main thread only.
 */
class SubtypeEchoes(private val now: () -> Long = SystemClock::uptimeMillis) {

    private class Told(val inputMethod: String, val at: Long)

    /** Input methods told to the system whose echo is not back yet, oldest first. */
    private val pending = ArrayDeque<Told>()

    /**
     * fcitx switched to [inputMethod]; [had] is the system's subtype, null if not one of ours.
     * True if the switch is to be told to the system, whose echo is then awaited; false if the
     * system has [inputMethod] already: it calls back only when its subtype changes, not for a
     * sub-mode's change, nor for the switch the user's pick made.
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

    /**
     * The system called back with [inputMethod]: true for the echo of a switch [shouldTell] let
     * through, which is forgotten with any told before it that never came back; false for the
     * user's pick.
     */
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
     * The system refused the switch to [inputMethod] that [shouldTell] just let through, as it does
     * for a keyboard no longer enabled: no echo comes, and awaited, it would take a later pick of
     * the user's for itself. The newest switch to it is forgotten; any told before stay awaited.
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

        /** Bounds memory only: echoes come back in order and within the window, well short of it. */
        const val MAX_PENDING = 16
    }
}
