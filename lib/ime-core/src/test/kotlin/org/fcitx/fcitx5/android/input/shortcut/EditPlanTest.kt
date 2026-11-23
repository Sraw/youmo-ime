/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.input.UndoChipPlacement
import org.fcitx.fcitx5.android.input.editing.FakeEditor
import org.fcitx.fcitx5.android.input.shortcut.EditPlan.Menu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editor as BaseInputConnection's, its context menu as a TextView's. */
class EditPlanTest {

    private val clipboard = StringBuilder()

    private fun FakeEditor.menu(action: Menu) {
        val a = minOf(selectionStart, selectionEnd)
        val b = maxOf(selectionStart, selectionEnd)
        when (action) {
            Menu.SelectAll -> setSelection(0, text.length)
            Menu.Copy -> clipboard.clear().append(text, a, b)
            Menu.Cut -> {
                clipboard.clear().append(text, a, b)
                commitText("", 1)
            }
        }
    }

    private fun FakeEditor.run(steps: List<EditPlan.Step>) = EditPlan.apply(steps, this) { menu(it) }

    private fun FakeEditor.read(limit: Int = 1000) = Triple(
        textBeforeCursor(limit),
        text.substring(minOf(selectionStart, selectionEnd), maxOf(selectionStart, selectionEnd)),
        textAfterCursor(limit),
    )

    @Test
    fun aCopyOrCutHasNothingToTakeOnlyWithNothingSelectedInAFieldReadEmpty() {
        assertTrue(EditPlan.nothingToTake(0, 0, EditPlan.Field("", "", "")))
        // not read is not empty: the whole field taken as before
        assertFalse(EditPlan.nothingToTake(3, 3, null))
        assertFalse(EditPlan.nothingToTake(0, 0, null))
        // a field of raw keys (a terminal) read empty is not known empty either
        assertFalse(EditPlan.nothingToTake(0, 0, EditPlan.Field("", "", ""), rawKeys = true))
        assertFalse(EditPlan.nothingToTake(0, 2, null))
        assertFalse(EditPlan.nothingToTake(0, 2, EditPlan.Field("", "", "")))
        assertFalse(EditPlan.nothingToTake(1, 1, EditPlan.Field("a", "", "")))
        assertFalse(EditPlan.nothingToTake(0, 0, EditPlan.Field("", "", "b")))
        // a selection the report has not caught up with
        assertFalse(EditPlan.nothingToTake(0, 0, EditPlan.Field("", "ab", "")))
    }

    @Test
    fun aCopyOfNothingSelectedIsTheWholeFieldTheCursorStaying() {
        val editor = FakeEditor("hello world", cursor = 5)
        editor.run(EditPlan.copy(5, 5))
        assertEquals("hello world", clipboard.toString())
        assertEquals(5, editor.selectionStart)
        assertEquals(5, editor.selectionEnd)
    }

    @Test
    fun aCopyOfASelectionLeavesTheCursorAtItsEnd() {
        val editor = FakeEditor("hello world")
        editor.setSelection(0, 5)
        editor.run(EditPlan.copy(0, 5))
        assertEquals("hello", clipboard.toString())
        assertEquals(5, editor.selectionStart)
        assertEquals(5, editor.selectionEnd)
    }

    @Test
    fun aCutOfNothingSelectedEmptiesTheField() {
        val editor = FakeEditor("hello world", cursor = 3)
        editor.run(EditPlan.cut(3, 3))
        assertEquals("", editor.text)
        assertEquals("hello world", clipboard.toString())
        val selected = FakeEditor("hello world")
        selected.setSelection(6, 11)
        selected.run(EditPlan.cut(6, 11))
        assertEquals("hello ", selected.text)
    }

    private fun FakeEditor.field(limit: Int = 1000) = read(limit).let { (b, s, a) -> EditPlan.Field(b.toString(), s, a.toString()) }

    // the app's token for the input started: an EditorInfo, told by identity
    private val f = Any()

    private fun FakeEditor.clear(kept: ClearedText, limit: Int = 1000, password: Boolean = false): EditPlan.Clearing {
        val clearing = EditPlan.clear(field(limit), selectionStart, selectionEnd, limit, password)
        run(clearing.steps)
        if (clearing.steps.isNotEmpty()) kept.remember(f, clearing.cleared)
        return clearing
    }

    private fun FakeEditor.restore(kept: ClearedText, typing: Boolean = false): Boolean =
        kept.take(f, field(), typing)?.also { run(it.restoreAt(minOf(selectionStart, selectionEnd))) } != null

    @Test
    fun aClearTakesTheSelectionThenWhatIsBeforeTheCursorThenWhatIsAfter() {
        val kept = ClearedText()
        val editor = FakeEditor("one two three", cursor = 8)
        editor.setSelection(4, 8)
        editor.clear(kept)
        assertEquals("one three", editor.text)
        editor.clear(kept)
        assertEquals("three", editor.text)
        assertEquals(0, editor.selectionStart)
        editor.clear(kept)
        assertEquals("", editor.text)
        assertTrue("nothing left", editor.clear(kept).steps.isEmpty())
        // put back last first, each in its place, the selection as it was
        assertTrue(editor.restore(kept))
        assertEquals("three", editor.text)
        assertEquals(0, editor.selectionStart)
        assertTrue(editor.restore(kept))
        assertEquals("one three", editor.text)
        assertEquals(4, editor.selectionStart)
        assertTrue(editor.restore(kept))
        assertEquals("one two three", editor.text)
        assertEquals(4, editor.selectionStart)
        assertEquals(8, editor.selectionEnd)
        assertFalse("all put back", editor.restore(kept))
    }

    @Test
    fun nothingIsPutBackOnceTheFieldChanged() {
        val kept = ClearedText()
        val editor = FakeEditor("ab cd", cursor = 2)
        editor.clear(kept)
        editor.commitText("x", 1)
        assertFalse(editor.restore(kept))
        editor.setSelection(0, 1)
        editor.commitText("", 1)
        assertEquals(" cd", editor.text)
        assertFalse("forgotten, not just passed over", editor.restore(kept))
        val moved = FakeEditor("ab cd", cursor = 5)
        moved.clear(kept)
        assertEquals("", moved.text)
        assertTrue(moved.restore(kept))
        assertEquals(5, moved.selectionStart)
        assertNull("another field", kept.take(Any(), EditPlan.Field("", "", ""), typing = false))
    }

    @Test
    fun theCursorAtTheStartClearsWhatIsAfterIt() {
        val kept = ClearedText()
        val editor = FakeEditor("hello", cursor = 0)
        editor.clear(kept)
        assertEquals("", editor.text)
        assertTrue(editor.restore(kept))
        assertEquals("hello", editor.text)
        assertEquals(0, editor.selectionStart)
    }

    // an app that reads nothing before the cursor, the cursor reported past the start
    @Uat("KB-62")
    @Test
    fun nothingReadBeforeAReportedCursorPutsWhatIsAfterBackAtThatCursor() {
        val kept = ClearedText()
        val editor = FakeEditor("abc def", cursor = 3)
        val clearing = EditPlan.clear(EditPlan.Field("", "", " def"), 3, 3, 1000, password = false)
        assertEquals(listOf(EditPlan.Step.Delete(0, 4)), clearing.steps)
        editor.run(clearing.steps)
        assertEquals("abc", editor.text)
        assertFalse("what is before the cursor never read", clearing.cleared!!.whole)
        kept.remember(f, clearing.cleared)
        assertTrue(editor.restore(kept))
        assertEquals("abc def", editor.text)
        assertEquals(3, editor.selectionStart)
        assertEquals(3, editor.selectionEnd)
    }

    // nothing before the cursor given, the read after it cut at the limit: only what was read after goes
    @Uat("KB-88")
    @Test
    fun aLongFieldAfterACursorWithTextBeforeItNotReadKeepsThatText() = assertRows(
        listOf(
            Triple("before not given, the cursor reported past the start", false, 3),
            Triple("before not given, the cursor reported at the start", false, 0),
            Triple("before read empty, the cursor reported past the start", true, 3),
        ),
        { it.first },
    ) { (_, given, reported) ->
        val editor = FakeEditor("abc" + "x".repeat(12), cursor = 3)
        val clearing = EditPlan.clear(EditPlan.Field("", "", editor.textAfterCursor(10).toString()), reported, reported, 10, password = false, beforeGiven = given)
        assertNull("cut short: not kept", clearing.cleared)
        editor.run(clearing.steps)
        assertEquals("abcxx", editor.text)
    }

    // a field read whole from its start is still taken whole
    @Test
    fun aLongFieldAfterACursorReadAtTheStartIsTakenWhole() {
        val editor = FakeEditor("x".repeat(12), cursor = 0)
        val clearing = EditPlan.clear(EditPlan.Field("", "", "x".repeat(10)), 0, 0, 10, password = false)
        assertNull(clearing.cleared)
        editor.run(clearing.steps)
        assertEquals("", editor.text)
    }

    // a field one side of which the app does not give: not put back once anything was typed
    @Uat("KB-88")
    @Test
    fun aClearWithASideNotGivenIsNotPutBackOnceSomethingIsTyped() = assertRows(
        listOf(
            "what is after a cursor reported at the start" to { EditPlan.clear(EditPlan.Field("", "", "def"), 0, 0, 1000, password = false, beforeGiven = false) },
            "a selection" to { EditPlan.clear(EditPlan.Field("", "bc", "def"), 1, 3, 1000, password = false, beforeGiven = false) },
            "what is before the cursor, after it not given" to { EditPlan.clear(EditPlan.Field("abc", "", ""), 3, 3, 1000, password = false, afterGiven = false) },
        ),
        { it.first },
    ) { (_, clear) ->
        val cleared = clear().cleared!!
        assertFalse(cleared.whole)
        val kept = ClearedText()
        kept.remember(f, cleared)
        kept.edited()
        assertNull(kept.take(f, cleared.left, typing = false))
    }

    // the cursor just put at the start, the report stale as 清除 is pressed: the report at undo time tells them apart
    @Test
    fun whatIsAfterACursorWhoseReportLaggedIsPutBackWithTheCursorWhereTheEditorHasIt() = assertRows(
        listOf(
            Triple("the report lagged: the editor's cursor at the start", FakeEditor("abc def", cursor = 0), 0),
            Triple("the app gives nothing before its cursor", FakeEditor("abc def", cursor = 3), 3),
        ),
        { it.first },
    ) { (_, editor, cursor) ->
        val after = editor.textAfterCursor(1000).toString()
        val clearing = EditPlan.clear(EditPlan.Field("", "", after), 3, 3, 1000, password = false)
        editor.run(clearing.steps)
        assertEquals(cursor, editor.selectionStart)
        // the editor's report, settled, as the app has it when the clear is taken back
        editor.run(clearing.cleared!!.restoreAt(editor.selectionStart))
        assertEquals("abc def", editor.text)
        assertEquals(cursor, editor.selectionStart)
        assertEquals(cursor, editor.selectionEnd)
    }

    // only a cursor left before the text put back goes by the later report
    @Test
    fun theReportAsAClearIsTakenBackMovesOnlyACursorLeftBeforeTheText() {
        val after = EditPlan.clear(EditPlan.Field("", "", "def"), 3, 3, 1000, password = false).cleared!!
        assertEquals(after.restore, after.restoreAt(-1))
        assertEquals(listOf(EditPlan.Step.Replace("def"), EditPlan.Step.Select(0, 0)), after.restoreAt(0))
        val selection = EditPlan.clear(EditPlan.Field("ab", "cd", "ef"), 2, 4, 1000, password = false).cleared!!
        assertEquals(selection.restore, selection.restoreAt(0))
        val before = EditPlan.clear(EditPlan.Field("ab", "", "ef"), 2, 2, 1000, password = false).cleared!!
        assertEquals(before.restore, before.restoreAt(0))
    }

    @Test
    fun aSideTooLongToReadIsClearedWithNothingToPutBack() {
        val kept = ClearedText()
        val editor = FakeEditor("x".repeat(50) + "yz", cursor = 50)
        val clearing = editor.clear(kept, limit = 10)
        assertNull(clearing.cleared)
        assertEquals("yz", editor.text)
        val after = FakeEditor("x".repeat(50), cursor = 0)
        assertNull(after.clear(kept, limit = 10).cleared)
        assertEquals("", after.text)
        assertFalse(after.restore(kept))
    }

    @Test
    fun aPasswordIsClearedButNotKept() {
        val kept = ClearedText()
        val editor = FakeEditor("secret", cursor = 6)
        assertNull(editor.clear(kept, password = true).cleared)
        assertEquals("", editor.text)
        assertFalse(editor.restore(kept))
    }

    @Test
    fun anEmptyFieldNeedsNothing() {
        val editor = FakeEditor("")
        val clearing = editor.clear(ClearedText())
        assertTrue(clearing.steps.isEmpty())
        assertNull(clearing.cleared)
        assertEquals("", editor.text)
    }

    @Test
    fun whatWasTypedIsKeptLikeAnyClear() {
        val kept = ClearedText()
        val field = EditPlan.Field("ab", "", "")
        kept.remember(f, EditPlan.Cleared(field, emptyList(), typed = "nihao"))
        assertNull("typed into since", kept.take(f, EditPlan.Field("abc", "", ""), typing = false))
        kept.remember(f, EditPlan.Cleared(field, emptyList(), typed = "nihao"))
        assertEquals("nihao", kept.take(f, field, typing = false)?.typed)
        kept.remember(f, EditPlan.Cleared(field, emptyList()))
        kept.remember(f, null)
        assertNull("a clear not kept", kept.take(f, field, typing = false))
    }

    // typing never reaches the field (pinyin's shows above it): typed and cleared since, nothing is put back
    @Test
    fun nothingIsPutBackWhileSomethingIsTypedNorPastWhatWasTypedSince() {
        val kept = ClearedText()
        val editor = FakeEditor("hello", cursor = 5)
        editor.clear(kept)
        assertFalse("ni being typed", editor.restore(kept, typing = true))
        assertFalse("forgotten, not just passed over", editor.restore(kept))
        val field = EditPlan.Field("", "", "")
        kept.remember(f, EditPlan.Cleared(field, emptyList(), typed = "ni"))
        kept.remember(f, EditPlan.Cleared(field, emptyList(), typed = "hao"))
        assertEquals("hao", kept.take(f, field, typing = false)?.typed)
        assertNull("ni was typed over by hao: gone", kept.take(f, field, typing = false))
        // the text cleared, then pinyin typed and cleared: back to the pinyin, no further
        val text = FakeEditor("hello", cursor = 5)
        text.clear(kept)
        kept.remember(f, EditPlan.Cleared(text.field(), emptyList(), typed = "ni"))
        assertEquals("ni", kept.take(f, text.field(), typing = false)?.typed)
        assertFalse(text.restore(kept))
        assertEquals("", text.text)
    }

    // another field that reads the same is another input: nothing put back there
    @Test
    fun aClearIsPutBackInTheInputItWasClearedInAlone() {
        val kept = ClearedText()
        val editor = FakeEditor("to A", cursor = 4)
        editor.clear(kept)
        assertNull("B's box, as empty", kept.take(Any(), editor.field(), typing = false))
        val again = FakeEditor("to A", cursor = 4)
        again.clear(kept)
        kept.forget()
        assertFalse(again.restore(kept))
        assertEquals("", again.text)
    }

    @Test
    fun atMostSoManyClearsAreKept() {
        val kept = ClearedText()
        val field = EditPlan.Field("", "", "")
        repeat(ClearedText.MAX + 4) { kept.remember(f, EditPlan.Cleared(field, emptyList())) }
        var taken = 0
        while (kept.take(f, field, typing = false) != null) taken++
        assertEquals(ClearedText.MAX, taken)
    }

    // the app gives no text of the selection: the selection still goes, never the side before it, nothing kept
    @Test
    fun aSelectionWhoseTextIsNotGivenIsDeletedNotKept() {
        val kept = ClearedText()
        val editor = FakeEditor("one two three")
        editor.setSelection(4, 8)
        val clearing = EditPlan.clear(EditPlan.Field("one ", "", "three"), 4, 8, 1000, password = false)
        editor.run(clearing.steps)
        assertNull(clearing.cleared)
        assertEquals("one three", editor.text)
        val long = FakeEditor("abcdefgh")
        long.setSelection(1, 7)
        assertNull(long.clear(kept, limit = 4).cleared)
        assertEquals("ah", long.text)
    }

    // a reported cursor past the text read: only what was read goes, from the editor's cursor, and it can be put back
    @Test
    fun aCursorReportedPastTheTextReadClearsOnlyWhatWasReadAndKeepsIt() {
        val kept = ClearedText()
        val editor = FakeEditor("abc def", cursor = 3)
        val clearing = EditPlan.clear(editor.field(), 6, 6, 1000, password = false)
        assertEquals(listOf(EditPlan.Step.Delete(3, 0)), clearing.steps)
        editor.run(clearing.steps)
        assertEquals(" def", editor.text)
        kept.remember(f, clearing.cleared)
        assertTrue(editor.restore(kept))
        assertEquals("abc def", editor.text)
    }

    // an app giving less text before the cursor than asked: only that goes, and 撤销 puts back exactly that
    @Test
    @Uat("KB-62")
    fun aShortReadClearsWhatWasGivenAndPutsBackJustThatAtTheCursor() {
        val kept = ClearedText()
        val editor = FakeEditor("abcdef gh", cursor = 6)
        val given = EditPlan.Field("def", "", " gh")
        val clearing = EditPlan.clear(given, 6, 6, 1000, password = false)
        assertEquals(listOf(EditPlan.Step.Delete(3, 0)), clearing.steps)
        editor.run(clearing.steps)
        assertEquals("abc gh", editor.text)
        kept.remember(f, clearing.cleared)
        assertTrue(editor.restore(kept))
        assertEquals("abcdef gh", editor.text)
        assertEquals(6, editor.selectionStart)
        assertEquals(6, editor.selectionEnd)
        // the cursor moved since, what is after it not as the clear left it: nothing put back
        val again = EditPlan.clear(given, 6, 6, 1000, password = false)
        editor.run(again.steps)
        kept.remember(f, again.cleared)
        editor.setSelection(6, 6)
        assertFalse(editor.restore(kept))
    }

    // a short read's clear cannot see typing before the cursor: the typing itself ends it
    @Test
    @Uat("KB-62")
    fun aShortReadClearIsNotPutBackOnceSomethingWasTypedBeforeTheCursor() {
        class Row(val typed: String, val why: String)
        val rows = listOf(
            Row("x", "a key typed before the cursor"),
            Row("好", "a Chinese character committed, not an ASCII one: a short read ends the same way"),
        )
        assertRows(rows, { it.why }) { row ->
            val typed = row.typed
            val kept = ClearedText()
            val editor = FakeEditor("abcdef gh", cursor = 6)
            val clearing = EditPlan.clear(EditPlan.Field("def", "", " gh"), 6, 6, 1000, password = false)
            editor.run(clearing.steps)
            kept.remember(f, clearing.cleared)
            editor.commitText(typed, 1)
            kept.edited()
            assertFalse(typed, editor.restore(kept))
            assertEquals("abc$typed gh", editor.text)
        }
    }

    // a clear matches the field by all of it where it read all before the cursor, else the selection and after
    @Test
    fun aClearMatchesTheFieldWholeOnlyWhereItReadAllBeforeTheCursor() {
        val left = EditPlan.Field("", "", " gh")
        class Row(val whole: Boolean, val now: EditPlan.Field, val leaves: Boolean, val why: String)
        val rows = listOf(
            Row(true, left, true, "whole, the field as the clear left it"),
            Row(true, EditPlan.Field("abc", "", " gh"), false, "whole, text typed before the cursor is seen"),
            Row(true, EditPlan.Field("", "", " gx"), false, "whole, what is after changed"),
            Row(false, left, true, "short read, the field as the clear left it"),
            Row(false, EditPlan.Field("abc", "", " gh"), true, "short read, a change before the cursor is not seen"),
            Row(false, EditPlan.Field("abc", "", " gx"), false, "short read, what is after changed"),
            Row(false, EditPlan.Field("", "s", " gh"), false, "short read, a selection made since"),
        )
        assertRows(rows, { it.why }) { row ->
            assertEquals(row.leaves, EditPlan.Cleared(left, emptyList(), whole = row.whole).leaves(row.now))
        }
    }

    // a read cut at the limit, a reported cursor past the editor's: all before the editor's cursor goes, never after
    @Test
    @Uat("KB-62")
    fun aClearAtTheLimitWithTheCursorReportedAheadKeepsWhatIsAfterTheCursor() {
        val kept = ClearedText()
        val editor = FakeEditor("x".repeat(50) + "yz", cursor = 50)
        val clearing = EditPlan.clear(editor.field(10), 52, 52, 10, password = false)
        editor.run(clearing.steps)
        assertEquals("yz", editor.text)
        assertNull(clearing.cleared)
        kept.remember(f, clearing.cleared)
        assertFalse(editor.restore(kept))
    }

    // the reported cursor behind the editor's: measured from where the editor has the cursor
    @Test
    fun aSideReadWholeGoesByItsLengthFromTheEditorsCursor() {
        val kept = ClearedText()
        val editor = FakeEditor("hello world", cursor = 11)
        val clearing = EditPlan.clear(editor.field(), 6, 6, 1000, password = false)
        editor.run(clearing.steps)
        assertEquals("", editor.text)
        kept.remember(f, clearing.cleared)
        assertTrue(editor.restore(kept))
        assertEquals("hello world", editor.text)
        val after = FakeEditor("hello", cursor = 0)
        after.run(EditPlan.clear(after.field(), 3, 3, 1000, password = false).steps)
        assertEquals("", after.text)
    }

    // a selection the report has not caught up with: the selection goes, not kept
    @Test
    fun aSelectionTheReportLagsIsWhatGoesNotKept() {
        val kept = ClearedText()
        val editor = FakeEditor("one two three")
        editor.setSelection(4, 8)
        val clearing = EditPlan.clear(editor.field(), 2, 2, 1000, password = false)
        editor.run(clearing.steps)
        assertEquals("one three", editor.text)
        assertNull(clearing.cleared)
        kept.remember(f, clearing.cleared)
        assertFalse(editor.restore(kept))
    }

    @Test
    fun theEditMenusPasteIsOverTheFingerTheRowKeptOnTheScreen() {
        assertEquals(KeyCommand.Paste, EditMenuLayout.CELLS[EditMenuLayout.START])
        val cells = EditMenuLayout.CELLS.size
        val start = EditMenuLayout.START
        // centred: the paste cell's middle at the key's
        assertEquals(500 - 50 - 300, EditMenuLayout.rowLeft(500, 100, 0, 1080, cells, start))
        assertEquals("at the left edge", 0, EditMenuLayout.rowLeft(100, 100, 0, 1080, cells, start))
        assertEquals("at the right edge", 1080 - 500, EditMenuLayout.rowLeft(1050, 100, 0, 1080, cells, start))
        assertEquals("narrower than the row", 0, EditMenuLayout.rowLeft(200, 100, 0, 300, cells, start))
        // 选输入法's row: its own cells, its start over the key
        assertEquals(500 - 50 - 100, EditMenuLayout.rowLeft(500, 100, 0, 1080, count = 4, start = 1))
        assertEquals("at the right edge", 1080 - 400, EditMenuLayout.rowLeft(1050, 100, 0, 1080, count = 4, start = 1))
        assertTrue(EditMenuLayout.disabled(KeyCommand.Copy, password = true))
        assertEquals(false, EditMenuLayout.disabled(KeyCommand.Paste, password = true))
        assertEquals(false, EditMenuLayout.disabled(KeyCommand.Copy, password = false))
    }

    // the row up where a notice goes; over the bar where the notice's place would not show
    @Test
    fun theEditMenuGoesWhereTheNoticesGo() {
        val cells = EditMenuLayout.CELLS.size
        val start = EditMenuLayout.START
        val above = UndoChipPlacement.Place(left = 340, top = 900, slide = UndoChipPlacement.Slide.Up)
        assertEquals(EditMenuLayout.Spot(150, 900), EditMenuLayout.place(above, 500, 100, 0, 1080, roomTop = 0, barMiddle = 1050, count = cells, start = start))
        val below = above.copy(top = 1500, slide = UndoChipPlacement.Slide.Down)
        assertEquals(EditMenuLayout.Spot(0, 1500), EditMenuLayout.place(below, 100, 100, 0, 1080, roomTop = 0, barMiddle = 1050, count = cells, start = start))
        val beside = UndoChipPlacement.Place(left = 700, top = 1200, slide = UndoChipPlacement.Slide.Right)
        assertEquals(EditMenuLayout.Spot(700, 1200), EditMenuLayout.place(beside, 300, 100, 0, 1080, roomTop = 0, barMiddle = 1050, count = cells, start = start))
        assertEquals("no room above", EditMenuLayout.Spot(150, 1000), EditMenuLayout.place(above, 500, 100, 0, 1080, roomTop = 950, barMiddle = 1050, count = cells, start = start))
        // a bar's middle less than half a cell below the view's top: not off it
        assertEquals("over the bar, inside the view", EditMenuLayout.Spot(150, 1000), EditMenuLayout.place(above, 500, 100, 0, 1080, roomTop = 1000, barMiddle = 1020, count = cells, start = start))
    }
}
