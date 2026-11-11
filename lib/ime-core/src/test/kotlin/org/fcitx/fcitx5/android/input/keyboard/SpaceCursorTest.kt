/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.SpaceCursor.Line
import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceCursorTest {
    @Test
    fun aLineAStepInAMultiLineField() {
        assertEquals(listOf(Line.Up, Line.Up), SpaceCursor.vertical(-2, multiLine = true, composing = false))
        assertEquals(listOf(Line.Down), SpaceCursor.vertical(1, multiLine = true, composing = false))
        assertEquals(emptyList<Line>(), SpaceCursor.vertical(0, multiLine = true, composing = false))
    }

    @Test
    fun nothingInASingleLineFieldNorWhileComposing() {
        assertEquals(emptyList<Line>(), SpaceCursor.vertical(-1, multiLine = false, composing = false))
        assertEquals(emptyList<Line>(), SpaceCursor.vertical(2, multiLine = true, composing = true))
    }

    @Test
    fun noKeyAtTheFieldsVeryStartOrEnd() {
        assertEquals(false, SpaceCursor.sendable(Line.Up, "", "abc"))
        assertEquals(false, SpaceCursor.sendable(Line.Down, "abc", null))
        assertEquals(true, SpaceCursor.sendable(Line.Up, "c", ""))
        assertEquals(true, SpaceCursor.sendable(Line.Down, null, "a"))
    }

    @Test
    fun aRushedLineOnlyWithANewlineThatWay() {
        assertEquals(false, SpaceCursor.sendable(Line.Up, "aaa", "", rushed = 1))
        assertEquals(true, SpaceCursor.sendable(Line.Up, "a\nb\nc", "", rushed = 1))
        assertEquals(false, SpaceCursor.sendable(Line.Down, "a\nb", "cc", rushed = 1))
        assertEquals(true, SpaceCursor.sendable(Line.Down, "", "c\nd\ne", rushed = 1))
    }

    @Test
    fun aRushIsHeldToTheNewlinesThatWay() {
        // "\nabc", the cursor at 1: the first Up goes to 0, the second reads the same stale text
        assertEquals(true, SpaceCursor.sendable(Line.Up, "\n", "abc", rushed = 0))
        assertEquals(false, SpaceCursor.sendable(Line.Up, "\n", "abc", rushed = 1))
        // a flick of three lines over one newline: the first goes out, the rest held, as the reads are stale
        val before = "aa\nbb"
        assertEquals(listOf(true, false, false), (0..2).map { SpaceCursor.sendable(Line.Up, before, "", rushed = it) })
    }
}
