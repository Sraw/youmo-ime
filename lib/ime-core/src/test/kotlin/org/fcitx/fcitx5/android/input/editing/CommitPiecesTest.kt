/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.BreakIterator
import kotlin.random.Random

/** [CommitPieces]: a long paste cut for the binder, never inside a character. */
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

    private class Cut(val why: String, val text: String, val max: Int, val pieces: List<String>)

    @Test
    fun aCutComesWhereACharacterEnds() = assertRows(
        listOf(
            Cut("a multiple of the size leaves no empty piece at the end", "abcdef", 3, listOf("abc", "def")),
            Cut("the last piece takes what is left", "abcdefg", 3, listOf("abc", "def", "g")),
            // 😀 is a pair at 2..3: a cut at 3 would leave its high half alone
            Cut("a cut that would split a surrogate pair comes before it", "ab😀cd", 3, listOf("ab", "😀c", "d")),
            Cut("a pair right at the start of a piece is not moved", "abc😀d", 3, listOf("abc", "😀d")),
            // malformed already: nothing to keep together, and no piece may come out empty
            Cut("lone surrogates are cut as any character", "ab\uD83Dcd\uDE00e", 3, listOf("ab\uD83D", "cd\uDE00", "e")),
            Cut("pieces of two still move forward over pairs", "😀😀a", 2, listOf("😀", "😀", "a")),
            Cut("pieces of two still move forward over a pair inside", "a😀b", 2, listOf("a", "😀", "b")),
            Cut("a cut never splits a CRLF", "ab\r\ncd", 3, listOf("ab", "\r\nc", "d")),
            Cut("a cut never splits a letter from its combining mark", "abe\u0301c", 3, listOf("ab", "e\u0301c")),
        ),
        why = { it.why },
    ) { assertEquals(it.pieces, CommitPieces.split(it.text, it.max)) }

    @Test
    fun aCharacterLongerThanAPieceIsCutBetweenItsCodePoints() {
        val marks = "e" + "\u0301".repeat(5)
        assertEquals(listOf("e\u0301\u0301", "\u0301\u0301\u0301"), CommitPieces.split(marks, max = 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aPieceTooSmallForAPairIsRefused() {
        CommitPieces.split("abc", max = 1)
    }

    @Test
    fun randomTextsComeBackWholeInPiecesThatFitAndKeepCharacters() {
        val random = Random(20261110)
        // CRLF, a combining mark and a ZWJ sequence: characters of more than one code point
        val units = listOf("a", "中", "😀", "\uD83D", "\uDE00", "\n", "\r\n", "e\u0301", "👨\u200D👩")
        repeat(500) {
            val text = buildString { repeat(random.nextInt(0, 60)) { append(units.random(random)) } }
            val max = random.nextInt(2, 9)
            val pieces = CommitPieces.split(text, max)
            assertEquals(text, pieces.joinToString(""))
            assertTrue(pieces.all { it.length <= max })
            assertTrue("no empty piece but an empty text's", pieces.size == 1 || pieces.none { it.isEmpty() })
            val characters = BreakIterator.getCharacterInstance().apply { setText(text) }
            var at = 0
            for (piece in pieces.dropLast(1)) {
                val from = at
                at += piece.length
                val pairCut = Character.isHighSurrogate(text[at - 1]) && Character.isLowSurrogate(text[at])
                assertTrue("cut inside a pair at $at of ${text.length}", !pairCut)
                // inside a character only where that character began before the piece did: too long to fit
                assertTrue("cut inside a character that fits at $at", characters.isBoundary(at) || characters.preceding(at) <= from)
            }
        }
    }
}
