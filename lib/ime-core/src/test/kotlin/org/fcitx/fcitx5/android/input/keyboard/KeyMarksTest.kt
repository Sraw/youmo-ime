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

    @Test
    fun oneMarkGoesWherePunctuationPositionSays() {
        assertEquals(Placed(topRight = "1"), KeyMarks.place("1", null, null, Position.TopRight, landscape = false))
        assertEquals(Placed(below = "1"), KeyMarks.place("1", null, null, Position.Bottom, landscape = false))
        assertEquals("landscape: no room below", Placed(topRight = "1"), KeyMarks.place("1", null, null, Position.Bottom, landscape = true))
        assertEquals(Placed<String>(), KeyMarks.place("1", null, null, Position.None, landscape = false))
        assertEquals(Placed<String>(), KeyMarks.place<String>(null, null, null, Position.TopRight, landscape = false))
    }

    @Test
    fun aShortcutNoWayReachesIsNotDrawn() {
        assertEquals(Placed(topRight = "@"), KeyMarks.place("@", "全选", null, Position.TopRight, landscape = false))
        assertEquals("the shortcut alone", Placed(below = "全选"), KeyMarks.place(null, "全选", true, Position.Bottom, landscape = false))
    }

    @Test
    fun twoMarksTakeTheirWaysCorners() {
        assertEquals(Placed(topRight = "全选", bottomRight = "@"), KeyMarks.place("@", "全选", true, Position.Bottom, landscape = false))
        assertEquals(Placed(topRight = "@", bottomRight = "全选"), KeyMarks.place("@", "全选", false, Position.TopRight, landscape = true))
        assertEquals(Placed<String>(), KeyMarks.place("@", "全选", true, Position.None, landscape = false))
    }

    @Test
    fun aFunctionKeysUpTopRightDownBottomRight() {
        assertEquals(Placed(topRight = "清空", bottomRight = "恢复"), KeyMarks.placeFunction("清空", "恢复", upOn = true, downOn = true, Position.Bottom))
        assertEquals(Placed(topRight = "清空"), KeyMarks.placeFunction("清空", "恢复", upOn = true, downOn = false, Position.TopRight))
        assertEquals(Placed<String>(), KeyMarks.placeFunction("清空", "恢复", upOn = true, downOn = true, Position.None))
    }
}
