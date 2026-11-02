/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.UndoChipPlacement.Box
import org.fcitx.fcitx5.android.input.preedit.CodeStripPlacement.Place
import org.junit.Assert.assertEquals
import org.junit.Test

class CodeStripPlacementTest {

    private val portrait = Box(0, 80, 1080, 2340)
    private val landscape = Box(0, 0, 2340, 1080)

    @Test
    fun outsideTheBoardsTopLeftOrUnderItWithNoRoomAbove() = assertRows(
        listOf(
            "docked across: the screen's left, over the keyboard" to {
                assertEquals(Place(0, 1500, false), CodeStripPlacement.place(Box(0, 1566, 1080, 2340), portrait, 300, 66))
            },
            "one-handed at the right: the board's left, not the screen's" to {
                assertEquals(Place(240, 1500, false), CodeStripPlacement.place(Box(240, 1566, 1080, 2340), portrait, 300, 66))
            },
            "floating in the middle" to {
                assertEquals(Place(90, 1134, false), CodeStripPlacement.place(Box(90, 1200, 990, 1900), portrait, 300, 66))
            },
            "floating at the top: no room above, under it" to {
                assertEquals(Place(90, 800, true), CodeStripPlacement.place(Box(90, 100, 990, 800), portrait, 300, 66))
            },
            "landscape, wide" to {
                assertEquals(Place(0, 434, false), CodeStripPlacement.place(Box(0, 500, 2340, 1080), landscape, 900, 66))
            },
            "as wide as the board, no wider: kept in the room at the right" to {
                assertEquals(Place(180, 1134, false), CodeStripPlacement.place(Box(300, 1200, 1200, 1900), portrait, 900, 66))
            },
            "left of the room: kept in at the left" to {
                assertEquals(Place(0, 1134, false), CodeStripPlacement.place(Box(-50, 1200, 850, 1900), portrait, 300, 66))
            },
            "no room above or below: over the board's top, under the status bar" to {
                assertEquals(Place(0, 80, false), CodeStripPlacement.place(Box(0, 106, 1080, 2340), portrait, 300, 66))
            },
        ),
        { it.first },
    ) { it.second() }
}
