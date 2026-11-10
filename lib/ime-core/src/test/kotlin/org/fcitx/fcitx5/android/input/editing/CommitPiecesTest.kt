/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** [CommitPieces]: a long paste cut for the binder, never inside a surrogate pair. */
class CommitPiecesTest {

    @Test
    fun aTextThatFitsIsOnePieceItself() {
        val text = "abc"
        val pieces = CommitPieces.split(text, max = 3)
        assertEquals(1, pieces.size)
        assertSame(text, pieces[0])
    }

    @Test
    fun anEmptyTextIsOneEmptyPiece() {
        assertEquals(listOf(""), CommitPieces.split("", max = 3))
    }

    @Test
    fun aMultipleOfTheSizeLeavesNoEmptyPieceAtTheEnd() {
        assertEquals(listOf("abc", "def"), CommitPieces.split("abcdef", max = 3))
    }

    @Test
    fun theLastPieceTakesWhatIsLeft() {
        assertEquals(listOf("abc", "def", "g"), CommitPieces.split("abcdefg", max = 3))
    }

    @Test
    fun aCutThatWouldSplitASurrogatePairComesBeforeIt() {
        // 😀 is a pair at 2..3: a cut at 3 would leave its high half alone
        assertEquals(listOf("ab", "😀c", "d"), CommitPieces.split("ab😀cd", max = 3))
    }

    @Test
    fun aPairRightAtTheStartOfAPieceIsNotMoved() {
        assertEquals(listOf("abc", "😀d"), CommitPieces.split("abc😀d", max = 3))
    }

    @Test
    fun loneSurrogatesAreCutAsAnyCharacter() {
        // malformed already: nothing to keep together, and no piece may come out empty
        val text = "ab\uD83Dcd\uDE00e"
        val pieces = CommitPieces.split(text, max = 3)
        assertEquals(listOf("ab\uD83D", "cd\uDE00", "e"), pieces)
    }

    @Test
    fun piecesOfTwoStillMoveForwardOverPairs() {
        assertEquals(listOf("😀", "😀", "a"), CommitPieces.split("😀😀a", max = 2))
        assertEquals(listOf("a", "😀", "b"), CommitPieces.split("a😀b", max = 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aPieceTooSmallForAPairIsRefused() {
        CommitPieces.split("abc", max = 1)
    }

    @Test
    fun randomTextsComeBackWholeInPiecesThatFitAndKeepPairs() {
        val random = Random(20261110)
        val units = listOf("a", "中", "😀", "\uD83D", "\uDE00", "\n")
        repeat(500) {
            val text = buildString { repeat(random.nextInt(0, 60)) { append(units.random(random)) } }
            val max = random.nextInt(2, 9)
            val pieces = CommitPieces.split(text, max)
            assertEquals(text, pieces.joinToString(""))
            assertTrue(pieces.all { it.length <= max })
            assertTrue("no empty piece but an empty text's", pieces.size == 1 || pieces.none { it.isEmpty() })
            var at = 0
            for (piece in pieces.dropLast(1)) {
                at += piece.length
                val pairCut = Character.isHighSurrogate(text[at - 1]) && Character.isLowSurrogate(text[at])
                assertTrue("cut inside a pair at $at of ${text.length}", !pairCut)
            }
        }
    }
}
