/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.keyboard.SpaceCursor.Line
import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceCursorTest {
    @Test
    fun theLinesAVerticalMoveSends() {
        assertRows(
            "a multi-line field: a line a step, up" to { assertEquals(listOf(Line.Up, Line.Up), SpaceCursor.vertical(-2, multiLine = true, composing = false)) },
            "a multi-line field: a line a step, down" to { assertEquals(listOf(Line.Down), SpaceCursor.vertical(1, multiLine = true, composing = false)) },
            "no step, no line" to { assertEquals(emptyList<Line>(), SpaceCursor.vertical(0, multiLine = true, composing = false)) },
            "a single-line field has no lines to go to" to { assertEquals(emptyList<Line>(), SpaceCursor.vertical(-1, multiLine = false, composing = false)) },
            "while composing the move is the composition's" to { assertEquals(emptyList<Line>(), SpaceCursor.vertical(2, multiLine = true, composing = true)) },
        )
    }

    @Test
    @Uat("KB-71")
    fun whetherALineKeyIsSent() {
        assertRows(
            "no Up at the field's very start" to { assertEquals(false, SpaceCursor.sendable(Line.Up, "", "abc")) },
            "no Down at the field's very end (nothing after)" to { assertEquals(false, SpaceCursor.sendable(Line.Down, "abc", null)) },
            "Up with text before" to { assertEquals(true, SpaceCursor.sendable(Line.Up, "c", "")) },
            "Down with text after, before unknown" to { assertEquals(true, SpaceCursor.sendable(Line.Down, null, "a")) },
            "a rushed Up with no newline before: held" to { assertEquals(false, SpaceCursor.sendable(Line.Up, "aaa", "", rushed = 1)) },
            "a rushed Up with a newline before: sent" to { assertEquals(true, SpaceCursor.sendable(Line.Up, "a\nb\nc", "", rushed = 1)) },
            "a rushed Down with no newline after: held" to { assertEquals(false, SpaceCursor.sendable(Line.Down, "a\nb", "cc", rushed = 1)) },
            "a rushed Down with a newline after: sent" to { assertEquals(true, SpaceCursor.sendable(Line.Down, "", "c\nd\ne", rushed = 1)) },
            // "\nabc", the cursor at 1: the first Up goes to 0, the second reads the same stale text
            "the first Up over the one newline goes" to { assertEquals(true, SpaceCursor.sendable(Line.Up, "\n", "abc", rushed = 0)) },
            "a second Up on the same stale text is held" to { assertEquals(false, SpaceCursor.sendable(Line.Up, "\n", "abc", rushed = 1)) },
            "a flick of three lines over one newline: the first goes, the rest held, as the reads are stale" to {
                assertEquals(listOf(true, false, false), (0..2).map { SpaceCursor.sendable(Line.Up, "aa\nbb", "", rushed = it) })
            },
        )
    }

    @Test
    fun linesSentTogetherOneWayCountAsRushed() {
        val rush = SpaceCursor.LineRush()
        assertEquals(0, rush.rushed(Line.Up, now = 1000))
        rush.sent(Line.Up, now = 1000)
        assertEquals(1, rush.rushed(Line.Up, now = 1010))
        rush.sent(Line.Up, now = 1010)
        assertEquals(2, rush.rushed(Line.Up, now = 1020))
    }

    @Test
    fun aLineTheOtherWayRightAfterIsNotRushed() {
        // a step's jitter: Up then Down within the window; the Down on the next-to-last line must go out
        val rush = SpaceCursor.LineRush()
        rush.sent(Line.Up, now = 1000)
        assertEquals(0, rush.rushed(Line.Down, now = 1010))
        assertEquals(true, SpaceCursor.sendable(Line.Down, null, "x\ny", rush.rushed(Line.Down, now = 1010)))
    }

    @Test
    fun theRushEndsOnceTheEditorHadTimeToMove() {
        val rush = SpaceCursor.LineRush()
        rush.sent(Line.Down, now = 1000)
        assertEquals(0, rush.rushed(Line.Down, now = 1000 + SpaceCursor.LineRush.SETTLE_MS))
    }
}
