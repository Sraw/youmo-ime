/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * Where a key draws what its swipes reach. Its shortcut's mark (a command's icon, a text) is in the
 * top left corner, whichever way swipes to it; its symbol is where 标点位置 says, as it always was
 * (top right, or under the letter; landscape's and 紧凑's keys are too low for under, so top right
 * there). The two never move each other: a symbol pushed to the bottom right by a shortcut, moved
 * back as the swipe changed ways, read as a different key each time (0.5.1's testers). 标点位置 无
 * draws neither, the swipes still working.
 *
 * The symbol is drawn whether a swipe reaches it or not (it is also the long press's first); a
 * shortcut only where a way reaches it.
 *
 * A function key has no symbol: its up shortcut top left, its down one bottom left.
 */
object KeyMarks {

    enum class Position { TopRight, Bottom, None }

    /** A shortcut's corners [topLeft] and [bottomLeft] (a function key's down one); the symbol's, [topRight] or [below]. */
    data class Placed<T>(
        val topLeft: T? = null,
        val bottomLeft: T? = null,
        val topRight: T? = null,
        val below: T? = null,
    )

    /**
     * [symbol] and [shortcut], either null for none, placed by [position] ([landscape] or not,
     * [compact] or not); the shortcut drawn only where a way [swiped] to it.
     */
    fun <T : Any> place(
        symbol: T?, shortcut: T?, swiped: Boolean, position: Position, landscape: Boolean, compact: Boolean = false,
    ): Placed<T> {
        if (position == Position.None) return Placed()
        val below = position == Position.Bottom && !landscape && !compact
        return Placed(
            topLeft = shortcut?.takeIf { swiped },
            topRight = symbol?.takeUnless { below },
            below = symbol?.takeIf { below },
        )
    }

    /**
     * A function key's: its [up] shortcut top left, its [down] bottom left, each only where that way is
     * on ([upOn], [downOn]); 标点位置 无 draws neither. Never under its label, which is its icon or the
     * input method's name.
     */
    fun <T : Any> placeFunction(up: T?, down: T?, upOn: Boolean, downOn: Boolean, position: Position): Placed<T> =
        if (position == Position.None) Placed() else Placed(topLeft = up?.takeIf { upOn }, bottomLeft = down?.takeIf { downOn })
}
