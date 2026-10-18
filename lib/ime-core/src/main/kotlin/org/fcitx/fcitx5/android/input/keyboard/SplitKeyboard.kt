/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * The letters keyboard split in two for the thumbs, as Gboard splits it on a wide screen: when it
 * splits, and how wide its keys and the room between the halves are.
 *
 * Widths are shares of the keyboard's width, as the app's key definitions have them: a letter is
 * 0.1 unsplit, and 0 is the key that fills what is left (the space, which spans the room between
 * the halves).
 */
object SplitKeyboard {

    /** The setting: split where the screen is wide, always, or never. */
    enum class Mode { AUTO, ALWAYS, OFF }

    /**
     * Narrowest screen [Mode.AUTO] splits on, in dp, and only held sideways: a phone on its side
     * (some 700 to 930 dp), a tablet's landscape; not a tablet upright, typed on as a phone is.
     */
    const val AUTO_MIN_WIDTH_DP = 600

    /** Keys a half has, each row: Gboard's five, G and V on both halves to make the rows so. */
    const val HALF_KEYS = 5

    /** A letter's widest, in dp: what the screen has past ten of them is the room between the halves. */
    const val KEY_DP = 48

    /** The room between the halves at least (a narrow screen split all the same) and at most. */
    const val MIN_GAP = 0.12f
    const val MAX_GAP = 0.4f

    // a letter's width unsplit
    private const val LETTER = 0.1f

    fun splits(mode: Mode, landscape: Boolean, widthDp: Int): Boolean = when (mode) {
        Mode.ALWAYS -> true
        Mode.OFF -> false
        Mode.AUTO -> landscape && widthDp >= AUTO_MIN_WIDTH_DP
    }

    /**
     * Where the boards other than the letters go while they are split: the number pad, the nine
     * keys, handwriting, the symbols and emoji, the clipboard. Across the whole of a wide screen the
     * keys at its middle are out of either thumb's reach; one thumb's width at a side, as Gboard's
     * one-handed mode has its keyboard, they are under it.
     */
    enum class Dock { RIGHT, LEFT, FULL }

    /** One thumb's board: a share of the keyboard's width, about a phone's upright, within these. */
    const val DOCK_SHARE = 0.42f
    const val DOCK_MIN_DP = 320
    const val DOCK_MAX_DP = 420

    /** How wide, in dp, a board at a side is of a keyboard [widthDp] wide. */
    fun dockWidth(widthDp: Int): Int =
        (widthDp * DOCK_SHARE).toInt().coerceIn(DOCK_MIN_DP, DOCK_MAX_DP).coerceAtMost(widthDp)

    /** The room a board at a side leaves beside it at least, in dp, the arrow moving it across in it. */
    const val DOCK_MIN_FREE_DP = 120

    /**
     * The side the board shown goes to, as [dock] says, or null for the whole width: the letters
     * themselves ([lettersShown]) take it, split, as everything does when they are not ([splitting]),
     * and on a keyboard [widthDp] wide that a board at a side would all but fill (a phone upright
     * split all the same, [Mode.ALWAYS]: 320 of 360 dp, a strip of nothing beside it).
     */
    fun side(dock: Dock, splitting: Boolean, lettersShown: Boolean, widthDp: Int): Dock? {
        if (!splitting || lettersShown || dock == Dock.FULL) return null
        return dock.takeIf { widthDp - dockWidth(widthDp) >= DOCK_MIN_FREE_DP }
    }

    /**
     * One thumb's keyboard while the letters do not split, Gboard's one-handed mode: everything
     * the keyboard shows at a side, [ONE_HANDED_SHARE] of the width, the room beside it for the
     * buttons that move it across or back to the whole width.
     */
    const val ONE_HANDED_SHARE = 0.8f

    /** The room one thumb's keyboard leaves beside it at least, in dp: a side button's width. */
    const val ONE_HANDED_MIN_FREE_DP = 56

    /** Where the board shown goes ([Placement.side]) and how wide it is there, in dp. */
    data class Placement(val side: Dock, val widthDp: Int)

    /**
     * Where the board shown goes on a keyboard [widthDp] wide, or null for the whole width: split,
     * as [side] has it by [dock]; else one-handed at [oneHanded]'s side ([Dock.FULL] is off), a
     * phone's width at most on a tablet upright, whatever the board is, and null when the room beside
     * it is under [ONE_HANDED_MIN_FREE_DP].
     */
    fun placement(oneHanded: Dock, dock: Dock, splitting: Boolean, lettersShown: Boolean, widthDp: Int): Placement? = when {
        splitting -> side(dock, true, lettersShown, widthDp)?.let { Placement(it, dockWidth(widthDp)) }
        oneHanded == Dock.FULL -> null
        else -> (widthDp * ONE_HANDED_SHARE).toInt().coerceAtMost(DOCK_MAX_DP)
            // too narrow for the buttons beside it: the whole width, as a board at a side is
            .takeIf { widthDp - it >= ONE_HANDED_MIN_FREE_DP }?.let { Placement(oneHanded, it) }
    }

    /** The kinds of window the keyboard shows, as far as where they go while the letters split. */
    enum class Window { LETTERS, OTHER_LAYOUT, PICKER, CLIPBOARD, EXPANDED_CANDIDATES, OTHER }

    /**
     * Whether [window] goes to a side while the letters split: the other layouts, the pickers and
     * the clipboard do; the expanded candidates go as the board they are opened from, docked when it
     * was not the letters ([lettersLast]), so they neither widen nor narrow it; voice, editing and
     * the status area ([Window.OTHER]) take the whole width, as the letters they are opened from.
     */
    fun docks(window: Window, lettersLast: Boolean): Boolean = when (window) {
        Window.OTHER_LAYOUT, Window.PICKER, Window.CLIPBOARD -> true
        Window.EXPANDED_CANDIDATES -> !lettersLast
        Window.LETTERS, Window.OTHER -> false
    }

    /** How the keyboard splits on a screen [widthDp] wide. */
    class Sizes(widthDp: Int) {
        /** The room between the halves: letters at their widest, within [MIN_GAP] and [MAX_GAP]. */
        val gap: Float = (1 - 2f * HALF_KEYS * KEY_DP / widthDp.coerceAtLeast(1)).coerceIn(MIN_GAP, MAX_GAP)

        /** A letter's width split. */
        val letter: Float = (1 - gap) / 2 / HALF_KEYS

        /** A key [width] wide unsplit, split: as wide in letters as it was; 0, the filling key, stays 0. */
        fun width(width: Float): Float = width / LETTER * letter

        /**
         * A row in two halves of [HALF_KEYS] keys each, [left] and [right], as wide as each other
         * so a half is a grid filling its side from the edge to the room between them, [gap] in
         * that room: the keys and widths from left to right, adding up to 1.
         */
        fun <K> row(left: List<K>, right: List<K>, gap: K): List<Pair<K, Float>> {
            require(left.size == HALF_KEYS && right.size == HALF_KEYS) { "a half of other than $HALF_KEYS keys" }
            return left.map { it to letter } + (gap to this.gap) + right.map { it to letter }
        }
    }
}
