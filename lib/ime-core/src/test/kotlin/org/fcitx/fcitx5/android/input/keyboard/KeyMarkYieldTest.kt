/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.keyboard.KeyMarkYield.Ink
import org.fcitx.fcitx5.android.input.keyboard.KeyMarkYield.Yield
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyMarkYieldTest {

    private val face = Ink(0f, 0f, 40f, 50f)

    private fun of(label: Ink, mark: Ink, shrinks: Boolean = false) = KeyMarkYield.of(label, mark, face, gap = 1f, markShrinks = shrinks)

    private fun assertYield(expected: Yield, actual: Yield) {
        assertEquals("dx", expected.dx, actual.dx, 1e-4f)
        assertEquals("dy", expected.dy, actual.dy, 1e-4f)
        assertEquals("label", expected.labelScale, actual.labelScale, 1e-4f)
        assertEquals("mark", expected.markScale, actual.markScale, 1e-4f)
    }

    @Test
    fun apartNothingMoves() {
        // left of it, below it, or just touching with the gap: as it was (大千's symbols at the standard height)
        assertYield(Yield.None, of(Ink(10f, 15f, 28f, 35f), Ink(29f, 2f, 38f, 11f)))
        assertYield(Yield.None, of(Ink(10f, 12f, 35f, 35f), Ink(29f, 2f, 38f, 11f)))
        assertYield(Yield.None, of(Ink(10f, 15f, 30f, 35f), Ink(31f, 2f, 38f, 20f)))
    }

    @Test
    fun leftAsFarAsTheyMet() {
        // 4 over the mark's left, the gap kept, 4 under it: 4 left, the face's edge 9 away
        assertYield(Yield(-4f, 0f, 1f, 1f), of(Ink(10f, 8f, 32f, 30f), Ink(29f, 2f, 38f, 11f)))
        // 2 over, 4 under: 2 left
        assertYield(Yield(-2f, 0f, 1f, 1f), of(Ink(10f, 8f, 30f, 30f), Ink(29f, 2f, 38f, 11f)))
    }

    @Test
    fun orDownIfThatIsLess() {
        // under a text mark's bottom by 2, over its left by 20: down 2
        assertYield(Yield(0f, 2f, 1f, 1f), of(Ink(8f, 10f, 35f, 30f), Ink(16f, 2f, 38f, 11f)))
        // as far either way: left
        assertYield(Yield(-4f, 0f, 1f, 1f), of(Ink(10f, 8f, 32f, 30f), Ink(29f, 2f, 38f, 11f)))
        // left the shorter but no room for it: down
        assertYield(Yield(0f, 5f, 1f, 1f), of(Ink(1f, 8f, 32f, 30f), Ink(29f, 2f, 38f, 12f)))
    }

    @Test
    fun neitherAloneThenOneWayTheLessShrunk() {
        // 2 to the face's edge (+1 gap) of the 6 over, 5 of the 6 under
        val y = of(Ink(3f, 7f, 34f, 44f), Ink(29f, 2f, 38f, 12f))
        // left 2: 32 - 28 = 4 of 31 still over, 13 - 7 = 6 of 37 under, a 4/31 shrink;
        // down 5: 34 - 28 = 6 of 31, 13 - 12 = 1 of 37, a 1/37 shrink, the less: down, only
        assertEquals(0f, y.dx, 0f)
        assertEquals(5f, y.dy, 0f)
        assertEquals(1f - 1f / 37f, y.labelScale, 1e-4f)
        // the other way round, a tall label's top far under, little room below: left
        val l = of(Ink(3f, 4f, 31f, 48f), Ink(29f, 2f, 38f, 14f))
        // left 2: 29 - 28 = 1 of 28; down 1: 31 - 28 = 3 of 28, 15 - 5 = 10 of 44
        assertEquals(-2f, l.dx, 0f)
        assertEquals(0f, l.dy, 0f)
        assertEquals(1f - 1f / 28f, l.labelScale, 1e-4f)
    }

    @Test
    fun thenTheTextMarkSmallerThenTheLabel() {
        // no room either way: the label fills the face; down 4 of the 10 needed
        val label = Ink(1f, 4f, 39f, 45f)
        val mark = Ink(30f, 1f, 39f, 13f)
        val icon = of(label, mark)
        assertEquals(0f, icon.dx, 0f)
        assertEquals(1f, icon.markScale, 0f)
        // left: 39 - 29 = 10 of 38; down: 13 + 1 - 8 = 6 of 41, the smaller
        assertEquals(1f - 6f / 41f, icon.labelScale, 1e-4f)
        // a text mark a quarter smaller first: its bottom at 10, the label's top at 8 still under it
        val text = of(label, mark, shrinks = true)
        assertEquals(1f - KeyMarkYield.MAX_MARK_SHRINK, text.markScale, 1e-4f)
        assertEquals(4f, text.dy, 0f)
        // left: 39 - (32.75 - 1) = 7.25 of 38; down: 10 + 1 - 8 = 3 of 41, the smaller
        assertEquals(1f - 3f / 41f, text.labelScale, 1e-4f)
    }

    @Test
    fun aTextMarkSmallerOnlyAsNeeded() {
        // down 2 of the 3 needed: shrunk to clear by its bottom, 11 tall to 10, no more
        val y = of(Ink(1f, 10f, 39f, 47f), Ink(30f, 1f, 39f, 12f), shrinks = true)
        assertEquals(2f, y.dy, 0f)
        assertEquals(10f + 2f - 1f, 1f + 11f * y.markScale, 1e-4f)
        assertEquals(1f, y.labelScale, 0f)
    }

    @Test
    fun theLabelAFifthSmallerAtMost() {
        // a mark over its whole top: nothing clears it
        val y = of(Ink(1f, 2f, 39f, 49f), Ink(2f, 1f, 39f, 30f))
        assertEquals(1f - KeyMarkYield.MAX_LABEL_SHRINK, y.labelScale, 1e-4f)
    }

    // the keys of a letter keyboard, in a 40 × 50 face: room clear of its edge by 1
    private val room = Ink(1f, 1f, 39f, 49f)

    private fun key(letter: Ink, symbol: Ink?, dp: Float = 10f) = KeyMarkYield.LetterKey(letter, letter.bottom, symbol, room, dp)

    @Test
    fun aLetterApartFromItsSymbolNothingMoves() {
        val r = KeyMarkYield.letters(listOf(key(Ink(10f, 15f, 28f, 35f), Ink(29f, 2f, 38f, 11f)), key(Ink(10f, 15f, 28f, 35f), null)), gap = 1f)
        assertEquals(1f, r.scale, 0f)
        assertEquals(listOf(KeyMarkYield.Corner.None, KeyMarkYield.Corner.None), r.corners)
    }

    @Test
    fun theSymbolMovesTheShorterWayTheLetterStays() {
        // 3 over its left, right 3 of the 4 there; 6 under its bottom: right
        val right = KeyMarkYield.letters(listOf(key(Ink(10f, 15f, 31f, 35f), Ink(29f, 8f, 35f, 20f))), gap = 1f)
        assertEquals(1f, right.scale, 0f)
        assertEquals(KeyMarkYield.Corner(3f, 0f, 1f), right.corners[0])
        // 6 over with 4 to the right; 1 under with 5 up: up
        val up = KeyMarkYield.letters(listOf(key(Ink(10f, 12f, 34f, 35f), Ink(29f, 6f, 35f, 12f))), gap = 1f)
        assertEquals(KeyMarkYield.Corner(0f, -1f, 1f), up.corners[0])
    }

    @Test
    fun neitherIntoTheCornerThenSmaller() {
        // 4 over with 2 to the right, 5 under with 3 up: into the corner (39, 1 top right), then
        // smaller about that by its bottom: 11 tall to 11 - 1 - 1 = 9
        val r = KeyMarkYield.letters(listOf(key(Ink(10f, 11f, 34f, 35f), Ink(31f, 4f, 37f, 15f))), gap = 1f)
        assertEquals(1f, r.scale, 0f)
        val c = r.corners[0]
        assertEquals(2f, c.dx, 0f)
        assertEquals(-3f, c.dy, 0f)
        assertEquals(9f / 11f, c.scale, 1e-4f)
    }

    @Test
    fun aSymbolSmallerThanItsReadableLeastThenEveryLetterAsMuchSmaller() {
        // 10 dp, no smaller than 6: into the corner, 10 tall to 6, its bottom at 7, the letter's top at 5
        val crowded = key(Ink(10f, 5f, 38f, 35f), Ink(30f, 3f, 37f, 13f))
        val apart = key(Ink(10f, 15f, 20f, 35f), Ink(29f, 2f, 38f, 11f))
        val r = KeyMarkYield.letters(listOf(crowded, apart), gap = 1f)
        // the letter about (24, 35) by its top: 35 - (7 + 1) = 27 of 30
        assertEquals(27f / 30f, r.scale, 1e-4f)
        // its symbol then only as small as that leaves it: its top at 8, 8 - 1 - 1 = 6 of 10
        assertEquals(0.6f, r.corners[0].scale, 1e-4f)
        assertEquals(KeyMarkYield.Corner.None, r.corners[1])
        // a symbol of 6 dp or less is not shrunk: all the letters are, a quarter at most
        val small = KeyMarkYield.letters(listOf(key(Ink(10f, 2f, 38f, 35f), Ink(30f, 3f, 37f, 13f), dp = 6f)), gap = 1f)
        assertEquals(1f - KeyMarkYield.MAX_LETTER_SHRINK, small.scale, 1e-4f)
        assertEquals(1f, small.corners[0].scale, 0f)
    }

    // [ink] flipped about the 40-wide face's middle: a top right case as a top left one
    private fun flip(ink: Ink) = Ink(40f - ink.right, ink.top, 40f - ink.left, ink.bottom)

    @Test
    fun aTopLeftMarkIsClearedRightOrDownAsTheTopRightOneLeftOrDown() {
        // every top right case of the above, flipped: the same yield, its x the other way
        val cases = listOf(
            Ink(10f, 15f, 28f, 35f) to Ink(29f, 2f, 38f, 11f),
            Ink(10f, 8f, 32f, 30f) to Ink(29f, 2f, 38f, 11f),
            Ink(8f, 10f, 35f, 30f) to Ink(16f, 2f, 38f, 11f),
            Ink(1f, 8f, 32f, 30f) to Ink(29f, 2f, 38f, 12f),
            Ink(3f, 7f, 34f, 44f) to Ink(29f, 2f, 38f, 12f),
            Ink(1f, 4f, 39f, 45f) to Ink(30f, 1f, 39f, 13f),
        )
        for ((label, mark) in cases) for (shrinks in listOf(false, true)) {
            val right = of(label, mark, shrinks)
            val left = KeyMarkYield.ofTopLeft(flip(label), flip(mark), face, gap = 1f, markShrinks = shrinks)
            assertYield(right.copy(dx = -right.dx), left)
        }
        // 4 over the icon's right edge: 4 right
        assertYield(Yield(4f, 0f, 1f, 1f), KeyMarkYield.ofTopLeft(Ink(8f, 8f, 30f, 30f), Ink(2f, 2f, 11f, 11f), face, gap = 1f, markShrinks = false))
    }

    private fun both(letter: Ink, symbol: Ink?, shortcut: Ink?, shortcutDp: Float = 10f, icon: Boolean = true) =
        KeyMarkYield.LetterKey(letter, letter.bottom, symbol, room, 10f, shortcut, shortcutDp, icon)

    @Test
    fun aLettersShortcutMovesLeftOrUpTheLetterStays() {
        // the symbol's case flipped: 3 over the shortcut's right, left 3 of the 4 there
        val r = KeyMarkYield.letters(listOf(both(Ink(9f, 15f, 30f, 35f), null, Ink(5f, 8f, 11f, 20f))), gap = 1f)
        assertEquals(1f, r.scale, 0f)
        assertEquals(KeyMarkYield.Corner.None, r.corners[0])
        assertEquals(KeyMarkYield.Corner(-3f, 0f, 1f), r.leftCorners[0])
        // apart from both corners: nothing
        val apart = KeyMarkYield.letters(listOf(both(Ink(14f, 15f, 26f, 35f), Ink(29f, 2f, 38f, 11f), Ink(2f, 2f, 11f, 11f))), gap = 1f)
        assertEquals(listOf(KeyMarkYield.Corner.None), apart.leftCorners)
        assertEquals(listOf(KeyMarkYield.Corner.None), apart.corners)
    }

    @Test
    fun anIconNoSmallerThanItsReadableLeast() {
        // flipped from the symbol's: the letter's top at 5 under a 10 dp icon; an icon goes to 7 dp, a text to 6
        val letter = Ink(2f, 5f, 30f, 35f)
        val shortcut = Ink(3f, 3f, 10f, 13f)
        val icon = KeyMarkYield.letters(listOf(both(letter, null, shortcut)), gap = 1f)
        assertEquals(KeyMarkYield.MIN_ICON_DP / 10f, icon.leftCorners[0].scale, 1e-4f)
        // into the corner: (1, 1) top left, 7 tall, its bottom at 8, the letter about (16, 35) by its top: 35 - 9 = 26 of 30
        assertEquals(-2f, icon.leftCorners[0].dx, 0f)
        assertEquals(26f / 30f, icon.scale, 1e-4f)
        val text = KeyMarkYield.letters(listOf(both(letter, null, shortcut, icon = false)), gap = 1f)
        assertEquals(27f / 30f, text.scale, 1e-4f)
        // both corners: the letter as small as the more crowded needs, each mark then only as small as that leaves it
        val two = KeyMarkYield.letters(listOf(both(letter, Ink(30f, 3f, 37f, 13f), shortcut)), gap = 1f)
        assertEquals(26f / 30f, two.scale, 1e-4f)
        assertEquals(KeyMarkYield.MIN_ICON_DP / 10f, two.leftCorners[0].scale, 1e-4f)
    }

    @Test
    fun aFunctionKeysIconMovesRightOfItsMarksThenShrinks() {
        val top = Ink(2f, 2f, 10f, 10f)
        val bottom = Ink(2f, 40f, 10f, 48f)
        // apart (the gap kept, or below the top one): as it is
        assertYield(Yield.None, KeyMarkYield.ofIcon(Ink(11f, 15f, 30f, 35f), top, bottom, face, gap = 1f))
        assertYield(Yield.None, KeyMarkYield.ofIcon(Ink(5f, 12f, 25f, 38f), top, bottom, face, gap = 1f))
        // 3 over the top one: 3 right, never down (into the bottom one)
        assertYield(Yield(3f, 0f, 1f, 1f), KeyMarkYield.ofIcon(Ink(8f, 8f, 28f, 30f), top, null, face, gap = 1f))
        // over both, the bottom one wider: as far as that one needs
        assertYield(Yield(5f, 0f, 1f, 1f), KeyMarkYield.ofIcon(Ink(8f, 8f, 28f, 42f), top, Ink(2f, 40f, 12f, 48f), face, gap = 1f))
        // 9 needed, the face's edge 5 away: 5 right and 4 of its 20 shrunk about its right side
        assertYield(Yield(5f, 0f, 16f / 20f, 1f), KeyMarkYield.ofIcon(Ink(14f, 8f, 34f, 30f), Ink(2f, 2f, 22f, 10f), null, face, gap = 1f))
        // no more than a fifth smaller
        assertYield(Yield(0f, 0f, 1f - KeyMarkYield.MAX_LABEL_SHRINK, 1f), KeyMarkYield.ofIcon(Ink(14f, 8f, 39f, 30f), Ink(2f, 2f, 38f, 10f), null, face, gap = 1f))
    }
}
