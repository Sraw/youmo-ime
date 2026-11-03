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
        KeyboardHeightLevels.ALL.forEachIndexed { i, l -> assertEquals(i, KeyboardHeightLevels.nearest(l.portrait, l.landscape)) }
        assertEquals(listOf(20 to 34, 26 to 42, 30 to 49, 34 to 55, 38 to 60), KeyboardHeightLevels.ALL.map { it.portrait to it.landscape })
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
        assertTrue(KeyboardHeightLevels.isCompact(23, 38))
        assertEquals(1, KeyboardHeightLevels.nearest(24, 40))
        assertEquals(KeyboardHeightLevels.COMPACT, KeyboardHeightLevels.nearest(10, 10))
        assertEquals(4, KeyboardHeightLevels.nearest(90, 90))
    }
}
