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
}
