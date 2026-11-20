/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import kotlin.math.roundToInt

/** A candidate's width: its text plus [PADDING_PER_SP] × text size each side, at least [TOUCH_FLOOR_DP]; small text packs more on the first page. */
object CandidateSpacing {
    const val TOUCH_FLOOR_DP = 32
    const val PADDING_PER_SP = 0.45f

    // big text needs no wider gap than the old fixed one
    const val MAX_PADDING_DP = 10

    /** From the setting's sp: a larger system font scales the text, not this gap. */
    fun paddingDp(textSp: Int): Int = minOf((PADDING_PER_SP * textSp).roundToInt(), MAX_PADDING_DP)

    /** As the bar lays it out (KawaiiBarComponent, the adapter's minimumWidth); the app does not call this, the tests count with it. */
    fun widthDp(textWidthDp: Int, textSp: Int): Int = maxOf(textWidthDp + 2 * paddingDp(textSp), TOUCH_FLOOR_DP)

    /** Only when more wait behind ⌄; all fitting, they stay packed at natural width. Negative [total]: unknown. */
    fun stretches(shown: Int, total: Int): Boolean = total < 0 || shown < total
}
