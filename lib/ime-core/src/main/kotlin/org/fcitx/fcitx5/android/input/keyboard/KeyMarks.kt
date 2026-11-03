/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * Where a key draws what its swipes reach, as slots of its face ([Slot]), from two settings with one
 * set of choices ([At]): 标点位置 for its symbol, 快捷键图标位置 for its shortcut's mark (a command's
 * icon, a text). One place for each on every key, whichever way swipes to it: in 0.5.1's test build
 * they followed 滑动 instead (the up layer's top right, the down layer's bottom right), and a fresh
 * install's keyboard looked laid out anew once 滑动 was set the other way.
 *
 * The settings page keeps the two apart (the other's place greyed out); one place in both, as an
 * import or a backup can write, is read as [settled] says: the symbol yields. 无 draws nothing, the
 * swipes still working.
 *
 * The symbol's 底部 is under the letter, where there is room: not landscape's or 紧凑's low keys, where
 * it goes top right, bottom right if the shortcut is there ([symbolSlot]).
 *
 * A function key has no symbol: its up shortcut in the chosen slot, its down one in that slot mirrored
 * top for bottom ([placeFunction]); one alone in the chosen slot.
 */
object KeyMarks {

    /** 标点位置's and 快捷键图标位置's choices, in the settings' order; [Bottom] a shortcut's bottom edge middle, a symbol's under the letter. */
    enum class At(val corner: Slot?) {
        TopRight(Slot.TopRight), BottomRight(Slot.BottomRight), TopLeft(Slot.TopLeft), BottomLeft(Slot.BottomLeft), Bottom(null), None(null);

        /** Its corner's other on its side: top for bottom; [Bottom]'s top right. */
        val yielded: At
            get() = when (this) {
                TopRight -> BottomRight
                BottomRight -> TopRight
                TopLeft -> BottomLeft
                BottomLeft -> TopLeft
                Bottom -> TopRight
                None -> None
            }

        companion object {
            val SymbolDefault = TopRight
            val ShortcutDefault = TopLeft
        }
    }

    /** The two settings as read: never one place but 无. */
    data class Spots(val symbol: At, val shortcut: At)

    /**
     * [symbol] and [shortcut] as the settings stored them, apart: one place in both (an import, a
     * backup) moves the symbol to [At.yielded], the shortcut kept where the user last saw it chosen.
     */
    fun settled(symbol: At, shortcut: At): Spots =
        Spots(if (symbol == shortcut && symbol != At.None) symbol.yielded else symbol, shortcut)

    /** A shortcut's slot at [at]; null for none drawn. */
    fun shortcutSlot(at: At): Slot? = if (at == At.Bottom) Slot.Bottom else at.corner

    /**
     * A symbol's slot at [symbolAt], [shortcutAt] where the shortcuts are ([settled] first): 底部 under
     * the letter, or where there is no room for it (landscape, 紧凑) top right, bottom right if the
     * shortcuts are top right; null for none drawn.
     */
    fun symbolSlot(symbolAt: At, shortcutAt: At, landscape: Boolean, compact: Boolean): Slot? {
        val s = settled(symbolAt, shortcutAt)
        return when {
            s.symbol != At.Bottom -> s.symbol.corner
            !landscape && !compact -> Slot.Below
            s.shortcut == At.TopRight -> Slot.BottomRight
            else -> Slot.TopRight
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
     * A character key's [symbol] (null for none) and [shortcut] (null for none, or no way swiping to
     * it), at [symbolAt] and [shortcutAt] as the settings say; [landscape], [compact] as the keyboard is.
     */
    fun <T : Any> place(symbol: T?, shortcut: T?, symbolAt: At, shortcutAt: At, landscape: Boolean, compact: Boolean = false): Placed<T> {
        val out = linkedMapOf<Slot, T>()
        val shortcutSlot = shortcutSlot(shortcutAt)
        if (shortcut != null && shortcutSlot != null) out[shortcutSlot] = shortcut
        val symbolSlot = symbolSlot(symbolAt, shortcutAt, landscape, compact)
        if (symbol != null && symbolSlot != null) out[symbolSlot] = symbol
        return Placed(out)
    }

    /**
     * A function key's: its [up] shortcut at [at], its [down] one in that slot mirrored, each only
     * where that way is on ([upOn], [downOn]); the down one alone at [at]. 无 draws neither.
     */
    fun <T : Any> placeFunction(up: T?, down: T?, upOn: Boolean, downOn: Boolean, at: At): Placed<T> {
        val slot = shortcutSlot(at) ?: return Placed()
        val u = up?.takeIf { upOn }
        val d = down?.takeIf { downOn }
        val out = linkedMapOf<Slot, T>()
        if (u != null) out[slot] = u
        if (d != null) out[if (u != null) slot.mirrored else slot] = d
        return Placed(out)
    }
}
