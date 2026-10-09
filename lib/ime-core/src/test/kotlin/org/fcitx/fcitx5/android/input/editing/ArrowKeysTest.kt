/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editing

import org.fcitx.fcitx5.android.input.editing.EditorKeyPolicy.ArrowAction
import org.fcitx.fcitx5.android.input.editing.EditorKeyPolicy.Direction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Arrow presses ahead of the editor's cursor reports, as a space-bar swipe in a slow editor sends
 * them: none may land between the halves of an emoji, where the next commit would split it, and
 * the editor is read once a report rather than once a press, each read being a wait on the app.
 */
class ArrowKeysTest {

    private val wave = String(Character.toChars(0x1F44B)) // two UTF-16 units

    /** The service's side: the editor, the session's cursor, and the reports not delivered yet. */
    private class Arrows(text: String, cursor: Int = text.length, readAhead: Int = ArrowKeys.READ_AHEAD) {
        val editor = FakeEditor(text, cursor)
        val session = EditingSession(editor).apply { selection.resetTo(cursor) }
        val keys = ArrowKeys(session.selection, readAhead)
        private val reports = ArrayDeque<Int>()

        var reads = 0
            private set
        private val before: (Int) -> CharSequence? = { reads++; editor.textBeforeCursor(it) }
        private val after: (Int) -> CharSequence? = { reads++; editor.textAfterCursor(it) }

        val cursor get() = editor.selectionStart

        fun press(direction: Direction, times: Int = 1) = repeat(times) {
            val action = keys.onArrow(EditorTraits(), direction, before, after)
            if (action is ArrowAction.MoveCursor) {
                val was = editor.selectionStart to editor.selectionEnd
                editor.setSelection(action.position, action.position)
                // the editor reports a change only, and ignores a cursor outside the text
                if (editor.selectionStart to editor.selectionEnd != was) reports.addLast(action.position)
            }
        }

        /** A press whose reads the app does not answer in time: they come back null. */
        fun pressUnanswered(direction: Direction) {
            editor.revealLimit = null
            press(direction)
            editor.revealLimit = Int.MAX_VALUE
        }

        /** The editor reports a cursor, as `onUpdateSelection` passes it on. */
        fun report(start: Int, end: Int = start) {
            session.onCursorUpdate(start, end, -1, -1, ignoreSystemCursor = false)
            keys.cursorReported(start, end)
        }

        /** The editor catches up on [n] of the presses. */
        fun reportPresses(n: Int = reports.size) = repeat(n) { report(reports.removeFirst()) }
    }

    /** The width read at the live cursor is added to where the press before put it, not to the last report. */
    @Test
    fun pressesAheadOfTheReportsStepOverAWholeEmoji() {
        val left = Arrows("a${wave}b")
        left.press(Direction.Left, 2)
        assertEquals("before the emoji, not at 2 between its halves", 1, left.cursor)
        val right = Arrows("a${wave}b", cursor = 0)
        right.press(Direction.Right, 2)
        assertEquals(3, right.cursor)
        // the reports catching up move nothing, and the next press goes on from there
        right.reportPresses()
        right.press(Direction.Right)
        assertEquals(4, right.cursor)
    }

    /** Unanswered, a press still moves a unit, and the next one goes on from there. */
    @Test
    fun aPressWhoseReadGoesUnansweredIsGoneOnFrom() {
        val left = Arrows("a${wave}b")
        left.pressUnanswered(Direction.Left)
        assertEquals(3, left.cursor)
        left.press(Direction.Left)
        assertEquals("before the emoji, not at 2 between its halves", 1, left.cursor)
        val right = Arrows("a${wave}b", cursor = 0)
        right.pressUnanswered(Direction.Right)
        right.press(Direction.Right)
        assertEquals(3, right.cursor)
    }

    /** At the start, the unit moved is one the editor ignored: no move to go on from. */
    @Test
    fun anUnansweredPressAtTheStartMovesNothingToGoOnFrom() {
        val a = Arrows("ab", cursor = 0)
        a.pressUnanswered(Direction.Left)
        a.press(Direction.Right)
        assertEquals(1, a.cursor)
    }

    @Test
    fun aRunOfPressesReadsTheEditorOnceAReport() {
        val a = Arrows("abcdefghij")
        a.press(Direction.Left, 5)
        assertEquals(5, a.cursor)
        assertEquals(1, a.reads)
        // the text may have changed with a report: read again, at where the presses put the cursor
        a.reportPresses(1)
        a.press(Direction.Left)
        assertEquals(4, a.cursor)
        assertEquals(2, a.reads)
    }

    /** An emoji half out of what was read is read whole; at the start of the text, nothing is read again. */
    @Test
    fun pastWhatWasReadAPressReadsOnAndAtAnEndNoMore() {
        val a = Arrows("x${wave}abc", readAhead = 4)
        a.press(Direction.Left, 4)
        assertEquals(1, a.cursor)
        assertEquals(2, a.reads)
        a.press(Direction.Left, 3)
        assertEquals(0, a.cursor)
        assertEquals("one read to find the start, none after", 4, a.reads)
        // the presses past the start, which the editor ignored, moved nothing to go on from
        a.press(Direction.Right, 2)
        assertEquals(3, a.cursor)
        assertEquals(5, a.reads)
    }

    /** The app placed the cursor elsewhere, say: the presses go on from there. */
    @Test
    fun aReportOfACursorNoPressPutThereStartsThePressesOver() {
        val a = Arrows("abcdef")
        a.press(Direction.Left)
        a.editor.reportSelection(2)
        a.report(2)
        a.press(Direction.Left)
        assertEquals(1, a.cursor)
    }

    /** Its report is no cursor of the presses, but no news to the IME either. */
    @Test
    fun theReportOfTheImesOwnEditKeepsThePressesAfterIt() {
        val a = Arrows("ab")
        a.session.commitText(wave)
        a.press(Direction.Left, 2)
        assertEquals(1, a.cursor)
        a.report(4)
        a.press(Direction.Left)
        assertEquals("from 1, not from the commit's 4 into the emoji", 0, a.cursor)
    }

    @Test
    fun aPressAfterCollapsingASelectionGoesOnFromItsSide() {
        val a = Arrows("abc")
        a.editor.setSelection(1, 3)
        a.session.selection.resetTo(1, 3)
        a.press(Direction.Left, 2)
        assertEquals(0, a.cursor)
    }

    /** A new input can start where the IME last saw the cursor: nothing pressed before carries over. */
    @Test
    fun aNewInputForgetsThePresses() {
        val a = Arrows("abcdef")
        a.press(Direction.Left)
        a.editor.reportSelection(6)
        a.keys.forget()
        a.press(Direction.Left)
        assertEquals(5, a.cursor)
    }
}
