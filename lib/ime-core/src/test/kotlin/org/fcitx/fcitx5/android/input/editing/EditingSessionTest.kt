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
        e.setComposingText("ni")
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

    /**
     * Pins upstream behaviour: this branch compares against the *confirmed* cursor, where every
     * other path uses the latest prediction. With a move to 0 still unconfirmed and the editor
     * last seen at 2 (the target), no setSelection is sent -- so if the pending move does land,
     * the caret ends up at 0 rather than after the composition.
     */
    @Test
    fun finishingAnIdenticalCompositionComparesWithTheConfirmedCursorNotAPendingOne() {
        val (s, e) = session("你好", cursor = 2)
        s.compose("你好", start = 0)
        s.selection.resetTo(2)
        s.selection.predict(0) // e.g. an arrow key the editor has not acknowledged yet

        s.commitText("你好")

        assertFalse("no move sent, as the confirmed cursor is already there", e.calls.any { it.startsWith("setSelection") })
        assertTrue("and the pending prediction is left as it was", s.selection.latest.rangeEquals(0))
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
            e.calls.filter { it.startsWith("delete") },
        )
    }

    /**
     * The editor reports positions in UTF-16 units, so a deletion counted in code points must
     * still be predicted in units. Deleting one emoji moves the cursor back two, and predicting
     * one made the editor's report look like an external cursor move (it used to).
     */
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

    /** Asking for more code points than exist deletes to the start, and predicts exactly that. */
    @Test
    fun deletingMoreCodePointsThanExistStopsAtTheStart() {
        val (s, e) = session("ab", cursor = 2)
        s.deleteSurrounding(before = 5, after = 0, inCodePoints = true)
        assertEquals("", e.text)
        assertTrue(s.selection.latest.rangeEquals(0))
    }

    /**
     * An editor that will not show its text leaves no way to measure, so the old one-unit-per-
     * code-point guess stands. Right for everything outside the astral planes.
     */
    @Test
    fun anEditorHidingItsTextFallsBackToOneUnitPerCodePoint() {
        val wave = String(Character.toChars(0x1F44B))
        val (s, e) = session("a$wave", cursor = 3)
        e.revealLimit = null
        s.deleteSurrounding(before = 1, after = 0, inCodePoints = true)
        assertEquals("the editor still removed the whole emoji", "a", e.text)
        assertTrue("but with nothing to measure, one unit was guessed", s.selection.latest.rangeEquals(2))
    }

    /**
     * Returning less text than the cursor position allows means the editor held some back,
     * not that the text starts there. Counting what came back would under-predict.
     */
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

    /** Malformed text is refused by the editor, so no move is predicted. */
    @Test
    fun anUnpairedSurrogateBeforeTheCursorPredictsNoMove() {
        val low = String(Character.toChars(0x1F44B))[1]
        val (s, e) = session("a$low", cursor = 2)
        s.deleteSurrounding(before = 1, after = 0, inCodePoints = true)
        assertEquals("the editor deleted nothing", "a$low", e.text)
        assertTrue(s.selection.latest.rangeEquals(2))
    }

    /**
     * With a composition, the editor deletes before the composing region -- composing text is
     * never deleted -- so that is where the code points are measured, not at the cursor.
     */
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

    /** Text either side goes; a selection in the middle survives, shifted left. */
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

    // endregion

    // region backspace

    private val optedIn = EditorTraits(acceptsDeleteSurrounding = true)

    /** Almost every editor gets a key event: the only thing all of them understand. */
    @Test
    fun anOrdinaryEditorGetsAKeyEvent() {
        val (s, e) = session("abc", cursor = 3)
        assertFalse(s.backspace(EditorTraits()))
        assertTrue("the editor was not edited directly", e.calls.none { it.startsWith("delete") || it.startsWith("commit") })
        assertTrue("the key event is still predicted to delete one", s.selection.latest.rangeEquals(2))
    }

    /**
     * A key event deletes whatever the editor considers one character, which can't be known
     * without a round trip per press. The prediction stays at one unit, and the editor is not
     * asked -- Backspace auto-repeats, so that round trip would be paid many times a second.
     */
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

    /** At the very start there is nothing to delete; the key event lets the editor decide. */
    @Test
    fun atTheStartEvenAnOptedInEditorGetsAKeyEvent() {
        val (s, e) = session("abc", cursor = 0)
        assertFalse(s.backspace(optedIn))
        assertTrue("no move predicted", s.selection.latest.rangeEquals(0))
        assertTrue(e.calls.isEmpty())
    }

    /** An opted-in editor that hides its text still loses a whole emoji; the guess is one unit. */
    @Test
    fun anOptedInEditorHidingItsTextFallsBackToOneUnit() {
        val wave = String(Character.toChars(0x1F44B))
        val (s, e) = session("a$wave", cursor = 3)
        e.revealLimit = null
        assertTrue(s.backspace(optedIn))
        assertEquals("a", e.text)
        assertTrue(s.selection.latest.rangeEquals(2))
    }

    /**
     * A code-point deletion would refuse an unpaired surrogate and leave it stuck, so an
     * opted-in editor gets a key event for it instead, which does remove it.
     */
    @Test
    fun anOptedInBackspaceOnMalformedTextFallsBackToAKeyEvent() {
        val low = String(Character.toChars(0x1F44B))[1]
        val (s, e) = session("a$low", cursor = 2)
        assertFalse(s.backspace(optedIn))
        assertTrue("predicted like any key event", s.selection.latest.rangeEquals(1))
        assertFalse(e.calls.any { it.startsWith("delete") })
    }

    /** A deletion before the composition moves the composition too. */
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

    /** A TYPE_NULL editor takes raw keys only, whatever it asked for. */
    @Test
    fun aRawKeyEditorGetsAKeyEventEvenIfOptedIn() {
        val (s, _) = session("abc", cursor = 3)
        assertFalse(s.backspace(optedIn.copy(isRawKeyInput = true)))
    }

    /** With no connection a direct edit would be dropped; fall back to the key event. */
    @Test
    fun withNoEditorAnOptedInBackspaceFallsBackToAKeyEvent() {
        val (s, e) = session("abc", cursor = 3)
        e.isAvailable = false
        assertFalse(s.backspace(optedIn))
        assertTrue(e.calls.isEmpty())
    }

    // endregion

    // region composing state

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

    /** Every mutating entry point reports in, so a debug build can catch a wrong-thread caller. */
    @Test
    fun everyMutatingEntryPointChecksTheThread() {
        var checks = 0
        val s = EditingSession(FakeEditor("abcdef", 3)) { checks++ }
        s.selection.resetTo(3)
        val calls = listOf<EditingSession.() -> Unit>(
            { setComposingText(FormattedText.Empty) },
            { resetComposingState() },
            { finishComposition() },
            { commitText("x") },
            { deleteSelection() },
            { applySelectionOffset(0) },
            { cancelSelection() },
            { deleteSurrounding(1, 0, inCodePoints = false) },
            { backspace(EditorTraits()) },
            { onCursorUpdate(3, 3, -1, -1, ignoreSystemCursor = false) },
            { updateComposingText(formatted("ni")) },
        )
        // a new public method must be added above; this catches one that was not
        val mutators = EditingSession::class.java.declaredMethods.filter {
            java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.name.startsWith("get") &&
                !it.isSynthetic && !it.name.contains('$')
        }
        assertEquals("public methods: ${mutators.map { it.name }}", calls.size, mutators.size)
        calls.forEachIndexed { i, call ->
            val before = checks
            s.call()
            assertTrue("call #$i did not check the thread", checks > before)
        }
    }

    @Test
    fun resettingClearsBothTheRangeAndTheText() {
        val (s, _) = session("ni", cursor = 2)
        s.compose("ni", start = 0)
        s.resetComposingState()
        assertTrue(s.composing.isEmpty())
        assertEquals(FormattedText.Empty, s.composingText)
    }

    // endregion

    // region cursor reports (onUpdateSelection)

    private fun EditingSession.report(start: Int, end: Int = start, composing: Pair<Int, Int> = -1 to -1, ignore: Boolean = false) =
        onCursorUpdate(start, end, composing.first, composing.second, ignoreSystemCursor = ignore)

    private fun preedit(text: String, cursor: Int) = FormattedText(arrayOf(text), intArrayOf(0), cursor)

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
    fun aReportedComposingSpanIsLeftAlone() {
        val (s, e) = session("ni", cursor = 2)
        s.compose("ni", start = 0)
        assertEquals(EditingSession.CursorUpdate.None, s.report(2, composing = 0 to 2))
        assertTrue(e.calls.isEmpty())
    }

    @Test
    fun nothingIsRestoredWhenThereIsNoComposition() {
        val (s, e) = session("ni", cursor = 2)
        s.report(2)
        assertTrue(e.calls.isEmpty())
    }

    /** An InputFilter may also have changed the text, so the old range means nothing then. */
    @Test
    fun anUnexpectedReportDoesNotRestoreTheComposingSpan() {
        val (s, e) = session("nihao", cursor = 5)
        s.compose("hao", start = 2)
        s.report(4, composing = -1 to -1)
        assertFalse(e.calls.any { it.startsWith("setComposingRegion") })
    }

    @Test
    fun theUserMovingTheCursorWithNothingComposedResetsFcitx() {
        val (s, _) = session("abcdef", cursor = 6)
        assertEquals(EditingSession.CursorUpdate.ResetIfNotEmpty, s.report(2))
        assertTrue("the report is the new truth", s.selection.latest.rangeEquals(2))
    }

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
    fun theIgnoreSystemCursorSettingKeepsFcitxsCursorWhereItIs() {
        val (s, _) = session("nihao", cursor = 5)
        s.compose("nihao", start = 0)
        assertEquals(EditingSession.CursorUpdate.None, s.report(2, ignore = true))
        assertTrue("the report is still tracked", s.selection.latest.rangeEquals(2))
    }

    /** The setting only concerns taps inside the preedit; the rest of a report is acted on. */
    @Test
    fun theIgnoreSystemCursorSettingStillFinishesACompositionLeftBehind() {
        val (s, e) = session("abnihao", cursor = 7)
        s.compose("nihao", start = 2)
        assertEquals(EditingSession.CursorUpdate.FocusOutIn, s.report(0, ignore = true))
        assertEquals(listOf("finishComposingText()"), e.calls)
    }

    @Test
    fun theIgnoreSystemCursorSettingStillResetsFcitxWithNothingComposed() {
        val (s, _) = session("abc", cursor = 3)
        assertEquals(EditingSession.CursorUpdate.ResetIfNotEmpty, s.report(1, ignore = true))
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

    // endregion

    // region preedit (updateComposingText)

    /** The session's prediction and composing range must be what the editor ended up with. */
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

    /** fcitx sends -1 when the preedit has no cursor; there is nowhere to move to. */
    @Test
    fun theSamePreeditWithNoCursorLeavesTheCursorAlone() {
        val (s, e) = session("", cursor = 0)
        s.updateComposingText(preedit("nihao", cursor = 5))
        e.calls.clear()
        s.updateComposingText(preedit("nihao", cursor = -1))
        assertEquals(listOf("beginBatchEdit()", "endBatchEdit()"), e.calls)
        assertTrue(s.selection.latest.rangeEquals(5))
    }

    @Test
    fun theSamePreeditAndCursorTouchesNothing() {
        val (s, e) = session("", cursor = 0)
        s.updateComposingText(preedit("nihao", cursor = 5))
        e.calls.clear()
        s.updateComposingText(preedit("nihao", cursor = 5))
        assertEquals(listOf("beginBatchEdit()", "endBatchEdit()"), e.calls)
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
        assertEquals(listOf("beginBatchEdit()", "endBatchEdit()"), e.calls)
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
    fun withNoEditorAPreeditChangesNothing() {
        val (s, e) = session("ab", cursor = 2)
        e.isAvailable = false
        s.updateComposingText(preedit("ni", cursor = 2))
        assertTrue(e.calls.isEmpty())
        assertTrue(s.composing.isEmpty())
        assertEquals(FormattedText.Empty, s.composingText)
        assertTrue(s.selection.latest.rangeEquals(2))
    }

    /** The whole round trip: compose, have the editor report back, commit. */
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

    /** The key event the caller falls back to is dropped too, so no move may be expected. */
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

    // endregion

    // region sequences

    /** Type pinyin, pick a candidate, keep typing: the cursor must track through all of it. */
    @Test
    fun composingThenCommittingThenTypingKeepsTheCursorConsistent() {
        val (s, e) = session("", cursor = 0)

        // the service has shown "nihao" as composing text; the editor holds it as a region
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
