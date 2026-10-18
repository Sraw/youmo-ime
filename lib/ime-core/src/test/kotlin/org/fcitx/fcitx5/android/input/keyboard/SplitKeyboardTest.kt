/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.SplitKeyboard.Mode
import org.fcitx.fcitx5.android.input.keyboard.SplitKeyboard.Sizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitKeyboardTest {

    @Test
    fun autoSplitsAWideScreenHeldSidewaysOnly() {
        assertTrue(SplitKeyboard.splits(Mode.AUTO, landscape = true, widthDp = 800))
        // a screen narrower on its side than a phone's is not split, nor a tablet upright
        assertFalse(SplitKeyboard.splits(Mode.AUTO, landscape = true, widthDp = 560))
        assertFalse(SplitKeyboard.splits(Mode.AUTO, landscape = false, widthDp = 800))
        assertTrue(SplitKeyboard.splits(Mode.ALWAYS, landscape = false, widthDp = 360))
        assertFalse(SplitKeyboard.splits(Mode.OFF, landscape = true, widthDp = 1280))
    }

    @Test
    fun eachHalfIsAFullGridAgainstTheRoomInTheMiddle() {
        val sizes = Sizes(800)
        val row = sizes.row(listOf("q", "w", "e", "r", "t"), listOf("y", "u", "i", "o", "p"), "_")
        assertEquals(listOf("q", "w", "e", "r", "t", "_", "y", "u", "i", "o", "p"), row.map { it.first })
        assertEquals(1f, row.sumOf { it.second.toDouble() }.toFloat(), 1e-5f)
        // nothing at the edges: the gap in the middle, the letters as wide as each other
        assertEquals(sizes.gap, row[5].second)
        assertEquals(1, row.map { it.second }.filterIndexed { i, _ -> i != 5 }.distinct().size)
    }

    @Test
    fun theRoomBetweenIsWhatTenLettersAtTheirWidestLeave() {
        // a small phone on its side: 480 of 640 dp in letters
        assertEquals(0.25f, Sizes(640).gap, 1e-5f)
        assertEquals(640 * Sizes(640).letter, SplitKeyboard.KEY_DP.toFloat(), 1e-3f)
        // a tablet: no wider than MAX_GAP, the letters wider then
        assertEquals(SplitKeyboard.MAX_GAP, Sizes(1280).gap)
        // a phone upright, split all the same: letters narrower than their widest, some room kept
        assertEquals(SplitKeyboard.MIN_GAP, Sizes(360).gap)
    }

    @Test
    fun theBottomRowKeepsItsKeysAsWideInLettersAndTheSpaceFills() {
        val sizes = Sizes(800)
        assertEquals(sizes.letter * 1.5f, sizes.width(0.15f), 1e-6f)
        assertEquals(0f, sizes.width(0f))
    }

    @Test
    fun theOtherBoardsGoToASideOnlyWhileTheLettersSplit() {
        val dock = SplitKeyboard.Dock.RIGHT
        assertEquals(dock, SplitKeyboard.side(dock, splitting = true, lettersShown = false, widthDp = 851))
        assertEquals(SplitKeyboard.Dock.LEFT, SplitKeyboard.side(SplitKeyboard.Dock.LEFT, splitting = true, lettersShown = false, widthDp = 851))
        assertEquals(null, SplitKeyboard.side(dock, splitting = true, lettersShown = true, widthDp = 851))
        assertEquals(null, SplitKeyboard.side(dock, splitting = false, lettersShown = false, widthDp = 851))
        assertEquals(null, SplitKeyboard.side(SplitKeyboard.Dock.FULL, splitting = true, lettersShown = false, widthDp = 851))
    }

    @Test
    fun theLayoutsPickersAndClipboardDockAndVoiceEditingAndTheLettersDoNot() {
        for (letters in listOf(true, false)) {
            assertTrue(SplitKeyboard.docks(SplitKeyboard.Window.OTHER_LAYOUT, lettersLast = letters))
            assertTrue(SplitKeyboard.docks(SplitKeyboard.Window.PICKER, lettersLast = letters))
            assertTrue(SplitKeyboard.docks(SplitKeyboard.Window.CLIPBOARD, lettersLast = letters))
            assertFalse(SplitKeyboard.docks(SplitKeyboard.Window.LETTERS, lettersLast = letters))
            // voice, editing, the status area
            assertFalse(SplitKeyboard.docks(SplitKeyboard.Window.OTHER, lettersLast = letters))
        }
    }

    @Test
    fun theExpandedCandidatesGoAsTheBoardTheyAreOpenedFrom() {
        assertFalse(SplitKeyboard.docks(SplitKeyboard.Window.EXPANDED_CANDIDATES, lettersLast = true))
        assertTrue(SplitKeyboard.docks(SplitKeyboard.Window.EXPANDED_CANDIDATES, lettersLast = false))
    }

    @Test
    fun aBoardGoesToASideOnlyWithRoomBesideIt() {
        val dock = SplitKeyboard.Dock.RIGHT
        // a phone upright split all the same: 320 of 360 dp at a side would leave a strip of nothing
        assertEquals(null, SplitKeyboard.side(dock, splitting = true, lettersShown = false, widthDp = 360))
        assertEquals(null, SplitKeyboard.side(dock, splitting = true, lettersShown = false, widthDp = 439))
        assertEquals(dock, SplitKeyboard.side(dock, splitting = true, lettersShown = false, widthDp = 440))
    }

    @Test
    fun aBoardAtASideIsOneThumbsWide() {
        // a phone on its side: about a phone's width upright
        assertEquals(357, SplitKeyboard.dockWidth(851))
        // a small one, and a tablet: within the bounds
        assertEquals(SplitKeyboard.DOCK_MIN_DP, SplitKeyboard.dockWidth(640))
        assertEquals(SplitKeyboard.DOCK_MAX_DP, SplitKeyboard.dockWidth(1280))
        // never wider than the keyboard
        assertEquals(300, SplitKeyboard.dockWidth(300))
    }

    @Test
    fun oneHandedEveryBoardGoesToItsSideTillTheLettersSplit() {
        val right = SplitKeyboard.Dock.RIGHT
        val left = SplitKeyboard.Dock.LEFT
        val full = SplitKeyboard.Dock.FULL
        // a phone upright: four fifths of it, the letters too
        assertEquals(SplitKeyboard.Placement(right, 288), SplitKeyboard.placement(right, left, splitting = false, lettersShown = true, widthDp = 360))
        assertEquals(SplitKeyboard.Placement(left, 288), SplitKeyboard.placement(left, right, splitting = false, lettersShown = false, widthDp = 360))
        // a tablet upright: a phone's width
        assertEquals(SplitKeyboard.Placement(left, SplitKeyboard.DOCK_MAX_DP), SplitKeyboard.placement(left, full, false, true, 800))
        assertNull(SplitKeyboard.placement(full, right, splitting = false, lettersShown = false, widthDp = 360))
        // 20% of 250 dp is 50, under a side button: the whole width; 280 leaves 56, just enough
        assertNull(SplitKeyboard.placement(right, full, splitting = false, lettersShown = true, widthDp = 250))
        assertEquals(SplitKeyboard.Placement(right, 224), SplitKeyboard.placement(right, full, false, true, 280))
        // split, the letters take the width, the other boards go as the split's setting says
        assertNull(SplitKeyboard.placement(left, right, splitting = true, lettersShown = true, widthDp = 800))
        assertEquals(SplitKeyboard.Placement(right, SplitKeyboard.dockWidth(800)), SplitKeyboard.placement(left, right, true, false, 800))
        assertNull(SplitKeyboard.placement(left, full, splitting = true, lettersShown = false, widthDp = 800))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aHalfOfOtherThanFiveKeysIsAMistake() {
        Sizes(800).row(List(6) { it }, List(5) { it }, -1)
    }
}
