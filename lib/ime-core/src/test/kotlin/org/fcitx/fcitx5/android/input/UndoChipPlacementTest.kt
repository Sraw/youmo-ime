/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.input.UndoChipPlacement.Box
import org.fcitx.fcitx5.android.input.UndoChipPlacement.Place
import org.fcitx.fcitx5.android.input.UndoChipPlacement.Slide
import org.junit.Assert.assertEquals
import org.junit.Test

class UndoChipPlacementTest {

    @Test
    fun whereTheChipGoesAgainstTheBarAndTheKeyboard() = assertRows(
        "with no candidates it sits over the bar" to {
            assertEquals(1310, UndoChipPlacement.top(candidates = false, barTop = 1300, aboveTop = 1200, chipHeight = 110, gap = 10))
        },
        "with candidates it goes above the keyboard" to {
            // the chip ends a gap above the keyboard's top: over none of its rows
            assertEquals(1080, UndoChipPlacement.top(candidates = true, barTop = 1300, aboveTop = 1200, chipHeight = 110, gap = 10))
        },
        "with no room above it goes below when there is a below" to {
            assertEquals(410, UndoChipPlacement.top(candidates = true, barTop = 50, aboveTop = 50, chipHeight = 110, gap = 10, belowBottom = 400))
        },
        "with no room above and no below it sits over the bar" to {
            assertEquals(60, UndoChipPlacement.top(candidates = true, barTop = 50, aboveTop = 50, chipHeight = 110, gap = 10))
        },
        "exactly enough room is room" to {
            assertEquals(0, UndoChipPlacement.top(candidates = true, barTop = 120, aboveTop = 120, chipHeight = 110, gap = 10))
        },
        "below that fits the window is used" to {
            // 400 + 10 + 110 = 520: exactly the window's height still fits
            assertEquals(410, UndoChipPlacement.top(candidates = true, barTop = 50, aboveTop = 50, chipHeight = 110, gap = 10, belowBottom = 400, bottomLimit = 520))
        },
        "with no room above or below it sits over the bar" to {
            // a floating keyboard as tall as the screen: below would leave the window
            assertEquals(60, UndoChipPlacement.top(candidates = true, barTop = 50, aboveTop = 50, chipHeight = 110, gap = 10, belowBottom = 400, bottomLimit = 519))
        },
    )

    @Test
    fun dockedItIsAcrossTheMiddleAndSlidesUpUnlessItWentBelow() = assertRows(
        "above the keyboard with candidates" to {
            assertEquals(Place(440, 1080, Slide.Up), docked(candidates = true, barTop = 1300, aboveTop = 1200))
        },
        "over the bar with none, still sliding up" to {
            assertEquals(Place(440, 1310, Slide.Up), docked(candidates = false, barTop = 1300, aboveTop = 1200))
        },
        // a window pinned to the screen's top: it comes down out of it
        "below, sliding down" to {
            assertEquals(Place(440, 410, Slide.Down), docked(candidates = true, barTop = 50, aboveTop = 50, belowBottom = 400))
        },
    )

    private fun docked(candidates: Boolean, barTop: Int, aboveTop: Int, belowBottom: Int? = null) =
        UndoChipPlacement.docked(windowWidth = 1080, chipWidth = 200, candidates = candidates, barTop = barTop, aboveTop = aboveTop, chipHeight = 110, gap = 10, belowBottom = belowBottom)

    // a portrait screen under a status bar, and a floating keyboard narrower than it
    private val portrait = Box(0, 100, 1080, 2300)
    private val landscape = Box(0, 0, 2400, 1000)

    private fun floating(screen: Box, keyboard: Box) = UndoChipPlacement.floating(screen, keyboard, chipWidth = 300, chipHeight = 100, gap = 10)

    @Test
    fun floatingItGoesOnTheKeyboardsSideTowardTheMiddle() = assertRows(
        "low on the screen it goes above, sliding up, centred on the keyboard" to {
            assertEquals(Place(390, 1290, Slide.Up), floating(portrait, Box(90, 1400, 990, 2200)))
        },
        "pinned to the top it goes below, sliding down" to {
            assertEquals(Place(390, 1010, Slide.Down), floating(portrait, Box(90, 100, 990, 1000)))
        },
        // in the middle of a landscape screen's height, against its left edge
        "pinned left it goes to the right, sliding right" to {
            assertEquals(Place(1010, 450, Slide.Right), floating(landscape, Box(0, 200, 1000, 800)))
        },
        "pinned right it goes to the left, sliding left" to {
            assertEquals(Place(1090, 450, Slide.Left), floating(landscape, Box(1400, 200, 2400, 800)))
        },
        // left by 0.58 of half the width, low by 0.45 of half the height
        "off on both axes, the one off more wins: the side" to {
            assertEquals(Slide.Right, floating(landscape, Box(0, 450, 1000, 1000)).slide)
        },
        // left by 0.1, low by 0.45
        "off on both axes, the one off more wins: above" to {
            assertEquals(Slide.Up, floating(landscape, Box(580, 450, 1580, 1000)).slide)
        },
        // dead in the middle of each axis: above, as docked
        "a tie is up and down" to {
            assertEquals(Slide.Up, floating(landscape, Box(700, 250, 1700, 750)).slide)
        },
        // a hair left of the middle (0.07 of half the width) and dead centre up and down, so the side
        // wins; but the 300 wide chip fits neither in the 80 to its right nor anything to its left
        "no room on the side toward the middle tries the other axis" to {
            assertEquals(Place(350, 140, Slide.Up), floating(Box(0, 0, 1080, 1000), Box(0, 250, 1000, 750)))
        },
        // low and a little left, a chip wider than the keyboard: above it, but not off the left edge
        "a chip centred on the keyboard is kept on the screen" to {
            assertEquals(Place(0, 1290, Slide.Up), UndoChipPlacement.floating(portrait, Box(0, 1400, 900, 2200), chipWidth = 1000, chipHeight = 100, gap = 10))
        },
        // a keyboard filling the screen
        "with no room anywhere it goes over the keyboard's top" to {
            assertEquals(Place(390, 110, Slide.Down), floating(portrait, Box(0, 100, 1080, 2300)))
        },
    )
}
