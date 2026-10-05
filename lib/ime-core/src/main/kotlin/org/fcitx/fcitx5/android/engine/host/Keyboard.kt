/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.Snapshot

/** The events the androidengine addon sends, numbered as it numbers them (androidengine_public.h). */
object EngineEvent {
    /** A character typed; the argument is its code point. */
    const val CHAR = 0
    const val BACKSPACE = 1
    const val ENTER = 2
    const val ESCAPE = 3
    const val PAGE_UP = 4
    const val PAGE_DOWN = 5

    /** A candidate tapped; the argument is its index among all of them. */
    const val PICK = 6

    /** The app moved the cursor, or the input method was left. */
    const val RESET = 7

    /** Any other key: an arrow, Home, Tab, Delete. */
    const val OTHER = 8

    /** "Forget word" on a candidate; the argument is its index among all of them. */
    const val FORGET = 9

    /**
     * No key: the user pauses, and the last snapshot [refines][Snapshot.refines]. The snapshot
     * back is [handled][Snapshot.handled] only if it changed, to be shown in place of the last.
     */
    const val REFINE = 10

    /** "Pin to top as custom phrase" on a candidate; the argument is its index among all of them. */
    const val PIN = 11

    /** "Delete from custom phrase" on a candidate; the argument is its index among all of them. */
    const val UNPIN = 12

    /** "Never show this word" on a candidate; the argument is its index among all of them. */
    const val BLOCK = 13
}

/**
 * A keyboard's events as a [session]'s actions: which keys the engine takes, which are the app's.
 *
 * A key the session reads is typed. While something is typed, space picks the first candidate
 * and a digit the one it numbers, Enter keeps the input as typed and Escape drops it; any other
 * character goes to the session to end the input, committed as the session would, then to the
 * app, so `nihao,` gives 你好， (the comma turned full-width by fcitx's punctuation addon, which
 * androidengine asks for every key handed back). Keys that are no characters (arrows, Home) are
 * swallowed while something is typed: the app must not move its cursor about the input shown.
 *
 * While words that may follow are offered, any key the session does not read goes to the app and
 * the offer goes away: space after a word is a space, not the first prediction.
 *
 * [learning] is passed to the session: off where the app asks that nothing be learned.
 */
class Keyboard(private val session: Session) {

    private var shown: Snapshot = IDLE

    // something typed, not a prediction
    private val composing get() = shown.preedit.isNotEmpty() && !shown.predicting

    fun onEvent(event: Int, arg: Int, learning: Boolean = true): Snapshot {
        session.learning = learning
        shown = when (event) {
            EngineEvent.CHAR -> char(arg)
            EngineEvent.BACKSPACE -> session.apply(Action.Backspace)
            EngineEvent.ENTER -> if (composing) session.apply(Action.CommitRaw) else passOn()
            EngineEvent.ESCAPE -> if (composing || shown.predicting) session.apply(Action.Reset) else passOn()
            EngineEvent.PAGE_UP -> if (composing) session.apply(Action.PreviousPage) else passOn()
            EngineEvent.PAGE_DOWN -> if (composing) session.apply(Action.NextPage) else passOn()
            EngineEvent.PICK -> session.apply(Action.Pick(arg))
            EngineEvent.FORGET, EngineEvent.PIN, EngineEvent.UNPIN, EngineEvent.BLOCK -> session.apply(pressed(event, arg))
            EngineEvent.RESET -> session.apply(Action.Reset).copy(handled = false)
            EngineEvent.OTHER -> if (composing) shown.copy(commit = "", handled = true) else passOn()
            EngineEvent.REFINE -> refine()
            else -> passOn()
        }
        return shown
    }

    /**
     * [before] is the text before the cursor, which the user put somewhere the engine did not
     * (a field focused, a tap): what is typed next follows it. Nothing on show is dropped, as the
     * host would not know: the service resets fcitx first where there is something. [learning]
     * is as for [onEvent], set before: this comes before any key typed in a field just focused.
     */
    fun context(before: String, learning: Boolean = true) {
        if (shown.preedit.isNotEmpty() || shown.candidates.isNotEmpty()) return
        session.learning = learning
        shown = session.apply(Action.Context(before))
    }

    /** What a long press on the candidate at [index] offered, and the user chose. */
    private fun pressed(event: Int, index: Int) = when (event) {
        EngineEvent.FORGET -> Action.Forget(index)
        EngineEvent.PIN -> Action.Pin(index)
        EngineEvent.BLOCK -> Action.Block(index)
        else -> Action.Unpin(index)
    }

    private fun char(codePoint: Int): Snapshot {
        // past the BMP (an emoji key): no session reads one, but it still ends the input
        val c = if (codePoint in 0..Char.MAX_VALUE.code) codePoint.toChar() else REPLACEMENT
        if (session.reads(c)) return session.apply(Action.Key(c))
        val digit = c.digitToIntOrNull()
        return when {
            composing && c == ' ' -> session.apply(Action.Select(0))
            // 1 is the first; 0 the tenth
            composing && digit != null -> session.apply(Action.Select((digit + DIGITS - 1) % DIGITS))
            else -> session.apply(Action.Key(c)).copy(handled = false)
        }
    }

    private fun refine(): Snapshot {
        val last = shown.copy(commit = "", handled = true)
        if (!last.refines) return last.copy(handled = false)
        val next = session.apply(Action.Refine)
        return if (next.copy(refines = false) == last.copy(refines = false)) next.copy(handled = false) else next
    }

    /** The key is the app's; an offer of what may follow goes away with it. */
    private fun passOn(): Snapshot = if (shown.predicting) session.apply(Action.Reset).copy(handled = false) else IDLE

    companion object {
        private const val DIGITS = 10
        private const val REPLACEMENT = '\uFFFD'

        private val IDLE = Snapshot(
            commit = "",
            preedit = "",
            candidates = emptyList(),
            page = 0,
            hasPreviousPage = false,
            hasNextPage = false,
            handled = false,
            predicting = false,
        )
    }
}
