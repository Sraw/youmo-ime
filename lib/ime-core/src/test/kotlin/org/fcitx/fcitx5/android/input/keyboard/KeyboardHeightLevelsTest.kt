/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardHeightLevelsTest {

    @Test
    fun eachLevelIsItsOwn() {
        KeyboardHeightLevels.ALL.forEachIndexed { i, l -> assertEquals(i, KeyboardHeightLevels.nearest(l.portrait, l.landscapeLevel)) }
        assertEquals(listOf(20 to 34, 26 to 42, 30 to 49, 34 to 55, 38 to 60), KeyboardHeightLevels.ALL.map { it.portrait to it.landscapeLevel })
    }

    @Test
    fun onlyTheLowestIsCompact() {
        assertTrue(KeyboardHeightLevels.isCompact(20, 34))
        assertFalse(KeyboardHeightLevels.isCompact(26, 42))
        assertFalse("the default, 标准", KeyboardHeightLevels.isCompact(30, 49))
    }

    @Test
    fun aValueBetweenIsAtTheNearest() {
        // set by an older version's sliders: no migration, shown (and drawn) as the nearest level
        assertTrue(KeyboardHeightLevels.isCompact(22, 37))
        assertEquals(1, KeyboardHeightLevels.nearest(24, 40))
        assertEquals(KeyboardHeightLevels.COMPACT, KeyboardHeightLevels.nearest(10, 10))
        assertEquals(4, KeyboardHeightLevels.nearest(90, 90))
    }

    @Test
    fun exactlyHalfwayIsTheLowerLevel() {
        // the old 23% / 38%, as far from 紧凑's 20 / 34 as from the next's 26 / 42: 紧凑
        assertTrue(KeyboardHeightLevels.isCompact(23, 38))
    }

    @Test
    fun turnedALevelIsARowOfTheTable() {
        // a phone's rows are a common phone's today, a large screen's from Gboard's 56
        val phone = KeyboardHeightLevels.ALL.map { KeyboardHeightLevels.landscapeRowDp(it.landscapeLevel.toDouble(), large = false) }
        val large = KeyboardHeightLevels.ALL.map { KeyboardHeightLevels.landscapeRowDp(it.landscapeLevel.toDouble(), large = true) }
        assertEquals(listOf(34.0, 41.0, 48.0, 54.0, 59.0), phone)
        assertEquals(listOf(48.0, 52.0, 56.0, 60.0, 64.0), large)
    }

    @Test
    fun turnedAValueBetweenLevelsIsStraightBetweenTheirRows() {
        // a theme's 45, an old dragged 52: between 矮 (42, 41 dp) and 标准 (49, 48 dp), and between 标准 and 高
        assertEquals(44.0, KeyboardHeightLevels.landscapeRowDp(45.0, large = false), 1e-9)
        assertEquals(51.0, KeyboardHeightLevels.landscapeRowDp(52.0, large = false), 1e-9)
        assertEquals(54.0, KeyboardHeightLevels.landscapeRowDp(45.5, large = true), 1e-9)
        // under 紧凑's 34, half a dp a level, down to 28; over 很高's 60, 0.8 dp a level
        assertEquals(32.0, KeyboardHeightLevels.landscapeRowDp(30.0, large = false), 1e-9)
        assertEquals(28.0, KeyboardHeightLevels.landscapeRowDp(10.0, large = false), 1e-9)
        assertEquals(59.0 + 0.8 * 30, KeyboardHeightLevels.landscapeRowDp(90.0, large = false), 1e-9)
        assertEquals(64.0 + 0.8 * 5, KeyboardHeightLevels.landscapeRowDp(65.0, large = true), 1e-9)
    }
}
