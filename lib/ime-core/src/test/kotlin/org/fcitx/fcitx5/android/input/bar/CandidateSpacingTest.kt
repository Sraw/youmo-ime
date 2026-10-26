/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The candidate bar's spacing as numbers: a word's padding and width by the text size, and when
 * words stretch. The bar itself (KawaiiBarComponent, AutoFillWidth) is BarUatTest's.
 */
class CandidateSpacingTest {

    @Test
    fun paddingFollowsTheTextUpToTheCap() = assertRows(
        listOf(
            Triple(14, 6, "the smallest size: tight"),
            Triple(20, 9, "the default"),
            Triple(21, 9, "the last size under the cap"),
            Triple(22, 10, "the first at the cap"),
            Triple(23, 10, "past the cap: held at it"),
            Triple(28, 10, "the largest size: held at the cap"),
        ),
        { (sp, _, why) -> "$sp sp, $why" },
    ) { (sp, dp, _) -> assertEquals(dp, CandidateSpacing.paddingDp(sp)) }

    @Test
    fun aSingleCharacterKeepsTheTouchFloor() {
        // a CJK character is about as wide as its size
        assertEquals(listOf(32, 38, 48), listOf(14, 20, 28).map { CandidateSpacing.widthDp(it, it) })
        assertEquals(58, CandidateSpacing.widthDp(40, 20))
    }

    @Test
    fun smallerTextFitsMoreOfNihaoInTheBar() {
        // 你好 then single characters, in the 353 dp beside ⌄ on a 393 dp phone (337 at 28 sp, its ⌄ is wider).
        // A model of the bar's packing (1 dp between words), not the bar: what it shows is BarUatTest's
        fun fit(sp: Int, barDp: Int): Int {
            var left = barDp
            return (listOf(2) + List(20) { 1 }).takeWhile { n ->
                left -= CandidateSpacing.widthDp(n * sp, sp) + 1
                left >= 0
            }.size
        }
        assertEquals(listOf(10, 8, 6), listOf(14 to 353, 20 to 353, 28 to 337).map { (sp, w) -> fit(sp, w) })
    }

    @Test
    fun wordsStretchOnlyWhenMoreWait() {
        assertEquals(true, CandidateSpacing.stretches(6, 16))
        assertEquals(false, CandidateSpacing.stretches(3, 3))
        assertEquals(true, CandidateSpacing.stretches(16, -1))
    }
}
