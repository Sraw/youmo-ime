/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * What lifting the finger after a vertical swipe on a letter key types: the key's corner
 * character for a swipe the way the user picked, the letter itself for one the other way. The
 * letter is typed here, not left to the key's tap: a swipe as long as one the right way leaves the
 * key, and a touch that left its key is no tap, so it typed nothing at all where a shorter one,
 * still on the key, typed the letter (ST-15). With swiping turned off a slide off the key still
 * types nothing: that is how a press is taken back, and ST-15 is about keys with a swipe symbol.
 */
object SymbolSwipe {

    /** Which way a key's swipe types its symbol. */
    enum class Direction { Up, Down, Off }

    enum class Outcome {
        /** The corner character. */
        Symbol,

        /** The key's letter, as a tap. */
        Tap,

        /** Neither: the key's own handling (a tap, or nothing) stands. */
        None,
    }

    /**
     * The lift of a touch that swiped [totalY] steps (down positive), on a key whose swipe types
     * its symbol [direction]; [consumed] when a long press took the touch already, [cancelled]
     * when the system took it.
     */
    fun onUp(direction: Direction, totalY: Int, consumed: Boolean, cancelled: Boolean): Outcome = when {
        consumed || cancelled || totalY == 0 || direction == Direction.Off -> Outcome.None
        (totalY > 0) == (direction == Direction.Down) -> Outcome.Symbol
        else -> Outcome.Tap
    }

    /** Whether a swipe of [totalY] steps so far is one that types the symbol: the preview while it moves. */
    fun typesSymbol(direction: Direction, totalY: Int): Boolean =
        onUp(direction, totalY, consumed = false, cancelled = false) == Outcome.Symbol
}
