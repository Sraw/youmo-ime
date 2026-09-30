/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.junit.Assert.assertEquals
import org.junit.Test

class WordScorerTest {

    @Test
    fun aScorerWithoutItsOwnContextGetsBothWordsBack() {
        val seen = ArrayList<List<Int>>()
        val scorer = WordScorer { prev2, prev, word -> seen += listOf(prev2, prev, word); 0f }
        val ids = listOf(NO_WORD, -2, Int.MIN_VALUE, 0, 1, 70_000, Int.MAX_VALUE)
        for (prev2 in ids) for (prev in ids) scorer.scoreAfter(scorer.context(prev2, prev), 3)
        assertEquals(ids.flatMap { prev2 -> ids.map { listOf(prev2, it, 3) } }, seen)
    }
}
