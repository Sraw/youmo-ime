/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.cards

import kotlin.math.floor

/**
 * The settings cards' arithmetic (dev/design/061-settings-cards.md §2.3, §2.9, §3.1): how many columns
 * a row of cards gets, the size of a keyboard card's name, whether a group card draws its samples.
 * Sizes are in dp, the font scale is the system's.
 */
object CardColumns {

    const val EDGE_DP = 16
    const val GAP_DP = 8

    /** Keyboard (choice) cards: 8 of them, none of these counts leaves one alone on the last row. */
    val CHOICE: List<Int> = listOf(8, 4, 3, 2)
    const val CHOICE_MIN_DP = 76

    /** Rare characters' group cards: 4 of them. */
    val GROUP: List<Int> = listOf(4, 2, 1)
    const val GROUP_MIN_DP = 132

    /** Below this card width (× the font scale) a keyboard card's name is [NAME_SMALL_SP], else [NAME_SP]. */
    const val NAME_WIDE_DP = 82
    const val NAME_SP = 13
    const val NAME_SMALL_SP = 12

    /** A window lower than this leaves out the group cards' samples, so all four still show (§3.1). */
    const val SAMPLES_MIN_HEIGHT_DP = 600

    /**
     * The most columns of [candidates] (largest first) that fit [widthDp], the screen's width with
     * its [EDGE_DP] margins still to take off, each column at least [minDp] × [fontScale] wide with
     * [GAP_DP] between: n = ⌊(width − 2·edge + gap) ÷ (min × scale + gap)⌋, the largest candidate
     * not over n; the smallest candidate when none fits. Callers pass `Configuration.screenWidthDp`
     * (the rare page, whose content is at most 600 dp wide: the smaller of that and 600), and
     * `Configuration.fontScale`.
     */
    fun columns(widthDp: Float, fontScale: Float, minDp: Int, candidates: List<Int>): Int {
        require(candidates.isNotEmpty() && candidates.all { it > 0 }) { "candidates $candidates" }
        require(minDp > 0 && fontScale > 0f) { "min $minDp dp × $fontScale" }
        val fit = floor((widthDp - 2 * EDGE_DP + GAP_DP) / (minDp * fontScale + GAP_DP)).toInt()
        return candidates.firstOrNull { it <= fit } ?: candidates.last()
    }

    /** A card's width when [columns] of them share [widthDp], the screen's width as for [columns]. */
    fun cardWidth(widthDp: Float, columns: Int): Float {
        require(columns > 0) { "columns $columns" }
        return (widthDp - 2 * EDGE_DP - GAP_DP * (columns - 1)) / columns
    }

    /** A keyboard card's name size in sp, for a card [cardWidthDp] wide. */
    fun nameSp(cardWidthDp: Float, fontScale: Float): Int =
        if (cardWidthDp >= NAME_WIDE_DP * fontScale) NAME_SP else NAME_SMALL_SP

    /** Whether the group cards draw their samples' row in a window [windowHeightDp] high. */
    fun samples(windowHeightDp: Float): Boolean = windowHeightDp >= SAMPLES_MIN_HEIGHT_DP
}
