/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.input.editing.FakeEditor
import org.fcitx.fcitx5.android.input.shortcut.EditPlan.Menu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The steps carried out on an editor as BaseInputConnection's, its context menu as a TextView's. */
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
        kept.take(f, field(), typing)?.also { run(it.restore) } != null

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

    // review S1: typing never reaches the field (pinyin's shows above it), so the field read alone
    // cannot tell it: something typed now, or typed and cleared since, and nothing is put back
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

    // review M1: another field that reads the same (another chat's empty box, the same view) is
    // another input started: nothing put back there; input started anew or finished forgets all
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

    // review S2: the app gives no text of the selection (none, or too much to): the selection is
    // still what goes, never the side before it, and nothing is kept
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

    // review S2: the reported cursor behind the editor's (a report not in yet): what was read
    // before or after it is what goes, measured from where the editor has the cursor
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

    // review2: a selection the report has not caught up with (collapsed yet, the app giving its text):
    // the selection goes, never all before it, and is put back where the text before it ends
    @Test
    fun aSelectionTheReportLagsIsWhatGoesAndComesBack() {
        val kept = ClearedText()
        val editor = FakeEditor("one two three")
        editor.setSelection(4, 8)
        val clearing = EditPlan.clear(editor.field(), 2, 2, 1000, password = false)
        editor.run(clearing.steps)
        assertEquals("one three", editor.text)
        kept.remember(f, clearing.cleared)
        assertTrue(editor.restore(kept))
        assertEquals("one two three", editor.text)
        assertEquals(4, editor.selectionStart)
        assertEquals(8, editor.selectionEnd)
        // where the text before it is cut short, where it starts is not known: deleted, not kept
        val long = FakeEditor("abcdefgh")
        long.setSelection(5, 6)
        val cut = EditPlan.clear(long.field(4), 0, 0, 4, password = false)
        long.run(cut.steps)
        assertEquals("abcdegh", long.text)
        assertNull(cut.cleared)
    }

    @Test
    fun theEditMenusPasteIsOverTheFingerTheRowKeptOnTheScreen() {
        assertEquals(KeyCommand.Paste, EditMenuLayout.CELLS[EditMenuLayout.START])
        assertEquals(5, EditMenuLayout.CELLS.size)
        // centred: the paste cell's middle at the key's
        assertEquals(500 - 50 - 300, EditMenuLayout.rowLeft(500, 100, 0, 1080))
        assertEquals("at the left edge", 0, EditMenuLayout.rowLeft(100, 100, 0, 1080))
        assertEquals("at the right edge", 1080 - 500, EditMenuLayout.rowLeft(1050, 100, 0, 1080))
        assertEquals("narrower than the row", 0, EditMenuLayout.rowLeft(200, 100, 0, 300))
        assertTrue(EditMenuLayout.disabled(KeyCommand.Copy, password = true))
        assertEquals(false, EditMenuLayout.disabled(KeyCommand.Paste, password = true))
        assertEquals(false, EditMenuLayout.disabled(KeyCommand.Copy, password = false))
    }
}
