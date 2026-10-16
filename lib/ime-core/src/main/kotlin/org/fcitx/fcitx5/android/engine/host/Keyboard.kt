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

    /** Any other key: an arrow, Home, Tab, Delete; the argument is its fcitx keysym. */
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

    /** A tap in the composed text; the argument is the character it puts the cursor before, in code points. */
    const val CURSOR = 14
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
 * Left, Right, Home and End move the session's cursor in its input, where it keeps one ([Action.Step]).
 *
 * While words that may follow are offered, any key the session does not read goes to the app and
 * the offer goes away: space after a word is a space, not the first prediction.
 *
 * [nineKeys]: the session is the nine keys' (九键), whose 重输 is Escape and whose syllables come
 * as [SYLLABLES]. The host says so: a table whose codes are digits reads 2 as well, and its
 * Escape and characters are the app's.
 *
 * [learning] is passed to the session: off where the app asks that nothing be learned.
 */
class Keyboard(private val session: Session, private val nineKeys: Boolean) {

    private var shown: Snapshot = IDLE

    private val english get() = session.takesEveryCharacter

    // something typed, not a prediction
    private val composing get() = shown.preedit.isNotEmpty() && !shown.predicting

    fun onEvent(event: Int, arg: Int, learning: Boolean = true): Snapshot {
        session.learning = learning
        shown = when (event) {
            EngineEvent.CHAR -> char(arg)
            EngineEvent.BACKSPACE -> session.apply(Action.Backspace)
            EngineEvent.ENTER, EngineEvent.PAGE_UP, EngineEvent.PAGE_DOWN, EngineEvent.CURSOR, EngineEvent.OTHER -> whileComposing(event, arg)
            // the nine keys' 重输 is Escape: with nothing typed, nothing to start over, not the app's
            EngineEvent.ESCAPE -> if (composing || shown.predicting) session.apply(Action.Reset) else if (nineKeys) IDLE.copy(handled = true) else passOn()
            EngineEvent.PICK -> session.apply(Action.Pick(arg))
            EngineEvent.FORGET, EngineEvent.PIN, EngineEvent.UNPIN, EngineEvent.BLOCK -> session.apply(pressed(event, arg))
            EngineEvent.RESET -> session.apply(Action.Reset).copy(handled = false)
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
        if (codePoint == INK) return session.apply(Action.Ink)
        if (codePoint in LETTERS && nineKeys) {
            val n = codePoint - LETTERS.first
            return session.apply(Action.Letter('a' + n % ALPHABET, again = n >= ALPHABET))
        }
        // past the BMP (an emoji key): no session reads one, but it still ends the input
        val c = if (codePoint in 0..Char.MAX_VALUE.code) codePoint.toChar() else REPLACEMENT
        if (c in SYLLABLES && nineKeys) {
            val n = c - SYLLABLES.first
            return session.apply(Action.Syllable(n % Action.Syllable.MAX, n / Action.Syllable.MAX))
        }
        if (session.reads(c) || english) return session.apply(Action.Key(c))
        // on the nine keys a digit is a key: one not read (0) ends the input, as punctuation does
        val digit = c.digitToIntOrNull()?.takeUnless { nineKeys }
        return when {
            composing && c == ' ' -> session.apply(Action.Select(0))
            // 1 is the first; 0 the tenth
            composing && digit != null -> session.apply(Action.Select((digit + DIGITS - 1) % DIGITS))
            else -> session.apply(Action.Key(c)).copy(handled = false)
        }
    }

    // keys the session takes while something is typed, the app's otherwise
    private fun whileComposing(event: Int, arg: Int): Snapshot = when {
        // an arrow, Tab: English commits its word and the key moves on from it; another session
        // moves its cursor by an arrow, Home or End, and keeps any other key, its input as it was
        composing && event == EngineEvent.OTHER -> when {
            english -> session.apply(Action.CommitRaw)
            else -> step(arg)?.let { session.apply(it).takeIf(Snapshot::handled) } ?: again(handled = true)
        }
        composing -> session.apply(
            when (event) {
                EngineEvent.ENTER -> Action.CommitRaw
                EngineEvent.PAGE_UP -> Action.PreviousPage
                EngineEvent.PAGE_DOWN -> Action.NextPage
                // the session counts UTF-16 units: a piece picked may be past the BMP
                EngineEvent.CURSOR -> Action.Cursor(utf16Offset(shown.preedit, arg))
                else -> error("event $event is not one taken while composing")
            },
        )
        // a tap in the app's text: the panel as it was
        event == EngineEvent.CURSOR -> again(handled = false).also { session.passedOn() }
        else -> passOn()
    }

    // an arrow, Home or End as a move of the cursor in the input
    private fun step(keySym: Int) = when (keySym) {
        // the keypad's too, Num Lock off
        KEY_LEFT, KEY_KP_LEFT -> Action.Step(-1)
        KEY_RIGHT, KEY_KP_RIGHT -> Action.Step(1)
        KEY_HOME, KEY_KP_HOME -> Action.Step.START
        KEY_END, KEY_KP_END -> Action.Step.END
        else -> null
    }

    // [codePoints] into [text] in UTF-16 units, no further than its end: the host's tap counts code points (PreeditUi.preeditAt)
    private fun utf16Offset(text: String, codePoints: Int) =
        text.offsetByCodePoints(0, codePoints.coerceIn(0, text.codePointCount(0, text.length)))

    private fun refine(): Snapshot {
        val last = again(handled = true)
        if (!last.refines) return last.copy(handled = false)
        val next = session.apply(Action.Refine)
        return if (next.copy(refines = false) == last.copy(refines = false)) next.copy(handled = false) else next
    }

    // the last snapshot shown again, what it committed and deleted done: androidengine deletes
    // before the cursor for any snapshot it shows, handled or not
    private fun again(handled: Boolean) = shown.copy(commit = "", deleteBefore = 0, handled = handled)

    /**
     * The key is the app's; an offer of what may follow goes away with it, and so does what English
     * knows of the text before the cursor (an Enter or an arrow: the space it added is not there).
     */
    private fun passOn(): Snapshot {
        session.passedOn()
        return if (shown.predicting || english) session.apply(Action.Reset).copy(handled = false) else IDLE
    }

    companion object {
        private const val DIGITS = 10
        private const val ALPHABET = 26
        private const val REPLACEMENT = '\uFFFD'

        // X keysyms, as androidengine passes a key that is no character (EngineEvent.OTHER)
        private const val KEY_HOME = 0xff50
        private const val KEY_LEFT = 0xff51
        private const val KEY_RIGHT = 0xff53
        private const val KEY_END = 0xff57
        private const val KEY_KP_HOME = 0xff95
        private const val KEY_KP_LEFT = 0xff96
        private const val KEY_KP_RIGHT = 0xff98
        private const val KEY_KP_END = 0xff9c

        /**
         * Characters of the private use area the nine-key keyboard sends for a syllable it offers
         * (see [Snapshot.syllables]): [syllableKey]. fcitx passes any character on, so this needs
         * no event of its own; no keyboard types one otherwise.
         */
        val SYLLABLES = '\uE000'..'\uF8FF'

        /** The character for the syllable at [index] of the list [id] ([Snapshot.syllablesId]). */
        fun syllableKey(index: Int, id: Int): Char {
            require(index in 0 until Action.Syllable.MAX && id in 0 until Action.Syllable.IDS) { "syllable $index of $id" }
            return SYLLABLES.first + id * Action.Syllable.MAX + index
        }

        /**
         * Characters of the supplementary private use area the nine-key keyboard sends for a
         * letter typed as itself ([Action.Letter]): [letterKey]. Past the BMP, which [SYLLABLES]
         * fill.
         */
        val LETTERS = 0xF0000 until 0xF0000 + 2 * ALPHABET

        /** The character for [letter] typed as itself, [again] a key pressed again soon: see [Action.Letter]. */
        fun letterKey(letter: Char, again: Boolean): Int {
            require(letter in 'a'..'z') { "letter $letter" }
            return LETTERS.first + (if (again) ALPHABET else 0) + (letter - 'a')
        }

        /**
         * The character the handwriting pad sends to have what it read shown ([Action.Ink]): of the
         * supplementary private use area, past [LETTERS]. No keyboard types it otherwise.
         */
        const val INK = 0xF0100

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
