/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * Where a key draws what its swipes reach, as slots of its face ([Slot]).
 *
 * Its shortcut's mark (a command's icon, a text) where 快捷键标记位置 says ([ShortcutAt], top left
 * unless chosen otherwise), whichever way swipes to it: one place on every key, chosen once. In
 * 0.5.1's test build it followed 滑动 instead (the up layer's top right, the down layer's bottom
 * right): a fresh install's symbols pushed to the bottom right by the shortcuts, all of them back
 * and the shortcuts there once 滑动 was set the other way, which read as a keyboard laid out anew.
 *
 * Its symbol on the right, by the way that swipes to it: up top right, down bottom right, neither
 * (no swipe reaches it; it is the long press's first all the same) top right; 标点位置 底部 under the
 * letter instead, where there is room (not landscape's or 紧凑's low keys); 无 draws nothing, the
 * swipes still working. A symbol in the shortcut's slot takes the other right corner ([place]).
 *
 * A function key has no symbol: its up shortcut in the chosen slot, its down one in the slot mirrored
 * top for bottom ([placeFunction]); one alone in the chosen slot.
 */
object KeyMarks {

    /** 标点位置: where a symbol goes, [Position.None] for no marks at all. */
    enum class Position { TopRight, Bottom, None }

    /** 快捷键标记位置: a shortcut's slot on every key; [TopLeft] the default. */
    enum class ShortcutAt(val slot: Slot) {
        TopLeft(Slot.TopLeft), BottomLeft(Slot.BottomLeft), TopRight(Slot.TopRight), BottomRight(Slot.BottomRight), Bottom(Slot.Bottom);

        companion object {
            val Default = TopLeft
        }
    }

    /**
     * A place on a key's face: its four corners, the middle of its top and bottom edges, and [Below]
     * its label, the label moved up to make room (标点位置 底部's symbol; a shortcut is never there).
     */
    enum class Slot(val left: Boolean, val right: Boolean, val bottom: Boolean) {
        TopLeft(true, false, false), TopRight(false, true, false), BottomLeft(true, false, true), BottomRight(false, true, true),
        Top(false, false, false), Bottom(false, false, true), Below(false, false, true);

        /** Top for bottom, the side kept. */
        val mirrored: Slot
            get() = when (this) {
                TopLeft -> BottomLeft
                BottomLeft -> TopLeft
                TopRight -> BottomRight
                BottomRight -> TopRight
                Top -> Bottom
                Bottom, Below -> Top
            }

        val centred: Boolean get() = !left && !right
    }

    /** What is drawn in which slot. */
    data class Placed<T>(val at: Map<Slot, T> = emptyMap()) {
        operator fun get(slot: Slot): T? = at[slot]

        /** [t]'s slot, if it is drawn. */
        fun slotOf(t: T): Slot? = at.entries.firstOrNull { it.value == t }?.key

        companion object {
            fun <T> of(vararg pairs: Pair<Slot, T>): Placed<T> = Placed(mapOf(*pairs))
        }
    }

    /**
     * A character key's [symbol] (null for none), swiped to [symbolWay] (true up, false down, null
     * neither), and [shortcut] (null for none), drawn only where a way swipes to it ([shortcutWay]),
     * in [at]'s slot; [position], [landscape], [compact] as the keyboard is.
     */
    fun <T : Any> place(
        symbol: T?, symbolWay: Boolean?, shortcut: T?, shortcutWay: Boolean?, at: ShortcutAt,
        position: Position, landscape: Boolean, compact: Boolean = false,
    ): Placed<T> {
        if (position == Position.None) return Placed()
        val shortcutSlot = at.slot.takeIf { shortcut != null && shortcutWay != null }
        val home = symbol?.let { symbolSlot(symbolWay, position, landscape, compact) }
        val symbolSlot = when {
            home == null -> null
            // the shortcut where it was put, the symbol the other right corner; under the letter, it and
            // the bottom edge's middle one place: the symbol its way's corner
            home == Slot.Below -> if (shortcutSlot == Slot.Bottom) wayCorner(symbolWay) else home
            home != shortcutSlot -> home
            home == Slot.TopRight -> Slot.BottomRight
            else -> Slot.TopRight
        }
        val out = linkedMapOf<Slot, T>()
        if (shortcutSlot != null && shortcut != null) out[shortcutSlot] = shortcut
        if (symbolSlot != null && symbol != null) out[symbolSlot] = symbol
        return Placed(out)
    }

    /** A symbol's own slot, nothing else on its key: its way's right corner, or under the letter. */
    fun symbolSlot(way: Boolean?, position: Position, landscape: Boolean, compact: Boolean): Slot? = when {
        position == Position.None -> null
        position == Position.Bottom && !landscape && !compact -> Slot.Below
        else -> wayCorner(way)
    }

    private fun wayCorner(way: Boolean?): Slot = if (way == false) Slot.BottomRight else Slot.TopRight

    /**
     * A function key's: its [up] shortcut in [at]'s slot, its [down] one in that slot mirrored, each
     * only where that way is on ([upOn], [downOn]); the down one alone in [at]'s. 标点位置 无 draws neither.
     */
    fun <T : Any> placeFunction(up: T?, down: T?, upOn: Boolean, downOn: Boolean, at: ShortcutAt, position: Position): Placed<T> {
        if (position == Position.None) return Placed()
        val u = up?.takeIf { upOn }
        val d = down?.takeIf { downOn }
        val out = linkedMapOf<Slot, T>()
        if (u != null) out[at.slot] = u
        if (d != null) out[if (u != null) at.slot.mirrored else at.slot] = d
        return Placed(out)
    }
}
