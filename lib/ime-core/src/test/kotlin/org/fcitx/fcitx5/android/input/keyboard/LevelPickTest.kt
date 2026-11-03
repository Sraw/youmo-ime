/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class LevelPickTest {

    private val heights = listOf(listOf(26, 42), listOf(30, 49), listOf(38, 60))

    @Test
    fun theNearestLevelToWhatIsHeld() {
        assertEquals(1, LevelPick.nearest(listOf(30, 49), heights))
        // a number at a time, set by an older version
        assertEquals(2, LevelPick.nearest(listOf(36, 58), heights))
        assertEquals(0, LevelPick.nearest(listOf(10, 10), heights))
    }

    @Test
    fun valuesOfOtherUnitsWeighAlike() {
        // the vibration's: ms, and amplitudes of 0..255, each over its own span. 35/45 ms at the device's
        // own amplitude (0) is 2 from the system's and from 强 alike, 1.9 from 轻: it vibrates, so not the system's
        val vibration = listOf(listOf(0, 0, 0, 0), listOf(10, 20, 60, 90), listOf(20, 30, 128, 160), listOf(35, 45, 220, 255))
        assertEquals(1, LevelPick.nearest(listOf(35, 45, 0, 0), vibration))
    }

    @Test
    fun aValueTheLevelsAllShareWeighsNothing() {
        // a span of 0 (every level sets it alike) divides by 1, not 0
        assertEquals(1, LevelPick.nearest(listOf(5, 29), listOf(listOf(5, 20), listOf(5, 30))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun levelsOfAnotherLengthAreRefused() {
        LevelPick.nearest(listOf(1, 2), listOf(listOf(1)))
    }
}
