/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

/**
 * The candidate bar's height from 候选字号: at the default [DEFAULT_TEXT_SP] it is the bar's old
 * fixed [DEFAULT_DP]; each sp more or less moves it by two dp, so the words keep the same margin
 * above and below. The keys keep their height: the keyboard grows with the bar (the user, 2026-10-22).
 */
object CandidateBarHeight {
    const val DEFAULT_TEXT_SP = 20
    const val DEFAULT_DP = 40

    fun dp(textSp: Int): Int = DEFAULT_DP + 2 * (textSp - DEFAULT_TEXT_SP)
}
