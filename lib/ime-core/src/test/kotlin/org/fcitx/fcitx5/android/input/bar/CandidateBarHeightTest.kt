/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateBarHeightTest {

    @Test
    fun theBarFollowsTheTextAndTheDefaultKeepsTodaysBar() {
        assertEquals(listOf(28, 40, 56), listOf(14, 20, 28).map(CandidateBarHeight::dp))
    }
}
