/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HalvesTest {

    @Test
    fun aSentenceIsInTheSameHalfHoweverTyped() {
        val a = Sample("nihao", "你好", "daily")
        assertEquals(Halves.of(a), Halves.of(Sample("nh", "你好", "abbrev")))
        assertEquals(Halves.of(a), Halves.of(Sample("nihc", "你好", "fuzzy-l_n")))
        assertEquals(listOf(a), Halves.select(listOf(a), null))
    }

    @Test
    fun theSetSplitsRoughlyInHalf() {
        val samples = File("data/pinyin.tsv").useLines { EvalSet.parse(it) }
        val tune = Halves.select(samples, Halves.TUNE).size
        val heldOut = Halves.select(samples, Halves.HELD_OUT).size
        assertTrue("$tune / $heldOut", tune in samples.size * 2 / 5..samples.size * 3 / 5)
    }
}
