/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.KeyMarks.Placed
import org.fcitx.fcitx5.android.input.keyboard.KeyMarks.Position
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyMarksTest {

    private val positions = Position.entries
    private val bools = listOf(false, true)

    @Test
    fun theSymbolGoesWherePunctuationPositionSays() {
        assertEquals(Placed(topRight = "1"), KeyMarks.place("1", null, false, Position.TopRight, landscape = false))
        assertEquals(Placed(below = "1"), KeyMarks.place("1", null, false, Position.Bottom, landscape = false))
        assertEquals("landscape: no room below", Placed(topRight = "1"), KeyMarks.place("1", null, false, Position.Bottom, landscape = true))
        assertEquals(Placed<String>(), KeyMarks.place("1", null, false, Position.None, landscape = false))
        assertEquals(Placed<String>(), KeyMarks.place<String>(null, null, false, Position.TopRight, landscape = false))
    }

    @Test
    fun theShortcutTopLeftTheSymbolStaysPut() {
        // the 0.5.1 testers' keyboard: @ pushed to the bottom right by the shortcut, back as 滑动 changed
        assertEquals(Placed(topLeft = "全选", topRight = "@"), KeyMarks.place("@", "全选", true, Position.TopRight, landscape = false))
        assertEquals(Placed(topLeft = "全选", below = "@"), KeyMarks.place("@", "全选", true, Position.Bottom, landscape = false))
        assertEquals(Placed(topLeft = "全选", topRight = "@"), KeyMarks.place("@", "全选", true, Position.Bottom, landscape = true))
        assertEquals("the shortcut alone", Placed(topLeft = "全选"), KeyMarks.place(null, "全选", true, Position.Bottom, landscape = false))
        assertEquals(Placed<String>(), KeyMarks.place("@", "全选", true, Position.None, landscape = false))
    }

    @Test
    fun theSymbolIsWhereItIsWithAShortcutOrWithout() {
        for (p in positions) for (landscape in bools) for (compact in bools) {
            val alone = KeyMarks.place("@", null, false, p, landscape, compact)
            val with = KeyMarks.place("@", "全选", true, p, landscape, compact)
            assertEquals("$p $landscape $compact", alone, with.copy(topLeft = null))
            assertEquals("$p $landscape $compact", if (p == Position.None) null else "全选", with.topLeft)
        }
    }

    @Test
    fun aShortcutNoWayReachesIsNotDrawn() {
        assertEquals(Placed(topRight = "@"), KeyMarks.place("@", "全选", false, Position.TopRight, landscape = false))
        assertEquals(Placed<String>(), KeyMarks.place(null, "全选", false, Position.TopRight, landscape = false))
    }

    @Test
    fun compactDrawsTheSymbolTopRightAndTheShortcutToo() {
        // 底部 too: the key is too low for a line under the letter, upright as well
        for (landscape in bools) {
            assertEquals(Placed(topRight = "1"), KeyMarks.place("1", null, false, Position.Bottom, landscape, compact = true))
            assertEquals(Placed(topRight = "1"), KeyMarks.place("1", null, false, Position.TopRight, landscape, compact = true))
            assertEquals(Placed<String>(), KeyMarks.place("1", null, false, Position.None, landscape, compact = true))
            assertEquals(Placed(topLeft = "全选", topRight = "@"), KeyMarks.place("@", "全选", true, Position.Bottom, landscape, compact = true))
        }
    }

    @Test
    fun aFunctionKeysUpTopLeftDownBottomLeft() {
        assertEquals(Placed(topLeft = "清除", bottomLeft = "恢复"), KeyMarks.placeFunction("清除", "恢复", upOn = true, downOn = true, Position.Bottom))
        assertEquals(Placed(topLeft = "清除"), KeyMarks.placeFunction("清除", "恢复", upOn = true, downOn = false, Position.TopRight))
        assertEquals("the down one not moved up", Placed(bottomLeft = "恢复"), KeyMarks.placeFunction("清除", "恢复", upOn = false, downOn = true, Position.TopRight))
        assertEquals(Placed<String>(), KeyMarks.placeFunction("清除", "恢复", upOn = true, downOn = true, Position.None))
    }
}
