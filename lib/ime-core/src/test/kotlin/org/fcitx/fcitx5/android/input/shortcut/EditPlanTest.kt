/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.input.editing.FakeEditor
import org.fcitx.fcitx5.android.input.shortcut.EditPlan.Menu
import org.junit.Assert.assertEquals
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

    @Test
    fun aClearPutBackHasTheTextAndCursorAsTheyWere() {
        val editor = FakeEditor("hello world")
        editor.setSelection(2, 4)
        val (before, selected, after) = editor.read()
        val clearing = EditPlan.clear(before, selected, after, limit = 1000, password = false)
        editor.run(clearing.steps)
        assertEquals("", editor.text)
        editor.run(EditPlan.restore(clearing.cleared!!))
        assertEquals("hello world", editor.text)
        assertEquals(2, editor.selectionStart)
        assertEquals(4, editor.selectionEnd)
    }

    @Test
    fun aFieldTooLongToReadIsClearedWithNothingToPutBack() {
        val editor = FakeEditor("x".repeat(50), cursor = 20)
        val (before, selected, after) = editor.read(limit = 10)
        val clearing = EditPlan.clear(before, selected, after, limit = 10, password = false)
        assertNull(clearing.cleared)
        editor.run(clearing.steps)
        assertEquals("", editor.text)
        editor.revealLimit = null
        assertNull("unread", EditPlan.clear(editor.textBeforeCursor(10), "", editor.textAfterCursor(10), 10, password = false).cleared)
    }

    @Test
    fun aPasswordIsClearedButNotKept() {
        val clearing = EditPlan.clear("secret", "", "", limit = 1000, password = true)
        assertNull(clearing.cleared)
        val editor = FakeEditor("secret")
        editor.run(clearing.steps)
        assertEquals("", editor.text)
    }

    @Test
    fun anEmptyFieldNeedsNothing() {
        val clearing = EditPlan.clear("", "", "", limit = 1000, password = false)
        assertTrue(clearing.steps.isEmpty())
        assertNull(clearing.cleared)
        val editor = FakeEditor("")
        editor.run(clearing.steps)
        assertTrue(editor.calls.isEmpty())
    }

    @Test
    fun whatWasClearedIsPutBackInItsFieldWhileStillEmptyOnce() {
        val kept = ClearedText()
        val cleared = EditPlan.Cleared("hi", 2, 2)
        kept.remember("a", cleared)
        assertNull("typed in since", kept.take("a", empty = false))
        assertNull("another field", kept.take("b", empty = true))
        assertEquals(cleared, kept.take("a", empty = true))
        assertNull("taken", kept.take("a", empty = true))
        kept.remember("a", null)
        assertNull(kept.take("a", empty = true))
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
