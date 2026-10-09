/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.candidates.expanded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where the expanded candidates' pages end, the last candidate on a page of its own included. */
class CandidatesPagingSourceTest {

    @Test
    fun theLastCandidateAloneOnAPageIsStillLoaded() {
        // the 5 on the bar, then 144: one is left, index 149
        assertEquals(149, CandidatesPagingSource.nextKey(startIndex = 5, loaded = 144, pageSize = 144, total = 150))
        assertNull(CandidatesPagingSource.nextKey(startIndex = 149, loaded = 1, pageSize = 48, total = 150))
    }

    @Test
    fun aPageThatReachesTheTotalIsTheLast() {
        assertNull(CandidatesPagingSource.nextKey(startIndex = 0, loaded = 48, pageSize = 48, total = 48))
        assertEquals(48, CandidatesPagingSource.nextKey(startIndex = 0, loaded = 48, pageSize = 48, total = 49))
    }

    @Test
    fun withoutATotalAShortPageIsTheLast() {
        assertEquals(48, CandidatesPagingSource.nextKey(startIndex = 0, loaded = 48, pageSize = 48, total = -1))
        assertNull(CandidatesPagingSource.nextKey(startIndex = 48, loaded = 20, pageSize = 48, total = -1))
    }
}
