/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.core.CoreLog
import org.fcitx.fcitx5.android.core.FormattedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditingSessionTest {

    private fun session(text: String = "", cursor: Int = text.length): Pair<EditingSession, FakeEditor> {
        val editor = FakeEditor(text, cursor)
        val session = EditingSession(editor)
        session.selection.resetTo(cursor)
        return session to editor
    }

    private fun formatted(text: String) = FormattedText(arrayOf(text), intArrayOf(0), -1)

    private fun EditingSession.compose(text: String, start: Int) {
        setComposingText(formatted(text))
        composing.update(start, start + text.length)
    }

    @Test
    fun committingIntoAnEmptyBufferInsertsTheText() {
        val (s, e) = session()
        s.commitText("abc")
        assertEquals("abc", e.text)
        assertTrue("the cursor is predicted after the text", s.selection.latest.rangeEquals(3))
    }

    @Test
    fun committingPredictsTheCursorBeforeTheEditorConfirms() {
        val (s, _) = session("hello", cursor = 5)
        s.commitText("!")
        assertTrue(s.selection.latest.rangeEquals(6))
        assertTrue("current still reflects the editor", s.selection.current.rangeEquals(5))
    }

    @Test
    fun committingAnEmptyStringLeavesTheCursorWhereItWas() {
        val (s, _) = session("abc", cursor = 3)
        s.commitText("")
        assertTrue(s.selection.latest.rangeEquals(3))
    }

    @Test
    fun committingUsesTheNewestPredictionAsItsAnchor() {
        val (s, _) = session("", cursor = 0)
        s.selection.predict(10) // the IME already believes it moved
        s.commitText("ab")
        assertTrue("anchored on latest, not current", s.selection.latest.rangeEquals(12))
    }

    @Test
    fun anExplicitCursorPlacesTheCaretInsideTheCommittedText() {
        val (s, e) = session()
        s.commitText("()", cursor = 1)
        assertEquals("()", e.text)
        assertTrue("caret between the brackets", s.selection.latest.rangeEquals(1))
        assertEquals(1, e.selectionStart)
    }

    @Test
    fun aReplacementAroundTheCursorIsOneChange() {
        val (s, e) = session("to a@q", cursor = 6)
        s.replaceAround(3, 0, "a@qq.com")
        assertEquals("to a@qq.com", e.text)
        assertEquals(11, e.selectionStart)
        assertEquals("the deletion and the commit seen as one", 1, e.maxBatchDepth)
        val (s2, e2) = session("ab|cd".replace("|", ""), cursor = 2)
        s2.replaceAround(1, 1, "xyz", cursor = 1)
        assertEquals("axyzd", e2.text)
        assertEquals(2, e2.selectionStart)
    }

    @Test
    fun anExplicitCursorIsAppliedInOneBatch() {
        val (s, e) = session()
        s.commitText("()", cursor = 1)
        assertTrue("the commit and the move must not be seen separately", e.maxBatchDepth >= 1)
        assertEquals(
            listOf("beginBatchEdit()", "commitText((), 1)", "setSelection(1, 1)", "endBatchEdit()"),
            e.calls,
        )
    }

    @Test
    fun anExplicitCursorOfZeroPlacesTheCaretBeforeTheText() {
        val (s, _) = session("", cursor = 0)
        s.commitText("abc", cursor = 0)
        assertTrue(s.selection.latest.rangeEquals(0))
    }

    private val pauses = mutableListOf<Long>()

    private fun pieced(text: String = "", cursor: Int = text.length, end: Int = cursor): Pair<EditingSession, FakeEditor> {
        val editor = FakeEditor(text, cursor)
        editor.setSelection(cursor, end)
        editor.calls.clear()
        val session = EditingSession(editor, commitPiece = 4, pause = { pauses += it })
        session.selection.resetTo(cursor, end)
        return session to editor
    }

    @Test
    fun aLongTextGoesInPiecesInOneBatchAPauseBetween() {
        val (s, e) = pieced("<>", cursor = 1)
        s.commitText("abcdefghij")
        assertEquals("<abcdefghij>", e.text)
        assertEquals(
            listOf("beginBatchEdit()", "commitText(abcd, 1)", "commitText(efgh, 1)", "commitText(ij, 1)", "endBatchEdit()"),
            e.calls,
        )
        assertEquals(listOf(CommitPieces.GAP_MS, CommitPieces.GAP_MS), pauses)
        assertTrue("the cursor is predicted after the whole text", s.selection.latest.rangeEquals(11))
    }

    @Test
    fun theEditorReportingAPiecesEndIsExpected() {
        val (s, _) = pieced()
        s.commitText("abcdefghij")
        assertTrue("a report between pieces is one predicted", s.selection.consume(4))
        assertTrue(s.selection.latest.rangeEquals(10))
        assertTrue(s.selection.consume(10))
        assertTrue(s.selection.settled)
    }

    @Test
    fun aTextOfOnePieceGoesAsBefore() {
        val (s, e) = pieced()
        s.commitText("abcd")
        assertEquals(listOf("commitText(abcd, 1)"), e.calls)
        assertTrue(pauses.isEmpty())
        assertTrue(s.selection.latest.rangeEquals(4))
    }

    @Test
    fun theLongestTextOfOneCallGoesInOneAndOneMoreInTwo() {
        val e = FakeEditor()
        val s = EditingSession(e, pause = { pauses += it })
        s.selection.resetTo(0)
        s.commitText("a".repeat(CommitPieces.MAX))
        assertEquals(1, e.calls.count { it.startsWith("commitText(") })
        s.commitText("b".repeat(CommitPieces.MAX + 1))
        assertEquals(3, e.calls.count { it.startsWith("commitText(") })
        assertEquals(CommitPieces.MAX * 2 + 1, e.text.length)
        assertTrue(s.selection.latest.rangeEquals(CommitPieces.MAX * 2 + 1))
    }

    @Test
    fun aLongTextWithAnExplicitCursorLeavesItThereAfterThePieces() {
        val (s, e) = pieced("<>", cursor = 1)
        s.commitText("abcdefghij", cursor = 2)
        assertEquals("<abcdefghij>", e.text)
        assertEquals(listOf("setSelection(3, 3)", "endBatchEdit()"), e.calls.takeLast(2))
        assertEquals(3, e.selectionStart)
        assertTrue(s.selection.latest.rangeEquals(3))
    }

    @Test
    fun aLongTextReplacesTheCompositionWithItsFirstPiece() {
        val (s, e) = pieced("<>", cursor = 1)
        e.setComposingText("xy")
        s.compose("xy", start = 1)
        e.calls.clear()
        s.commitText("abcdefghij")
        assertEquals("<abcdefghij>", e.text)
        assertTrue(s.composing.isEmpty())
        assertTrue(s.selection.latest.rangeEquals(11))
    }

    @Test
    fun aLongTextReplacesTheSelectionWithItsFirstPiece() {
        val (s, e) = pieced("<xyz>", cursor = 1, end = 4)
        s.commitText("abcdefghij")
        assertEquals("<abcdefghij>", e.text)
        assertTrue("each piece's end counted from the selection's start", s.selection.consume(5))
        assertTrue(s.selection.latest.rangeEquals(11))
    }

    @Test
    fun aLongTextInsideReplaceAroundsBatch() {
        val (s, e) = pieced("<ab>", cursor = 3)
        s.replaceAround(2, 1, "abcdefghij")
        assertEquals("<abcdefghij", e.text)
        assertEquals("the delete and the pieces are one change", 2, e.maxBatchDepth)
        assertEquals("endBatchEdit()", e.calls.last())
        assertTrue(s.selection.latest.rangeEquals(11))
    }

    @Test
    fun aPieceNeverTakenStopsTheRestAndTheirPrediction() {
        val (s, e) = pieced("<>", cursor = 1)
        e.commitFails = { it == "efgh" }
        assertEquals("what went in", "abcd", s.commitText("abcdefghij"))
        assertEquals("no hole: what went in is the text's start", "<abcd>", e.text)
        assertEquals("the last piece not sent", 0, e.calls.count { it == "commitText(ij, 1)" })
        assertEquals("endBatchEdit()", e.calls.last())
        assertTrue("predicted no further than taken", s.selection.latest.rangeEquals(5))
        assertTrue(pauses.drop(1).sum() <= CommitPieces.RETRY_BUDGET_MS)
        assertTrue("sent again a few times first", e.calls.count { it == "commitText(efgh, 1)" } > 3)
    }

    @Test
    fun aFirstPieceNeverTakenFinishesTheEditorsComposition() {
        val (s, e) = pieced("<>", cursor = 1)
        e.setComposingText("xy")
        s.compose("xy", start = 1)
        e.commitFails = { true }
        assertEquals("", s.commitText("abcdefghij"))
        assertEquals("<xy>", e.text)
        assertEquals("not left composing in the editor", -1, e.composingStart)
        assertTrue(s.composing.isEmpty())
    }

    @Test
    fun aWholeTextIsReturnedAsWentIn() {
        val (s, _) = pieced("<>", cursor = 1)
        val text = "abcdefghij"
        assertEquals(text, s.commitText(text))
        assertEquals("one call: as sent", "ab", s.commitText("ab"))
    }

    @Test
    fun aPieceTakenAtTheSecondTryGoesOn() {
        val (s, e) = pieced("<>", cursor = 1)
        var failed = 0
        e.commitFails = { it == "efgh" && failed++ == 0 }
        s.commitText("abcdefghij")
        assertEquals("<abcdefghij>", e.text)
        assertEquals(listOf(CommitPieces.GAP_MS, CommitPieces.FIRST_RETRY_MS, CommitPieces.GAP_MS), pauses)
        assertTrue(s.selection.latest.rangeEquals(11))
    }

    @Test
    fun anEditorGoneMidwayIsNotWaitedFor() {
        val (s, e) = pieced("<>", cursor = 1)
        e.commitFails = { piece ->
            // the connection went with the piece: InputConnectionEditor gives false, isAvailable false
            (piece == "efgh").also { if (it) e.isAvailable = false }
        }
        s.commitText("abcdefghij")
        assertEquals("<abcd>", e.text)
        assertEquals("the gap before the piece, no pause after", listOf(CommitPieces.GAP_MS), pauses)
        assertTrue(s.selection.latest.rangeEquals(5))
    }

    @Test
    fun committingDifferentTextReplacesTheComposingRegion() {
        val (s, e) = session("", cursor = 0)
        s.compose("ni", start = 0)
        e.setComposingText("ni")
        s.commitText("你")
        assertEquals("the composing text was replaced, not appended to", "你", e.text)
        assertTrue("composing state is cleared", s.composing.isEmpty())
        assertEquals(FormattedText.Empty, s.composingText)
        assertTrue("anchored at the composing start, not the cursor", s.selection.latest.rangeEquals(1))
        assertEquals("commitText(你, 1)", e.calls.last())
    }

    /** Committing what is composing only finishes it: re-committing redraws and can drop spans. */
    @Test
    fun committingTheSameTextOnlyFinishesTheComposition() {
        val (s, e) = session("你好", cursor = 2)
        s.compose("你好", start = 0)
        s.selection.resetTo(2)

        s.commitText("你好")

        assertTrue(s.composing.isEmpty())
        assertEquals("the text was not re-committed", listOf("beginBatchEdit()", "finishComposingText()", "endBatchEdit()"), e.calls)
        assertEquals("你好", e.text)
    }

    @Test
    fun finishingAnIdenticalCompositionStillMovesTheCursorWhenItIsElsewhere() {
        val (s, e) = session("你好", cursor = 0)
        s.compose("你好", start = 0)
        s.selection.resetTo(0) // editor reports the caret at the start

        s.commitText("你好")

        assertTrue("caret moved to the end of the composition", s.selection.latest.rangeEquals(2))
        assertTrue(e.calls.contains("setSelection(2, 2)"))
        assertTrue(e.calls.contains("finishComposingText()"))
    }

    /** A pending move is where the caret is going: compared with the latest prediction, not the confirmed cursor. */
    @Test
    fun finishingAnIdenticalCompositionMovesTheCursorOnFromAPendingMove() {
        val (s, e) = session("你好", cursor = 2)
        s.compose("你好", start = 0)
        s.selection.resetTo(2)
        // a preedit cursor move, sent but not reported yet
        s.selection.predict(0)
        e.setSelection(0, 0)

        s.commitText("你好")

        assertTrue("moved after the composition", e.calls.contains("setSelection(2, 2)"))
        assertEquals(2, e.selectionStart)
        assertTrue("and predicted there", s.selection.latest.rangeEquals(2))
    }

    @Test
    fun finishingAnIdenticalCompositionHonoursAnExplicitCursor() {
        val (s, _) = session("abcd", cursor = 4)
        s.compose("abcd", start = 0)
        s.selection.resetTo(4)
        s.commitText("abcd", cursor = 2)
        assertTrue("caret placed inside the finished composition", s.selection.latest.rangeEquals(2))
    }

    @Test
    fun committingClearsComposingEvenWhenTheTextDiffers() {
        val (s, _) = session("ni", cursor = 2)
        s.compose("ni", start = 0)
        s.commitText("你好世界")
        assertTrue(s.composing.isEmpty())
        assertTrue(s.selection.latest.rangeEquals(4))
    }

    @Test
    fun deletingASelectionRemovesItAndCollapsesTheCursor() {
        val (s, e) = session("abcdef", cursor = 0)
        s.selection.resetTo(2, 4)
        e.reportSelection(2, 4)

        s.deleteSelection()

        assertEquals("abef", e.text)
        assertTrue(s.selection.latest.rangeEquals(2))
    }

    @Test
    fun deletingWithNoSelectionDoesNothing() {
        val (s, e) = session("abc", cursor = 3)
        s.deleteSelection()
        assertEquals("abc", e.text)
        assertTrue("the editor was not touched", e.calls.isEmpty())
    }

    @Test
    fun deletingASelectionWhileComposingDeletesTheSelection() {
        val (s, e) = session("abcnihao", cursor = 8)
        e.setComposingRegion(3, 8)
        s.compose("nihao", start = 3)
        s.applySelectionOffset(-8, -6) // select "ab"
        s.deleteSelection()
        assertEquals("cnihao", e.text)
        assertTrue(s.composing.isEmpty())
        assertEquals("the editor's composition is finished too", -1, e.composingStart)
        assertEquals("in one batch", 1, e.maxBatchDepth)
        assertTrue(s.selection.latest.rangeEquals(e.selectionStart, e.selectionEnd))
    }

    @Test
    fun extendingTheSelectionToTheRight() {
        val (s, e) = session("abcdef", cursor = 2)
        s.selection.resetTo(2)
        s.applySelectionOffset(0, 3)
        assertTrue(s.selection.latest.rangeEquals(2, 5))
        assertEquals("setSelection(2, 5)", e.calls.last())
    }

    @Test
    fun aSelectionOffsetMovesItsEnds() = assertRows(
        "extending the selection to the left" to {
            val (s, _) = session("abcdef", cursor = 4)
            s.selection.resetTo(4)
            s.applySelectionOffset(-2, 0)
            assertTrue(s.selection.latest.rangeEquals(2, 4))
        },
        "the start is clamped at the beginning of the buffer" to {
            val (s, _) = session("abc", cursor = 1)
            s.selection.resetTo(1)
            s.applySelectionOffset(-5, 0)
            assertTrue("clamped to 0 rather than going negative", s.selection.latest.rangeEquals(0, 1))
        },
        "moving both ends shifts the whole selection" to {
            val (s, _) = session("abcdef", cursor = 0)
            s.selection.resetTo(1, 3)
            s.applySelectionOffset(1, 1)
            assertTrue(s.selection.latest.rangeEquals(2, 4))
        },
    )

    @Test
    fun anInvertingOffsetIsIgnored() {
        val (s, e) = session("abcdef", cursor = 0)
        s.selection.resetTo(2, 4)
        s.applySelectionOffset(3, -3)
        assertTrue("the selection is unchanged", s.selection.latest.rangeEquals(2, 4))
        assertTrue("the editor was not touched", e.calls.isEmpty())
    }

    /** A zero offset re-asserts the range to the editor rather than being skipped. */
    @Test
    fun offsettingByZeroStillSetsTheSelection() {
        val (s, e) = session("abc", cursor = 1)
        s.selection.resetTo(1, 2)
        s.applySelectionOffset(0, 0)
        assertEquals(listOf("setSelection(1, 2)"), e.calls)
    }

    @Test
    fun selectSetsTheRangeAsGiven() = assertRows(
        "as given" to {
            val (s, e) = session("abcdef", cursor = 4)
            s.select(1, 4)
            assertTrue(s.selection.latest.rangeEquals(1, 4))
            assertEquals("setSelection(1, 4)", e.calls.last())
            assertEquals(1, e.selectionStart)
            assertEquals(4, e.selectionEnd)
        },
        "the start held at the buffer's" to {
            val (s, e) = session("abc", cursor = 1)
            s.select(-2, 1)
            assertEquals("setSelection(0, 1)", e.calls.last())
        },
        "an end before its start is a cursor at the start" to {
            val (s, e) = session("abc", cursor = 1)
            s.select(2, 1)
            assertEquals("setSelection(2, 2)", e.calls.last())
        },
        "no editor, nothing predicted" to {
            val (s, e) = session("abc", cursor = 1)
            e.isAvailable = false
            s.select(0, 1)
            assertTrue(s.selection.latest.rangeEquals(1, 1))
            assertTrue(e.calls.isEmpty())
        },
    )

    // the other way round where the free end is the left one, as the editor extends its selection's end
    @Test
    fun selectFromSetsTheAnchorFirst() = assertRows(
        "the free end left of the anchor: set and predicted reversed" to {
            val (s, e) = session("abcdef", cursor = 4)
            s.selectFrom(4, 1)
            assertTrue(s.selection.latest.rangeEquals(4, 1))
            assertEquals("setSelection(4, 1)", e.calls.last())
        },
        "the report of it is the prediction, not a move of the editor's own" to {
            val (s, _) = session("abcdef", cursor = 4)
            s.selectFrom(4, 1)
            assertEquals(EditingSession.CursorUpdate.None, s.onCursorUpdate(4, 1, -1, -1))
        },
        "no editor, nothing predicted" to {
            val (s, e) = session("abc", cursor = 1)
            e.isAvailable = false
            s.selectFrom(2, 0)
            assertTrue(s.selection.latest.rangeEquals(1, 1))
            assertTrue(e.calls.isEmpty())
        },
    )

    @Test
    fun cancellingASelectionCollapsesItToTheEnd() {
        val (s, e) = session("abcdef", cursor = 0)
        s.selection.resetTo(1, 4)
        s.cancelSelection()
        assertTrue(s.selection.latest.rangeEquals(4))
        assertEquals("setSelection(4, 4)", e.calls.last())
    }

    @Test
    fun cancellingWithNoSelectionDoesNothing() {
        val (s, e) = session("abc", cursor = 2)
        s.selection.resetTo(2)
        s.cancelSelection()
        assertTrue(e.calls.isEmpty())
    }

    @Test
    fun deletingAroundTheCursorPredictsWhereItGoes() = assertRows(
        "deleting before the cursor moves the predicted cursor back" to {
            val (s, e) = session("abcdef", cursor = 4)
            s.selection.resetTo(4)
            e.reportSelection(4)

            s.deleteSurrounding(before = 2, after = 0, inCodePoints = false)

            assertEquals("abef", e.text)
            assertTrue(s.selection.latest.rangeEquals(2))
        },
        "deleting after the cursor leaves the prediction alone" to {
            val (s, e) = session("abcdef", cursor = 2)
            s.selection.resetTo(2)
            e.reportSelection(2)

            s.deleteSurrounding(before = 0, after = 2, inCodePoints = false)

            assertEquals("abef", e.text)
            assertTrue(s.selection.latest.rangeEquals(2))
        },
    )

    @Test
    fun theDeletionUnitIsForwardedToTheEditor() {
        val (s, e) = session("abc", cursor = 3)
        s.deleteSurrounding(before = 1, after = 0, inCodePoints = true)
        s.deleteSurrounding(before = 1, after = 0, inCodePoints = false)
        assertEquals(
            listOf(
                "deleteSurroundingText(1, 0, inCodePoints=true)",
                "deleteSurroundingText(1, 0, inCodePoints=false)",
            ),
            e.calls.filter { it.startsWith("delete") },
        )
    }

    /** Deleting one emoji in code points moves the cursor back two units. */
    @Test
    fun deletingByCodePointsPredictsTheUnitsActuallyRemoved() {
        val wave = String(Character.toChars(0x1F44B)) // two UTF-16 units
        val (s, e) = session("a$wave", cursor = 3)
        e.reportSelection(3)

        s.deleteSurrounding(before = 1, after = 0, inCodePoints = true)

        assertEquals("the editor removed the whole emoji", "a", e.text)
        assertTrue("the prediction matches where the editor put the cursor", s.selection.latest.rangeEquals(e.selectionStart))
        assertTrue("and the editor's report is recognised as expected", s.selection.consume(1))
    }

    @Test
    fun codePointDeletionsAcrossMixedTextAreMeasuredPerCodePoint() {
        val wave = String(Character.toChars(0x1F44B))
        val (s, e) = session("x${wave}b$wave", cursor = 6)

        s.deleteSurrounding(before = 3, after = 0, inCodePoints = true)

        assertEquals("x", e.text)
        assertTrue("five units for two emoji and a letter", s.selection.latest.rangeEquals(1))
    }

    @Test
    fun deletingMoreCodePointsThanExistStopsAtTheStart() {
        val (s, e) = session("ab", cursor = 2)
        s.deleteSurrounding(before = 5, after = 0, inCodePoints = true)
        assertEquals("", e.text)
        assertTrue(s.selection.latest.rangeEquals(0))
    }

    /** An editor that hides its text: one unit per code point is guessed. */
    @Test
    fun anEditorHidingItsTextFallsBackToOneUnitPerCodePoint() {
        val wave = String(Character.toChars(0x1F44B))
        val (s, e) = session("a$wave", cursor = 3)
        e.revealLimit = null
        s.deleteSurrounding(before = 1, after = 0, inCodePoints = true)
        assertEquals("the editor still removed the whole emoji", "a", e.text)
        assertTrue("but with nothing to measure, one unit was guessed", s.selection.latest.rangeEquals(2))
    }

    /** Less text than the cursor allows means the editor held some back: counting it would under-predict. */
    @Test
    fun anEditorRevealingTooLittleAlsoFallsBack() {
        val (s, e) = session("abcdef", cursor = 4)
        e.revealLimit = 1
        s.deleteSurrounding(before = 2, after = 0, inCodePoints = true)
        assertTrue(s.selection.latest.rangeEquals(2))
    }

    @Test
    fun deletingByUnitsDoesNotNeedToAskTheEditor() {
        val (s, e) = session("abcdef", cursor = 4)
        s.deleteSurrounding(before = 2, after = 0, inCodePoints = false)
        assertFalse(e.calls.any { it.startsWith("textBeforeCursor") })
    }

    @Test
    fun anUnpairedSurrogateBeforeTheCursorPredictsNoMove() {
        val low = String(Character.toChars(0x1F44B))[1]
        val (s, e) = session("a$low", cursor = 2)
        s.deleteSurrounding(before = 1, after = 0, inCodePoints = true)
        assertEquals("the editor deleted nothing", "a$low", e.text)
        assertTrue(s.selection.latest.rangeEquals(2))
    }

    /** Composing, the editor deletes before the composing region, so code points are measured there. */
    @Test
    fun deletingByCodePointsMeasuresFromTheCompositionNotTheCursor() {
        val wave = String(Character.toChars(0x1F44B))
        // "a👋" then the composition "bc" with the cursor after it
        val (s, e) = session("a$wave", cursor = 3)
        e.setComposingText("bc")
        s.compose("bc", start = 3)
        s.selection.resetTo(5)

        s.deleteSurrounding(before = 1, after = 0, inCodePoints = true)

        assertEquals("the emoji went, the composition stayed", "abc", e.text)
        assertTrue("two units, not the one a 'c' before the cursor would suggest", s.selection.latest.rangeEquals(3))
        assertTrue(s.selection.consume(e.selectionStart, e.selectionEnd))
    }

    @Test
    fun deletingBeforeACompositionMovesIt() {
        val (s, e) = session("abcd", cursor = 4)
        e.setComposingText("ni")
        s.compose("ni", start = 4)
        s.selection.resetTo(6)

        s.deleteSurrounding(before = 2, after = 0, inCodePoints = false)

        assertEquals("abni", e.text)
        assertTrue(s.composing.rangeEquals(2, 4))
        assertTrue(s.composing.rangeEquals(e.composingStart, e.composingEnd))
    }

    @Test
    fun deletingAroundASelectionShiftsIt() {
        val (s, e) = session("abcdef", cursor = 0)
        s.selection.resetTo(2, 4)
        e.reportSelection(2, 4)

        s.deleteSurrounding(before = 1, after = 1, inCodePoints = false)

        assertEquals("acd" + "f", e.text)
        assertTrue(s.selection.latest.rangeEquals(1, 3))
        assertTrue(s.selection.consume(e.selectionStart, e.selectionEnd))
    }

    private fun EditingSession.type(text: String) {
        for (c in text) if (!pairs.type(c.toString())) commitText(c.toString())
    }

    /** After the cursor, the closing one put in may be what went: the pairs are forgotten. */
    @Test
    fun aDeletionAfterTheCursorForgetsThePairs() {
        val (s, e) = session(")", cursor = 0)
        s.type("(")
        s.deleteSurrounding(before = 0, after = 1, inCodePoints = true)
        s.type(")")
        assertEquals("())", e.text)
        assertEquals(2, e.selectionStart)
        val (t, f) = session(")", cursor = 0)
        t.type("(a")
        t.deleteSurrounding(before = 1, after = 1, inCodePoints = true)
        t.type(")")
        assertEquals("())", f.text)
        assertEquals(2, f.selectionStart)
    }

    /** Composing, the editor deletes before the composition, so the opening one can go: pairs forgotten. */
    @Test
    fun aDeletionWhileComposingForgetsThePairs() {
        val (s, e) = session()
        s.type("(")
        s.updateComposingText(formatted("ni"))
        s.deleteSurrounding(before = 1, after = 0, inCodePoints = true)
        assertEquals("ni)", e.text)
        s.commitText("你")
        s.type(")")
        assertEquals("你))", e.text)
        assertEquals(2, e.selectionStart)
    }

    private val optedIn = EditorTraits(acceptsDeleteSurrounding = true)

    @Test
    fun anOrdinaryEditorGetsAKeyEvent() {
        val (s, e) = session("abc", cursor = 3)
        assertFalse(s.backspace(EditorTraits()))
        assertTrue("the editor was not edited directly", e.calls.none { it.startsWith("delete") || it.startsWith("commit") })
        assertTrue("the key event is still predicted to delete one", s.selection.latest.rangeEquals(2))
    }

    /** A key event's prediction stays at one unit: asking the editor would cost a round trip per auto-repeat. */
    @Test
    fun aKeyEventBackspaceDoesNotAskTheEditorForText() {
        val wave = String(Character.toChars(0x1F44B))
        val (s, e) = session("a$wave", cursor = 3)
        assertFalse(s.backspace(EditorTraits()))
        assertTrue(s.selection.latest.rangeEquals(2))
        assertTrue(e.calls.isEmpty())
    }

    @Test
    fun anOptedInEditorHasOneCodePointDeleted() {
        val wave = String(Character.toChars(0x1F44B))
        val (s, e) = session("a$wave", cursor = 3)
        assertTrue(s.backspace(optedIn))
        assertEquals("a", e.text)
        assertTrue("exactly where the editor will report", s.selection.latest.rangeEquals(1))
        // a "\r\n" or a flag: one code point too, the "\n", half the flag
        val (s2, e2) = session("a\r\n", cursor = 3)
        assertTrue(s2.backspace(optedIn))
        assertEquals("a\r", e2.text)
        val flag = String(Character.toChars(0x1F1EF)) + String(Character.toChars(0x1F1F5))
        val (s3, e3) = session("a$flag", cursor = 5)
        assertTrue(s3.backspace(optedIn))
        assertEquals("a" + String(Character.toChars(0x1F1EF)), e3.text)
    }

    @Test
    fun anOptedInEditorHasItsSelectionDeleted() {
        val (s, e) = session("abcdef", cursor = 0)
        s.selection.resetTo(1, 4)
        e.reportSelection(1, 4)
        assertTrue(s.backspace(optedIn))
        assertEquals("aef", e.text)
        assertTrue(s.selection.latest.rangeEquals(1))
    }

    @Test
    fun atTheStartEvenAnOptedInEditorGetsAKeyEvent() {
        val (s, e) = session("abc", cursor = 0)
        assertFalse(s.backspace(optedIn))
        assertTrue("no move predicted", s.selection.latest.rangeEquals(0))
        assertTrue(e.calls.isEmpty())
    }

    @Test
    fun anOptedInEditorHidingItsTextFallsBackToOneUnit() {
        val wave = String(Character.toChars(0x1F44B))
        val (s, e) = session("a$wave", cursor = 3)
        e.revealLimit = null
        assertTrue(s.backspace(optedIn))
        assertEquals("a", e.text)
        assertTrue(s.selection.latest.rangeEquals(2))
    }

    /** A code-point deletion would refuse an unpaired surrogate, so it goes as a key event. */
    @Test
    fun anOptedInBackspaceOnMalformedTextFallsBackToAKeyEvent() {
        val low = String(Character.toChars(0x1F44B))[1]
        val (s, e) = session("a$low", cursor = 2)
        assertFalse(s.backspace(optedIn))
        assertTrue("predicted like any key event", s.selection.latest.rangeEquals(1))
        assertFalse(e.calls.any { it.startsWith("delete") })
    }

    @Test
    fun aRawKeyEditorGetsAKeyEventEvenIfOptedIn() {
        val (s, _) = session("abc", cursor = 3)
        assertFalse(s.backspace(optedIn.copy(isRawKeyInput = true)))
    }

    @Test
    fun withNoEditorAnOptedInBackspaceFallsBackToAKeyEvent() {
        val (s, e) = session("abc", cursor = 3)
        e.isAvailable = false
        assertFalse(s.backspace(optedIn))
        assertTrue(e.calls.isEmpty())
    }

    @Test
    fun backspaceFinishesALeftoverCompositionAndDeletesBeforeTheCursor() {
        val (s, e) = session("abnihao", cursor = 7)
        e.setComposingRegion(2, 7)
        s.compose("nihao", start = 2)
        assertTrue(s.backspace(EditorTraits(acceptsDeleteSurrounding = true)))
        assertEquals("the character before the cursor, not before the composition", "abniha", e.text)
        assertTrue(s.composing.isEmpty())
        assertEquals(-1, e.composingStart)
        assertTrue(s.selection.latest.rangeEquals(e.selectionStart))
    }

    @Test
    fun aKeyEventBackspaceAlsoFinishesALeftoverComposition() {
        val (s, e) = session("abnihao", cursor = 7)
        e.setComposingRegion(2, 7)
        s.compose("nihao", start = 2)
        e.calls.clear()
        assertFalse(s.backspace(EditorTraits()))
        assertEquals(listOf("finishComposingText()"), e.calls)
        assertTrue(s.composing.isEmpty())
    }

    @Test
    fun finishingTheCompositionLeavesTheTextAndForgetsTheRange() {
        val (s, e) = session("", cursor = 0)
        e.setComposingText("ni")
        s.compose("ni", start = 0)
        e.calls.clear()

        s.finishComposition()

        assertEquals("ni", e.text)
        assertEquals("the editor's composing region is gone", -1, e.composingStart)
        assertTrue(s.composing.isEmpty())
        assertEquals("", s.composingText.toString())
        assertEquals(listOf("finishComposingText()"), e.calls)
    }

    @Test
    fun finishingWithNothingComposedDoesNotTouchTheEditor() {
        val (s, e) = session("abc", cursor = 3)
        s.finishComposition()
        assertTrue(e.calls.isEmpty())
    }

    /** Every mutating entry point, the pairs' too, calls checkThread. */
    @Test
    fun everyMutatingEntryPointChecksTheThread() {
        var checks = 0
        val s = EditingSession(FakeEditor("abcdef", 3)) { checks++ }
        s.selection.resetTo(3)
        val calls = buildMap<String, EditingSession.() -> Unit> {
            put("setComposingText") { setComposingText(FormattedText.Empty) }
            put("resetComposingState") { resetComposingState() }
            put("finishComposition") { finishComposition() }
            put("commitText") { commitText("x") }
            put("deleteSelection") { deleteSelection() }
            put("applySelectionOffset") { applySelectionOffset(0) }
            put("cancelSelection") { cancelSelection() }
            put("select") { select(1, 3) }
            put("selectFrom") { selectFrom(3, 1) }
            put("deleteSurrounding") { deleteSurrounding(1, 0, inCodePoints = false) }
            put("replaceAround") { replaceAround(1, 0, "x") }
            put("backspace") { backspace(EditorTraits()) }
            put("onCursorUpdate") { onCursorUpdate(3, 3, -1, -1) }
            put("updateComposingText") { updateComposingText(formatted("ni")) }
            put("provisionalRestarted") { provisionalRestarted(sameField = true, 3, 3) }
            put("writeCode") { writeCode(formatted("ni")) }
            put("leaveComposition") { leaveComposition() }
            put("codeRestarted") { codeRestarted(again = true) }
            put("judgeCommit") { judgeCommit() }
        }
        val pairCalls = buildMap<String, AutoPairs.() -> Unit> {
            put("type") { type("(") }
            put("committed") { committed("()", cursor = 1, start = 3) }
            put("backspace") { backspace() }
            put("forget") { forget() }
            put("cursorReported") { cursorReported() }
            put("deletedBefore") { deletedBefore(cursor = 3, units = 1) }
        }
        // a new public method must be added above, with a call, or to queries if it changes nothing
        // queries: the listeners, the field's kind, and the context reads; the editor untouched
        val queries = setOf("setProvisionalTaken", "setCodeFellBack", "setInlineCompletion", "beforeCode", "afterCode")
        val pairQueries = emptySet<String>()
        assertEquals("EditingSession methods not listed", emptyList<String>(), unlisted(EditingSession::class.java, calls.keys + queries))
        assertEquals("AutoPairs methods not listed", emptyList<String>(), unlisted(AutoPairs::class.java, pairCalls.keys + pairQueries))
        for ((name, call) in calls) {
            val before = checks
            s.call()
            assertTrue("$name did not check the thread", checks > before)
        }
        for ((name, call) in pairCalls) {
            val before = checks
            s.pairs.call()
            assertTrue("pairs.$name did not check the thread", checks > before)
        }
    }

    private fun unlisted(type: Class<*>, listed: Set<String>): List<String> = type.declaredMethods
        .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        // an internal method's name has its module after a '$'
        .map { it.name.substringBefore('$') }
        .filter { !getter.matches(it) && it !in listed }

    private val getter = Regex("(get|is)\\p{Lu}.*")

    @Test
    fun resettingClearsBothTheRangeAndTheText() {
        val (s, _) = session("ni", cursor = 2)
        s.compose("ni", start = 0)
        s.resetComposingState()
        assertTrue(s.composing.isEmpty())
        assertEquals(FormattedText.Empty, s.composingText)
    }

    private fun EditingSession.report(start: Int, end: Int = start, composing: Pair<Int, Int> = -1 to -1) =
        onCursorUpdate(start, end, composing.first, composing.second)

    private fun preedit(text: String, cursor: Int) = FormattedText(arrayOf(text), intArrayOf(0), cursor)

    // the cursor left it (a tap or an arrow), the editor's span there or not: taken away, the cursor kept
    @Test
    fun aProvisionalCompositionTheCursorLeft() = assertRows(
        "the cursor moved past it: still there, taken away" to {
            val (s, e) = session("abc", cursor = 2)
            val told = mutableListOf<EditingSession.Taken>()
            s.provisionalTaken = { told += it }
            s.updateComposingText(formatted("你好"), provisional = true)
            assertEquals("ab你好c", e.text)
            e.setSelection(5, 5)
            assertEquals(EditingSession.CursorUpdate.None, s.report(5))
            assertEquals(listOf(EditingSession.Taken.AWAY), told)
            assertEquals("abc", e.text)
            assertEquals(3, e.selectionStart)
        },
        "the editor dropped its composing span, the cursor moved past it: still taken away, not left as text" to {
            val (s, e) = session("abc", cursor = 2)
            val told = mutableListOf<EditingSession.Taken>()
            s.provisionalTaken = { told += it }
            s.updateComposingText(formatted("你好"), provisional = true)
            // an InputFilter or a WebView ended the composition on its own: the text stays, no span
            e.finishComposingText()
            e.setSelection(5, 5)
            assertEquals(EditingSession.CursorUpdate.None, s.report(5))
            assertEquals(listOf(EditingSession.Taken.AWAY), told)
            assertEquals("abc", e.text)
            assertEquals(3, e.selectionStart)
        },
        "the cursor moved: taken away here, told so" to {
            val (s, e) = session("ab", cursor = 2)
            val told = mutableListOf<EditingSession.Taken>()
            s.provisionalTaken = { told += it }
            s.updateComposingText(formatted("你好"), provisional = true)
            e.setSelection(1, 1)
            assertEquals(EditingSession.CursorUpdate.None, s.report(1))
            assertEquals(listOf(EditingSession.Taken.AWAY), told)
            assertEquals("ab", e.text)
        },
        "the cursor moved before it: taken away, the cursor where it went" to {
            val (s, e) = session("ab", cursor = 2)
            s.updateComposingText(formatted("你好"), provisional = true)
            assertEquals("ab你好", e.text)
            assertTrue(s.provisional)
            e.setSelection(0, 0)
            assertEquals(EditingSession.CursorUpdate.None, s.report(0))
            assertEquals("ab", e.text)
            assertEquals(0, e.selectionStart)
            assertFalse(s.provisional)
            assertTrue(s.composing.isEmpty())
        },
        "the cursor moved forward past text after it: kept where it went" to {
            val (s, e) = session("ab", cursor = 1)
            s.updateComposingText(formatted("你好"), provisional = true)
            assertEquals("a你好b", e.text)
            e.setSelection(4, 4)
            assertEquals(EditingSession.CursorUpdate.None, s.report(4))
            assertEquals("ab", e.text)
            assertEquals(2, e.selectionStart)
        },
    )

    @Test
    fun aProvisionalCompositionTheUserTappedInto() = assertRows(
        "the cursor tapped inside it: not fcitx's preedit, nothing moved" to {
            val (s, e) = session("ab", cursor = 2)
            s.updateComposingText(formatted("你好"), provisional = true)
            e.setSelection(3, 3)
            assertEquals(EditingSession.CursorUpdate.None, s.report(3, composing = 2 to 4))
            assertTrue(s.provisional)
        }
    )

    @Test
    fun aProvisionalCompositionTakenByTheApp() = assertRows(
        "the field cleared (a chat's text sent)" to {
            val (s, e) = session("ab", cursor = 2)
            val told = mutableListOf<EditingSession.Taken>()
            s.provisionalTaken = { told += it }
            s.updateComposingText(formatted("你好"), provisional = true)
            e.appSetsText("")
            e.calls.clear()
            assertEquals(EditingSession.CursorUpdate.None, s.report(0))
            assertEquals(listOf(EditingSession.Taken.GONE), told)
            assertFalse(s.provisional)
            assertTrue(s.composing.isEmpty())
            assertEquals(listOf("textAfterCursor(4)", "finishComposingText()"), e.calls)
        },
        "all of it composing, the field cleared: the cursor at its start" to {
            val (s, e) = session("", cursor = 0)
            val told = mutableListOf<EditingSession.Taken>()
            s.provisionalTaken = { told += it }
            s.updateComposingText(formatted("你好"), provisional = true)
            // its own report first: the cursor at 0 after it is news, not an old echo
            s.report(2, composing = 0 to 2)
            e.appSetsText("")
            assertEquals(EditingSession.CursorUpdate.None, s.report(0))
            assertEquals(listOf(EditingSession.Taken.GONE), told)
            assertEquals("", e.text)
        },
        "text before it taken, it rewritten: no longer as composed" to {
            val (s, e) = session("ab", cursor = 2)
            val told = mutableListOf<EditingSession.Taken>()
            s.provisionalTaken = { told += it }
            s.updateComposingText(formatted("你好"), provisional = true)
            e.appSetsText("xyz")
            assertEquals(EditingSession.CursorUpdate.None, s.report(3))
            assertEquals(listOf(EditingSession.Taken.GONE), told)
            assertEquals("xyz", e.text)
        },
        "the app shortened it: measured by the span reported" to {
            val (s, e) = session("abc", cursor = 1)
            s.updateComposingText(formatted("你好"), provisional = true)
            // the app made it one character ("a你bc", its span 1..2), then the user tapped at the end
            e.setComposingText("你", 1)
            e.setSelection(4, 4)
            assertEquals(EditingSession.CursorUpdate.None, s.report(4, composing = 1 to 2))
            assertEquals("abc", e.text)
            // one place back, the span's length as reported, not as composed
            assertEquals(3, e.selectionStart)
        },
    )

    @Test
    fun inputRestartedOverAProvisionalComposition() = assertRows(
        "the same field, still there: taken away, to compose anew" to {
            val (s, e) = session("ab", cursor = 2)
            val told = mutableListOf<EditingSession.Taken>()
            s.provisionalTaken = { told += it }
            s.updateComposingText(formatted("你好"), provisional = true)
            s.provisionalRestarted(sameField = true, 4, 4)
            assertEquals("ab", e.text)
            assertEquals(2, e.selectionStart)
            assertEquals(listOf(EditingSession.Taken.AWAY), told)
            assertFalse(s.provisional)
            assertTrue(s.selection.latest.rangeEquals(2))
            assertEquals(EditingSession.CursorUpdate.None, s.report(2))
            assertTrue(s.selection.current.rangeEquals(2))
        },
        "the same field, text after it, the cursor past it: kept after that text" to {
            val (s, e) = session("abc", cursor = 2)
            s.updateComposingText(formatted("你好"), provisional = true)
            assertEquals("ab你好c", e.text)
            e.setSelection(5, 5)
            s.provisionalRestarted(sameField = true, 5, 5)
            assertEquals("abc", e.text)
            assertEquals(3, e.selectionStart)
            assertTrue(s.selection.latest.rangeEquals(3))
        },
        "the same field, the app took it: not touched" to {
            val (s, e) = session("ab", cursor = 2)
            val told = mutableListOf<EditingSession.Taken>()
            s.provisionalTaken = { told += it }
            s.updateComposingText(formatted("你好"), provisional = true)
            e.appSetsText("")
            e.calls.clear()
            s.provisionalRestarted(sameField = true, 0, 0)
            assertEquals("", e.text)
            assertEquals(listOf(EditingSession.Taken.GONE), told)
            assertTrue(e.calls.none { it.startsWith("set") || it.startsWith("commit") })
            assertTrue("finishComposingText()" in e.calls)
        },
        "the same field, the cursor further from it than is read: still there, taken away, nothing that far read" to {
            val far = "x".repeat(5000)
            val (s, e) = session("ab$far", cursor = 2)
            s.updateComposingText(formatted("你好"), provisional = true)
            e.setSelection(4 + far.length, 4 + far.length)
            e.calls.clear()
            s.provisionalRestarted(sameField = true, 4 + far.length, 4 + far.length)
            assertEquals("ab$far", e.text)
            assertFalse(s.provisional)
            // no read across the IPC of maybe the whole document
            assertTrue(e.calls.toString(), e.calls.none { it.startsWith("textBeforeCursor(") && it.removePrefix("textBeforeCursor(").removeSuffix(")").toInt() > 4096 })
        },
        "the same field, its selection unknown: left as text, told so, not as the app's" to {
            val (s, e) = session("ab", cursor = 2)
            val told = mutableListOf<EditingSession.Taken>()
            s.provisionalTaken = { told += it }
            s.updateComposingText(formatted("你好"), provisional = true)
            s.provisionalRestarted(sameField = true, -1, -1)
            assertEquals("ab你好", e.text)
            assertEquals(-1, e.composingStart)
            assertEquals(listOf(EditingSession.Taken.LEFT), told)
        },
        "another field: left as text" to {
            val (s, e) = session("ab", cursor = 2)
            val told = mutableListOf<EditingSession.Taken>()
            s.provisionalTaken = { told += it }
            s.updateComposingText(formatted("你好"), provisional = true)
            s.provisionalRestarted(sameField = false, 0, 0)
            assertEquals("ab你好", e.text)
            assertEquals(-1, e.composingStart)
            assertEquals(listOf(EditingSession.Taken.LEFT), told)
        },
        "not provisional: nothing" to {
            val (s, e) = session("ab", cursor = 2)
            val told = mutableListOf<EditingSession.Taken>()
            s.provisionalTaken = { told += it }
            s.updateComposingText(formatted("ni"))
            s.provisionalRestarted(sameField = true, 4, 4)
            assertEquals("abni", e.text)
            assertEquals(emptyList<EditingSession.Taken>(), told)
        },
    )

    @Test
    fun aProvisionalCompositionIsReplacedByTheCommit() {
        val (s, e) = session("ab")
        s.updateComposingText(formatted("你好"), provisional = true)
        s.commitText("你好世界")
        assertEquals("ab你好世界", e.text)
        assertFalse(s.provisional)
        s.updateComposingText(formatted("ni"))
        assertFalse(s.provisional)
    }

    @Test
    fun anExpectedReportChangesNothing() {
        val (s, e) = session("abc", cursor = 3)
        s.commitText("d")
        assertEquals(EditingSession.CursorUpdate.None, s.report(4))
        assertTrue("confirmed", s.selection.current.rangeEquals(4))
        assertEquals(listOf("commitText(d, 1)"), e.calls)
    }

    @Test
    fun aDroppedComposingSpanIsRestored() {
        val (s, e) = session("ni", cursor = 2)
        s.compose("ni", start = 0)
        assertEquals(EditingSession.CursorUpdate.None, s.report(2, composing = -1 to -1))
        assertEquals(listOf("setComposingRegion(0, 2)"), e.calls)
    }

    @Test
    fun aReportThatNeedsNoRestoringEditsNothing() = assertRows(
        "a reported composing span is left alone" to {
            val (s, e) = session("ni", cursor = 2)
            s.compose("ni", start = 0)
            assertEquals(EditingSession.CursorUpdate.None, s.report(2, composing = 0 to 2))
            assertTrue(e.calls.isEmpty())
        },
        "nothing is restored when there is no composition" to {
            val (s, e) = session("ni", cursor = 2)
            s.report(2)
            assertTrue(e.calls.isEmpty())
        },
    )

    @Test
    fun anUnexpectedReportDoesNotRestoreTheComposingSpan() {
        val (s, e) = session("nihao", cursor = 5)
        s.compose("hao", start = 2)
        s.report(4, composing = -1 to -1)
        assertFalse(e.calls.any { it.startsWith("setComposingRegion") })
    }

    @Test
    fun withNothingComposedACursorReportResetsFcitx() = assertRows(
        "the user moving the cursor with nothing composed resets fcitx" to {
            val (s, _) = session("abcdef", cursor = 6)
            assertEquals(EditingSession.CursorUpdate.ResetIfNotEmpty, s.report(2))
            assertTrue("the report is the new truth", s.selection.latest.rangeEquals(2))
        },
        "the user selecting with nothing composed resets fcitx" to {
            val (s, _) = session("hello world", cursor = 11)
            assertEquals(EditingSession.CursorUpdate.ResetIfNotEmpty, s.report(6, 11))
            assertTrue(s.selection.latest.rangeEquals(6, 11))
        },
    )

    @Test
    fun anUnexpectedSelectionIsTrackedButLeavesTheCompositionAlone() {
        val (s, e) = session("abnihao", cursor = 7)
        s.compose("nihao", start = 2)
        assertEquals(EditingSession.CursorUpdate.None, s.report(0, 3))
        assertTrue(s.selection.latest.rangeEquals(0, 3))
        assertTrue(s.composing.rangeEquals(2, 7))
        assertTrue(e.calls.isEmpty())
    }

    @Test
    fun tappingInsideTheCompositionMovesFcitxsCursorInCodePoints() {
        val wave = String(Character.toChars(0x1F44B))
        val text = "${wave}ni"
        val (s, _) = session("ab$text", cursor = 6)
        s.setComposingText(preedit(text, cursor = 4))
        s.composing.update(2, 6)
        val before = s.cursorUpdateIndex
        val update = s.report(5) // after the emoji and "n": 3 units, 2 code points
        assertEquals(EditingSession.CursorUpdate.MovePreeditCursor(2, before + 1), update)
    }

    @Test
    fun aTapWhereFcitxsCursorAlreadyIsDoesNothing() {
        val (s, _) = session("nihao", cursor = 5)
        s.setComposingText(preedit("nihao", cursor = 2))
        s.composing.update(0, 5)
        assertEquals(EditingSession.CursorUpdate.None, s.report(2))
    }

    @Test
    fun eitherEdgeOfTheCompositionCountsAsInside() {
        val (s, _) = session("abnihao", cursor = 4)
        s.setComposingText(preedit("nihao", cursor = 2))
        s.composing.update(2, 7)
        assertEquals(EditingSession.CursorUpdate.MovePreeditCursor(0, s.cursorUpdateIndex + 1), s.report(2))
        assertEquals(EditingSession.CursorUpdate.MovePreeditCursor(5, s.cursorUpdateIndex + 1), s.report(7))
    }

    @Test
    fun leavingTheCompositionFinishesItInPlace() {
        val (s, e) = session("abnihao", cursor = 7)
        s.compose("nihao", start = 2)
        e.setComposingRegion(2, 7)
        e.calls.clear()
        assertEquals(EditingSession.CursorUpdate.FocusOutIn, s.report(1))
        assertTrue(s.composing.isEmpty())
        assertEquals(FormattedText.Empty, s.composingText)
        assertEquals(listOf("finishComposingText()"), e.calls)
        assertEquals("the text stays", "abnihao", e.text)
    }

    @Test
    fun onlyTheLatestReportsCursorMoveIsCurrent() {
        val (s, _) = session("nihao", cursor = 5)
        s.compose("nihao", start = 0)
        val first = s.report(1) as EditingSession.CursorUpdate.MovePreeditCursor
        val second = s.report(3) as EditingSession.CursorUpdate.MovePreeditCursor
        assertTrue("superseded", first.updateIndex != s.cursorUpdateIndex)
        assertEquals(s.cursorUpdateIndex, second.updateIndex)
    }

    @Test
    fun everyReportCountsEvenAnExpectedOne() {
        val (s, _) = session("abc", cursor = 3)
        val before = s.cursorUpdateIndex
        s.report(3)
        s.report(1)
        assertEquals(before + 2, s.cursorUpdateIndex)
    }

    @Test
    fun aReportSaysWhenItLeftAQuoteFcitxCountsOpenWithNothingComposed() {
        val (s, _) = session()
        assertTrue(s.pairs.type("“"))
        s.report(1)
        assertFalse(s.quoteLeftOpen)
        s.report(0)
        assertTrue(s.quoteLeftOpen)
        s.report(0)
        assertFalse(s.quoteLeftOpen)
        // a ” stepped over with “, reported with nothing composed: fcitx still counts that “ open
        val (o, _) = session()
        o.pairs.type("“")
        o.report(1)
        assertTrue(o.pairs.type("“"))
        o.report(2)
        assertTrue(o.quoteLeftOpen)
        // the report coming once something is composed: a reset would drop it
        val (c, _) = session()
        c.pairs.type("“")
        c.report(1)
        assertTrue(c.pairs.type("“"))
        c.compose("n", start = 2)
        c.report(2, composing = 2 to 3)
        assertFalse(c.pairs.quoteOpen)
        assertFalse(c.quoteLeftOpen)
    }

    private fun assertInStep(s: EditingSession, e: FakeEditor) {
        assertTrue("predicted ${s.selection.latest}, editor at ${e.selectionStart}",
            s.selection.latest.rangeEquals(e.selectionStart, e.selectionEnd))
        val tracked = if (s.composing.isEmpty()) -1 to -1 else s.composing.start to s.composing.end
        assertEquals("composing range", e.composingStart to e.composingEnd, tracked)
    }

    @Test
    fun aFirstPreeditIsComposedAtTheCursor() {
        val (s, e) = session("ab", cursor = 2)
        s.updateComposingText(preedit("ni", cursor = 2))
        assertEquals("abni", e.text)
        assertInStep(s, e)
        assertEquals("ni", s.composingText.toString())
    }

    @Test
    fun aGrowingPreeditReplacesTheComposition() {
        val (s, e) = session("ab", cursor = 1)
        s.updateComposingText(preedit("n", cursor = 1))
        s.updateComposingText(preedit("ni", cursor = 2))
        s.updateComposingText(preedit("nih", cursor = -1))
        assertEquals("anihb", e.text)
        assertInStep(s, e)
    }

    @Test
    fun aPreeditCursorInTheMiddleTakesASelectionInTheSameBatch() {
        val (s, e) = session("", cursor = 0)
        s.updateComposingText(preedit("nihao", cursor = 2))
        assertInStep(s, e)
        assertTrue(s.selection.latest.rangeEquals(2))
        assertEquals(
            listOf("beginBatchEdit()", "setComposingText(nihao, 1)", "setSelection(2, 2)", "endBatchEdit()"),
            e.calls,
        )
    }

    @Test
    fun theSamePreeditWithAMovedCursorOnlyMovesTheCursor() {
        val (s, e) = session("", cursor = 0)
        s.updateComposingText(preedit("nihao", cursor = 5))
        e.calls.clear()
        s.updateComposingText(preedit("nihao", cursor = 1))
        assertEquals(listOf("beginBatchEdit()", "setSelection(1, 1)", "endBatchEdit()"), e.calls)
        assertInStep(s, e)
    }

    @Test
    fun theSamePreeditWithNoCursorLeavesTheCursorAlone() {
        val (s, e) = session("", cursor = 0)
        s.updateComposingText(preedit("nihao", cursor = 5))
        e.calls.clear()
        s.updateComposingText(preedit("nihao", cursor = -1))
        assertEquals("no empty batch either", listOf<String>(), e.calls)
        assertTrue(s.selection.latest.rangeEquals(5))
    }

    @Test
    fun theSamePreeditAndCursorTouchesNothing() {
        val (s, e) = session("", cursor = 0)
        s.updateComposingText(preedit("nihao", cursor = 5))
        e.calls.clear()
        s.updateComposingText(preedit("nihao", cursor = 5))
        assertEquals("no empty batch either", listOf<String>(), e.calls)
    }

    @Test
    fun anEmptyPreeditRemovesTheCompositionAndPutsTheCursorWhereItStarted() {
        val (s, e) = session("ab", cursor = 1)
        s.updateComposingText(preedit("nihao", cursor = 3))
        s.updateComposingText(FormattedText.Empty)
        assertEquals("ab", e.text)
        assertTrue(s.composing.isEmpty())
        assertInStep(s, e)
        assertTrue(s.selection.latest.rangeEquals(1))
    }

    @Test
    fun anEmptyPreeditWithNothingComposedTouchesNothing() {
        val (s, e) = session("ab", cursor = 1)
        s.updateComposingText(FormattedText.Empty)
        assertEquals("no empty batch either", listOf<String>(), e.calls)
        assertTrue(s.selection.latest.rangeEquals(1))
    }

    @Test
    fun thePreeditIsShownAsRendered() {
        val (s, e) = session("", cursor = 0)
        s.updateComposingText(preedit("ni", cursor = 2)) { it.toString().uppercase() }
        assertEquals("NI", e.text)
        assertEquals("the formatted text is what is remembered", "ni", s.composingText.toString())
    }

    @Test
    fun theDebugLogHasThePreeditsLengthNotItsText() {
        val logged = ArrayList<String>()
        CoreLog.sink = { logged += it }
        try {
            val (s, _) = session("", cursor = 0)
            s.updateComposingText(preedit("nihao", cursor = 5))
        } finally {
            CoreLog.sink = null
        }
        assertTrue(logged.any { "composing 5 chars" in it })
        assertFalse(logged.any { "nihao" in it })
    }

    @Test
    fun withNoEditorAPreeditChangesNothing() {
        val (s, e) = session("ab", cursor = 2)
        e.isAvailable = false
        s.updateComposingText(preedit("ni", cursor = 2))
        assertTrue(e.calls.isEmpty())
        assertTrue(s.composing.isEmpty())
        assertEquals(FormattedText.Empty, s.composingText)
        assertTrue(s.selection.latest.rangeEquals(2))
    }

    @Test
    fun typingAWordStaysInStepWithTheEditorsReports() {
        val (s, e) = session("", cursor = 0)
        for ((i, p) in listOf("n", "ni", "nih", "niha", "nihao").withIndex()) {
            s.updateComposingText(preedit(p, cursor = i + 1))
            assertEquals(EditingSession.CursorUpdate.None, s.report(e.selectionStart, composing = e.composingStart to e.composingEnd))
        }
        s.commitText("你好")
        assertEquals("你好", e.text)
        assertEquals(EditingSession.CursorUpdate.None, s.report(e.selectionStart))
        assertInStep(s, e)
    }

    /** Without a connection, nothing may be predicted: the report would never come. */
    @Test
    fun withNoEditorACommitChangesNothing() {
        val (s, e) = session("ni", cursor = 2)
        s.compose("ni", start = 0)
        e.isAvailable = false

        assertEquals("nothing went in", "", s.commitText("你"))

        assertTrue("no prediction was queued", s.selection.latest.rangeEquals(2))
        assertFalse("the composition is still held", s.composing.isEmpty())
        assertTrue(e.calls.isEmpty())
    }

    @Test
    fun withNoEditorASelectionOffsetChangesNothing() {
        val (s, e) = session("abcdef", cursor = 2)
        e.isAvailable = false
        s.applySelectionOffset(0, 3)
        assertTrue(s.selection.latest.rangeEquals(2))
        assertTrue(e.calls.isEmpty())
    }

    @Test
    fun withNoEditorDeletingSurroundingTextChangesNothing() {
        val (s, e) = session("abcdef", cursor = 4)
        e.isAvailable = false
        s.deleteSurrounding(before = 2, after = 0, inCodePoints = true)
        assertTrue(s.selection.latest.rangeEquals(4))
        assertTrue(e.calls.isEmpty())
    }

    @Test
    fun withNoEditorCancellingASelectionChangesNothing() {
        val (s, e) = session("abcdef", cursor = 0)
        s.selection.resetTo(1, 3)
        e.isAvailable = false
        s.cancelSelection()
        assertTrue(s.selection.latest.rangeEquals(1, 3))
        assertTrue(e.calls.isEmpty())
    }

    @Test
    fun withNoEditorDeletingASelectionChangesNothing() {
        val (s, e) = session("abcdef", cursor = 0)
        s.selection.resetTo(1, 3)
        e.isAvailable = false
        s.deleteSelection()
        assertTrue(s.selection.latest.rangeEquals(1, 3))
        assertTrue(e.calls.isEmpty())
    }

    @Test
    fun withNoEditorABackspacePredictsNothing() {
        val (s, e) = session("abc", cursor = 3)
        e.isAvailable = false
        assertFalse(s.backspace(EditorTraits()))
        assertTrue(s.selection.latest.rangeEquals(3))
    }

    @Test
    fun withNoEditorFinishingTheCompositionKeepsIt() {
        val (s, e) = session("ni", cursor = 2)
        s.compose("ni", start = 0)
        e.isAvailable = false
        s.finishComposition()
        assertFalse(s.composing.isEmpty())
        assertTrue(e.calls.isEmpty())
    }

    @Test
    fun composingThenCommittingThenTypingKeepsTheCursorConsistent() {
        val (s, e) = session("", cursor = 0)

        s.compose("nihao", start = 0)
        e.setComposingText("nihao")
        s.selection.consume(5)

        s.commitText("你好")
        assertEquals("你好", e.text)
        assertTrue(s.selection.latest.rangeEquals(2))
        s.selection.consume(2)

        s.commitText("！")
        assertEquals("你好！", e.text)
        assertTrue(s.selection.latest.rangeEquals(3))
    }

    @Test
    fun anExternalCursorMoveIsAbsorbed() {
        val (s, _) = session("hello world", cursor = 11)
        s.selection.resetTo(11)
        s.selection.predict(12) // a commit the editor has not confirmed yet

        assertFalse("the tap was never predicted", s.selection.consume(3))
        assertTrue(s.selection.current.rangeEquals(3))

        s.commitText("X")
        assertTrue("the next commit anchors on the new position", s.selection.latest.rangeEquals(4))
    }

}
