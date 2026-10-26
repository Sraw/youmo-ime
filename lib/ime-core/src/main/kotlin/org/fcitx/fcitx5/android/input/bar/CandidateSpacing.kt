/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import kotlin.math.roundToInt

/**
 * A candidate's width from 候选字号: its text plus [PADDING_PER_SP] × the text size on each side
 * (9 dp at the default 20 sp, where it used to be a fixed 10 dp), never under [TOUCH_FLOOR_DP].
 * The bar shows as many as fit at that natural width, so small text packs more words onto the
 * first page (the user, 2026-10-25), and a single character stays tappable.
 */
object CandidateSpacing {
    const val TOUCH_FLOOR_DP = 32
    const val PADDING_PER_SP = 0.45f

    // big text needs no wider gap than the old fixed one (the user, 2026-10-25)
    const val MAX_PADDING_DP = 10

    /**
     * The padding each side of a word of [textSp] (候选字号's value). Taken from the setting's sp, so
     * the 0.45 holds at font scale 1: a larger system font scales the text, not this gap.
     */
    fun paddingDp(textSp: Int): Int = minOf((PADDING_PER_SP * textSp).roundToInt(), MAX_PADDING_DP)

    /**
     * A word's width as the bar lays it out: [paddingDp] each side (KawaiiBarComponent), at least
     * [TOUCH_FLOOR_DP] (the adapter's minimumWidth). The app builds it from those two, not from
     * this; the tests count what fits with it, so it changes with them.
     */
    fun widthDp(textWidthDp: Int, textSp: Int): Int = maxOf(textWidthDp + 2 * paddingDp(textSp), TOUCH_FLOOR_DP)

    /**
     * Whether the words on the bar share its leftover width (AutoFillWidth): only when more wait
     * behind ⌄, where the leftover is less than the next word. When every word fits they stay at
     * their natural width, packed from the start: the user wants the bar compact (2026-10-25).
     * A negative [total] is unknown, so more may wait.
     */
    fun stretches(shown: Int, total: Int): Boolean = total < 0 || shown < total
}
