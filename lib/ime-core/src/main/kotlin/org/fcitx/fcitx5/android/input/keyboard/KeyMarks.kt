/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * Where a key draws what its swipes reach: the up one's in the top right corner, the down one's in
 * the bottom right. A key with one only draws it where 标点位置 says, as it always drew its symbol
 * (top right, or under the letter; landscape's keys are too low for under, so top right there);
 * 标点位置 无 draws neither, the swipes still working.
 *
 * The symbol is drawn whether a swipe reaches it or not (it is also the long press's first); a
 * shortcut only where a way reaches it.
 *
 * 紧凑 (the lowest 键盘高度, [KeyboardHeightLevels]) has keys too low for anything but the top right
 * corner, upright too (051-compact §1.4): one mark goes there whatever 标点位置 says (but 无); of a
 * key's two only the symbol, which differs on every key and needs reading, where a shortcut is
 * remembered as Ctrl+C is and still swipes; of a function key's two only the up one.
 */
object KeyMarks {

    enum class Position { TopRight, Bottom, None }

    data class Placed<T>(val topRight: T? = null, val bottomRight: T? = null, val below: T? = null)

    /**
     * [symbol] and [shortcut], either null for none, placed by [position] ([landscape] or not), the
     * shortcut swiped [shortcutUp] (true up, false down, null not swiped to, so not drawn). With
     * both drawn the symbol takes the other corner: the two ways never reach one layer. [compact]:
     * the top right corner only.
     */
    fun <T : Any> place(
        symbol: T?, shortcut: T?, shortcutUp: Boolean?, position: Position, landscape: Boolean, compact: Boolean = false,
    ): Placed<T> {
        if (position == Position.None) return Placed()
        val drawnShortcut = shortcut?.takeIf { shortcutUp != null }
        if (symbol == null || drawnShortcut == null || compact) {
            val only = symbol ?: drawnShortcut ?: return Placed()
            return if (position == Position.Bottom && !landscape && !compact) Placed(below = only) else Placed(topRight = only)
        }
        return if (shortcutUp == true) {
            Placed(topRight = drawnShortcut, bottomRight = symbol)
        } else {
            Placed(topRight = symbol, bottomRight = drawnShortcut)
        }
    }

    /**
     * A function key's: its [up] shortcut top right, its [down] bottom right, each only where that way
     * is on ([upOn], [downOn]); 标点位置 无 draws neither. Never under its label, which is its icon or the
     * input method's name. [compact]: the up one alone.
     */
    fun <T : Any> placeFunction(
        up: T?, down: T?, upOn: Boolean, downOn: Boolean, position: Position, compact: Boolean = false,
    ): Placed<T> = when {
        position == Position.None -> Placed()
        compact -> Placed(topRight = up?.takeIf { upOn })
        else -> Placed(topRight = up?.takeIf { upOn }, bottomRight = down?.takeIf { downOn })
    }
}
