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

    private fun FakeEditor.clear(kept: ClearedText, limit: Int = 1000, password: Boolean = false): EditPlan.Clearing {
        val clearing = EditPlan.clear(field(limit), minOf(selectionStart, selectionEnd), limit, password)
        run(clearing.steps)
        if (clearing.steps.isNotEmpty()) kept.remember("f", clearing.cleared)
        return clearing
    }

    private fun FakeEditor.restore(kept: ClearedText): Boolean =
        kept.take("f", field())?.also { run(it.restore) } != null

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
        assertNull("another field", kept.take("g", EditPlan.Field("", "", "")))
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
        kept.remember("f", EditPlan.Cleared(field, emptyList(), typed = "nihao"))
        assertNull("typed into since", kept.take("f", EditPlan.Field("abc", "", "")))
        kept.remember("f", EditPlan.Cleared(field, emptyList(), typed = "nihao"))
        assertEquals("nihao", kept.take("f", field)?.typed)
        kept.remember("f", EditPlan.Cleared(field, emptyList()))
        kept.remember("f", null)
        assertNull("a clear not kept", kept.take("f", field))
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
