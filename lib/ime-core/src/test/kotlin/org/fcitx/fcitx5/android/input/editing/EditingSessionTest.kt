/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.core.FormattedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cursor, selection and composing arithmetic -- the part of an IME that is hardest to get
 * right and, until this was extracted from `FcitxInputMethodService`, could only be exercised
 * by typing on a device and watching for the cursor to jump.
 */
class EditingSessionTest {

    private fun session(text: String = "", cursor: Int = text.length): Pair<EditingSession, FakeEditor> {
        val editor = FakeEditor(text, cursor)
        val session = EditingSession(editor)
        session.selection.resetTo(cursor)
        return session to editor
    }

    private fun formatted(text: String) = FormattedText(arrayOf(text), intArrayOf(0), -1)

    /** Puts the session into the state it would be in mid-composition. */
    private fun EditingSession.compose(text: String, start: Int) {
        setComposingText(formatted(text))
        composing.update(start, start + text.length)
    }

    // region commitText into an empty buffer

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

    // endregion

    // region commitText with an explicit cursor

    @Test
    fun anExplicitCursorPlacesTheCaretInsideTheCommittedText() {
        val (s, e) = session()
        s.commitText("()", cursor = 1)
        assertEquals("()", e.text)
        assertTrue("caret between the brackets", s.selection.latest.rangeEquals(1))
        assertEquals(1, e.selectionStart)
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

    // endregion

    // region commitText while composing

    @Test
    fun committingDifferentTextReplacesTheComposingRegion() {
        val (s, e) = session("", cursor = 0)
        s.compose("ni", start = 0)
        e.showComposingText("ni", start = 0)
        s.commitText("你")
        assertEquals("the composing text was replaced, not appended to", "你", e.text)
        assertTrue("composing state is cleared", s.composing.isEmpty())
        assertEquals(FormattedText.Empty, s.composingText)
        assertTrue("anchored at the composing start, not the cursor", s.selection.latest.rangeEquals(1))
        assertEquals("commitText(你, 1)", e.calls.last())
    }

    /**
     * When the commit is character-for-character what is already composing, the text is left
     * alone and the composition is simply finished. Re-committing it would make the editor
     * redraw and can drop spans other IMEs or the app put there.
     */
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

    // endregion

    // region deleteSelection

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

    // endregion

    // region applySelectionOffset

    @Test
    fun extendingTheSelectionToTheRight() {
        val (s, e) = session("abcdef", cursor = 2)
        s.selection.resetTo(2)
        s.applySelectionOffset(0, 3)
        assertTrue(s.selection.latest.rangeEquals(2, 5))
        assertEquals("setSelection(2, 5)", e.calls.last())
    }

    @Test
    fun extendingTheSelectionToTheLeft() {
        val (s, _) = session("abcdef", cursor = 4)
        s.selection.resetTo(4)
        s.applySelectionOffset(-2, 0)
        assertTrue(s.selection.latest.rangeEquals(2, 4))
    }

    @Test
    fun movingBothEndsShiftsTheWholeSelection() {
        val (s, _) = session("abcdef", cursor = 0)
        s.selection.resetTo(1, 3)
        s.applySelectionOffset(1, 1)
        assertTrue(s.selection.latest.rangeEquals(2, 4))
    }

    @Test
    fun theStartIsClampedAtTheBeginningOfTheBuffer() {
        val (s, _) = session("abc", cursor = 1)
        s.selection.resetTo(1)
        s.applySelectionOffset(-5, 0)
        assertTrue("clamped to 0 rather than going negative", s.selection.latest.rangeEquals(0, 1))
    }

    /** An offset that would turn the range inside out is dropped, not applied backwards. */
    @Test
    fun anInvertingOffsetIsIgnored() {
        val (s, e) = session("abcdef", cursor = 0)
        s.selection.resetTo(2, 4)
        s.applySelectionOffset(3, -3)
        assertTrue("the selection is unchanged", s.selection.latest.rangeEquals(2, 4))
        assertTrue("the editor was not touched", e.calls.isEmpty())
    }

    /** A zero offset re-asserts the current range to the editor rather than being skipped. */
    @Test
    fun offsettingByZeroStillSetsTheSelection() {
        val (s, e) = session("abc", cursor = 1)
        s.selection.resetTo(1, 2)
        s.applySelectionOffset(0, 0)
        assertEquals(listOf("setSelection(1, 2)"), e.calls)
    }

    // endregion

    // region cancelSelection

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

    // endregion

    // region deleteSurrounding

    @Test
    fun deletingBeforeTheCursorMovesThePredictedCursorBack() {
        val (s, e) = session("abcdef", cursor = 4)
        s.selection.resetTo(4)
        e.reportSelection(4)

        s.deleteSurrounding(before = 2, after = 0, inCodePoints = false)

        assertEquals("abef", e.text)
        assertTrue(s.selection.latest.rangeEquals(2))
    }

    /** Deleting after the cursor does not move it, so nothing is predicted. */
    @Test
    fun deletingAfterTheCursorLeavesThePredictionAlone() {
        val (s, e) = session("abcdef", cursor = 2)
        s.selection.resetTo(2)
        e.reportSelection(2)

        s.deleteSurrounding(before = 0, after = 2, inCodePoints = false)

        assertEquals("abef", e.text)
        assertTrue(s.selection.latest.rangeEquals(2))
    }

    /**
     * One press should remove a whole emoji, not half a surrogate pair. The session's job is to
     * forward the caller's choice of unit to the editor unchanged.
     */
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
            e.calls,
        )
    }

    /**
     * Pins a latent inaccuracy that predates the refactor: the cursor is predicted to move back
     * by `before` UTF-16 units even when the editor deletes `before` *code points*. Deleting one
     * emoji therefore predicts a 1-unit move for a 2-unit change, and the editor's report is
     * later treated as an unpredicted (external) cursor move. See dev/ISSUES.md #6.
     */
    @Test
    fun deletingByCodePointsPredictsInUnitsNotCodePoints() {
        val wave = String(Character.toChars(0x1F44B)) // two UTF-16 units
        val (s, e) = session("a$wave", cursor = 3)
        s.selection.resetTo(3)
        e.reportSelection(3)

        s.deleteSurrounding(before = 1, after = 0, inCodePoints = true)

        assertEquals("the editor removed the whole emoji", "a", e.text)
        assertTrue("but the prediction moved back one unit, to 2 rather than 1", s.selection.latest.rangeEquals(2))
    }

    // endregion

    // region composing state

    @Test
    fun resettingClearsBothTheRangeAndTheText() {
        val (s, _) = session("ni", cursor = 2)
        s.compose("ni", start = 0)
        s.resetComposingState()
        assertTrue(s.composing.isEmpty())
        assertEquals(FormattedText.Empty, s.composingText)
    }

    @Test
    fun aDroppedComposingSpanIsRestored() {
        val (s, e) = session("ni", cursor = 2)
        s.compose("ni", start = 0)
        assertTrue("the editor reported no composing span", s.restoreComposingRegionIfDropped(-1, -1))
        assertEquals("setComposingRegion(0, 2)", e.calls.last())
    }

    @Test
    fun aReportedComposingSpanIsLeftAlone() {
        val (s, e) = session("ni", cursor = 2)
        s.compose("ni", start = 0)
        assertFalse(s.restoreComposingRegionIfDropped(0, 2))
        assertTrue(e.calls.isEmpty())
    }

    @Test
    fun nothingIsRestoredWhenThereIsNoComposition() {
        val (s, e) = session("ni", cursor = 2)
        assertFalse(s.restoreComposingRegionIfDropped(-1, -1))
        assertTrue(e.calls.isEmpty())
    }

    // endregion

    // region no editor

    /**
     * Between input sessions there is no connection. Operations that predict a cursor move must
     * not do so when the editor call is going to be dropped, or the IME is left expecting a
     * report that never comes.
     */
    @Test
    fun withNoEditorACommitChangesNothing() {
        val (s, e) = session("ni", cursor = 2)
        s.compose("ni", start = 0)
        e.isAvailable = false

        s.commitText("你")

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

    /** These two were unguarded in the original service and stay that way. */
    @Test
    fun cancelSelectionIsNotGuardedJustLikeBefore() {
        val (s, e) = session("abcdef", cursor = 0)
        s.selection.resetTo(1, 3)
        e.isAvailable = false
        s.cancelSelection()
        assertTrue("prediction still made", s.selection.latest.rangeEquals(3))
    }

    // endregion

    // region sequences

    /** Type pinyin, pick a candidate, keep typing: the cursor must track through all of it. */
    @Test
    fun composingThenCommittingThenTypingKeepsTheCursorConsistent() {
        val (s, e) = session("", cursor = 0)

        // the service has shown "nihao" as composing text; the editor holds it as a region
        s.compose("nihao", start = 0)
        e.showComposingText("nihao", start = 0)
        s.selection.consume(5)

        s.commitText("你好")
        assertEquals("你好", e.text)
        assertTrue(s.selection.latest.rangeEquals(2))
        s.selection.consume(2)

        s.commitText("！")
        assertEquals("你好！", e.text)
        assertTrue(s.selection.latest.rangeEquals(3))
    }

    /** The user taps elsewhere mid-composition; the session must follow, not drift. */
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

    // endregion
}
