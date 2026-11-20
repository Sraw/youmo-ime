/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.rules

/** The letters keyboard split in two for the thumbs, as Gboard splits it; widths are shares of the keyboard's width. */
object SplitKeyboard {

    const val HALF_KEYS = 5

    const val KEY_DP = 48

    const val MIN_GAP = 0.12
    const val MAX_GAP = 0.4

    private const val LETTER = 0.1

    /** Across a wide screen the keys at its middle are out of either thumb's reach, so the other boards go to a side. */
    enum class Dock { RIGHT, LEFT, FULL }

    const val DOCK_SHARE = 0.42
    const val DOCK_MIN_DP = 320
    const val DOCK_MAX_DP = 420

    fun dockWidth(widthDp: Int): Int =
        (widthDp * DOCK_SHARE).toInt().coerceIn(DOCK_MIN_DP, DOCK_MAX_DP).coerceAtMost(widthDp)

    const val DOCK_MIN_FREE_DP = 120

    /** Null on a keyboard a board at a side would all but fill (a phone upright split all the same). */
    fun side(dock: Dock, splitting: Boolean, lettersShown: Boolean, widthDp: Int): Dock? {
        if (!splitting || lettersShown || dock == Dock.FULL) return null
        return dock.takeIf { widthDp - dockWidth(widthDp) >= DOCK_MIN_FREE_DP }
    }

    const val ONE_HANDED_SHARE = 0.8

    const val ONE_HANDED_MIN_FREE_DP = 56

    data class Placement(val side: Dock, val widthDp: Int)

    fun placement(oneHanded: Dock, dock: Dock, splitting: Boolean, lettersShown: Boolean, widthDp: Int): Placement? = when {
        splitting -> side(dock, true, lettersShown, widthDp)?.let { Placement(it, dockWidth(widthDp)) }
        oneHanded == Dock.FULL -> null
        else -> (widthDp * ONE_HANDED_SHARE).toInt().coerceAtMost(DOCK_MAX_DP)
            .takeIf { widthDp - it >= ONE_HANDED_MIN_FREE_DP }?.let { Placement(oneHanded, it) }
    }

    enum class Window { LETTERS, OTHER_LAYOUT, PICKER, CLIPBOARD, EXPANDED_CANDIDATES, OTHER }

    /** The expanded candidates go as the board they are opened from, so they neither widen nor narrow it. */
    fun docks(window: Window, lettersLast: Boolean): Boolean = when (window) {
        Window.OTHER_LAYOUT, Window.PICKER, Window.CLIPBOARD -> true
        Window.EXPANDED_CANDIDATES -> !lettersLast
        Window.LETTERS, Window.OTHER -> false
    }

    class Sizes(widthDp: Int) {
        val gap: Double = (1 - 2.0 * HALF_KEYS * KEY_DP / widthDp.coerceAtLeast(1)).coerceIn(MIN_GAP, MAX_GAP)

        val letter: Double = (1 - gap) / 2 / HALF_KEYS

        fun width(width: Double): Double = width / LETTER * letter

        fun <K> row(left: List<K>, right: List<K>, gap: K): List<Pair<K, Double>> {
            require(left.size == HALF_KEYS && right.size == HALF_KEYS) { "a half of other than $HALF_KEYS keys" }
            return left.map { it to letter } + (gap to this.gap) + right.map { it to letter }
        }
    }
}
