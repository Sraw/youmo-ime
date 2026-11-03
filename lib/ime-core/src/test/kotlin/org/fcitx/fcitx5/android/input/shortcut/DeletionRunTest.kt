/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.input.editing.FakeEditor
import org.fcitx.fcitx5.android.input.editing.WatchedEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backspace's deletions kept to put back ([ClearedText.deleting]), the field a [FakeEditor] as the
 * service reads it; a key event's Backspace taking what the editor calls one character, told here.
 */
class DeletionRunTest {

    // the app's token for the input started: an EditorInfo, told by identity
    private val f = Any()

    private fun FakeEditor.field(before: Int = 10_000, after: Int = 10_000) = EditPlan.Field(
        textBeforeCursor(before).toString(),
        text.substring(minOf(selectionStart, selectionEnd), maxOf(selectionStart, selectionEnd)),
        textAfterCursor(after).toString(),
    )

    // the field as a run reads it, the app giving at most [gives] of each side
    private fun FakeEditor.read(before: Int, after: Int, gives: Int = Int.MAX_VALUE) =
        ClearedText.Read(field(minOf(before, gives), minOf(after, gives)), minOf(selectionStart, selectionEnd))

    /** One Backspace: [units] before the cursor deleted as the editor's one character, or the selection. */
    private fun FakeEditor.backspace(kept: ClearedText, units: Int = 1, gives: Int = Int.MAX_VALUE) {
        val selection = selectionStart != selectionEnd
        kept.deleting(f, selection) { b, a -> read(b, a, gives) }
        if (selection) commitText("", 1) else deleteSurroundingText(units, 0, false)
    }

    private fun FakeEditor.restore(kept: ClearedText, typing: Boolean = false, gives: Int = 10_000): Boolean =
        kept.take(f, field(gives, gives), typing)?.also { EditPlan.apply(it.restore, this) {} } != null

    @Test
    @Uat("KB-64")
    fun backspacesOneAfterAnotherArePutBackTogetherTheCursorWhereItWas() {
        val kept = ClearedText()
        val editor = FakeEditor("hello world!", cursor = 11)
        repeat(3) { editor.backspace(kept) }
        assertEquals("hello wo!", editor.text)
        assertTrue(editor.restore(kept))
        assertEquals("hello world!", editor.text)
        assertEquals(11, editor.selectionStart)
        assertEquals(11, editor.selectionEnd)
        assertFalse("the last alone", editor.restore(kept))
    }

    @Test
    fun aCharacterOfManyCodePointsComesBackWholeHoweverTheEditorTookIt() {
        val family = "👨‍👩‍👧"
        val flag = "🇨🇳"
        val text = "a${family}é$flag"
        val kept = ClearedText()
        val editor = FakeEditor(text)
        editor.backspace(kept, units = 4) // the flag whole
        editor.backspace(kept) // the accent alone, as a TextView's Backspace takes it
        editor.backspace(kept) // the e
        editor.backspace(kept, units = family.length)
        assertEquals("a", editor.text)
        assertTrue(editor.restore(kept))
        assertEquals(text, editor.text)
        assertEquals(text.length, editor.selectionStart)
    }

    @Test
    fun aBackspaceHeldPastTheStartPutsBackAllItTook() {
        val kept = ClearedText()
        val editor = FakeEditor("abc def")
        editor.setSelection(3, 3)
        repeat(8) { editor.backspace(kept, units = if (editor.selectionStart > 0) 1 else 0) }
        assertEquals(" def", editor.text)
        assertTrue(editor.restore(kept))
        assertEquals("abc def", editor.text)
        assertEquals(3, editor.selectionStart)
    }

    @Test
    @Uat("KB-64")
    fun somethingTypedBetweenStartsAnotherRunAndNoneIsPutBackOnceTyped() {
        val kept = ClearedText()
        val editor = FakeEditor("abcd")
        val watched = WatchedEditor(editor) { kept.edited() }
        editor.backspace(kept)
        watched.commitText("x", 1)
        editor.backspace(kept)
        editor.backspace(kept)
        assertEquals("ab", editor.text)
        assertTrue(editor.restore(kept))
        assertEquals("the run after the typing alone", "abcx", editor.text)
        assertFalse(editor.restore(kept))

        editor.backspace(kept)
        watched.commitText("y", 1)
        assertFalse("typed since", editor.restore(kept))
    }

    @Test
    fun theCursorMovedAwayAndBackStartsAnotherRun() {
        val kept = ClearedText()
        val editor = FakeEditor("abcd")
        val watched = WatchedEditor(editor) { kept.edited() }
        editor.backspace(kept)
        watched.setSelection(1, 1)
        watched.setSelection(3, 3)
        editor.backspace(kept)
        assertTrue(editor.restore(kept))
        assertEquals("abc", editor.text)
        assertFalse(editor.restore(kept))
    }

    @Test
    fun whatIsTypedShownInTheFieldEndsTheRunItsCompositionFinishedDoesNot() {
        val kept = ClearedText()
        val editor = FakeEditor("abcd")
        val watched = WatchedEditor(editor) { kept.edited() }
        editor.backspace(kept)
        watched.finishComposingText()
        watched.setComposingRegion(0, 1)
        watched.finishComposingText()
        assertTrue(editor.restore(kept))
        editor.backspace(kept)
        watched.setComposingText("n", 1)
        watched.setComposingText("", 1)
        assertFalse(editor.restore(kept))
        editor.backspace(kept)
        watched.deleteSurroundingText(1, 0, false)
        assertFalse("the engine's own deletion", editor.restore(kept))
    }

    @Test
    fun aDeletionMadeQuietlyIsNoChangeEndingTheRun() {
        val kept = ClearedText()
        val editor = FakeEditor("abcd")
        val watched = WatchedEditor(editor) { kept.edited() }
        repeat(2) {
            kept.deleting(f, false) { b, a -> editor.read(b, a) }
            watched.quietly { watched.deleteSurroundingText(1, 0, true) }
        }
        assertTrue(editor.restore(kept))
        assertEquals("abcd", editor.text)
    }

    @Test
    fun aFieldChangedSomeOtherWayIsNotPutBack() {
        // the app emptied the field (a message sent): no Backspace takes three characters
        val kept = ClearedText()
        val sent = FakeEditor("abc")
        sent.backspace(kept)
        sent.setSelection(0, 2)
        sent.commitText("", 1)
        assertFalse(sent.restore(kept))
        // the cursor elsewhere, what is after it not what the run left
        val moved = FakeEditor("abc def")
        moved.backspace(kept)
        moved.setSelection(2, 2)
        assertFalse(moved.restore(kept))
        // an empty pair's closing one gone with it
        val pair = FakeEditor("a()", cursor = 2)
        pair.backspace(kept)
        pair.deleteSurroundingText(0, 1, false)
        assertFalse(pair.restore(kept))
        // something typed, or the selection not where the run left it
        val typing = FakeEditor("abc")
        typing.backspace(kept)
        assertFalse(typing.restore(kept, typing = true))
        val selected = FakeEditor("abc")
        selected.backspace(kept)
        selected.setSelection(0, 1)
        assertFalse(selected.restore(kept))
    }

    @Test
    fun aSelectionsDeletionGoesWithTheRunAndAloneWithItsSelectionText() {
        val kept = ClearedText()
        val editor = FakeEditor("one two three")
        repeat(2) { editor.backspace(kept) }
        // the swipe's selection, then its deletion
        editor.setSelection(4, 11)
        editor.backspace(kept)
        editor.backspace(kept)
        assertEquals("one", editor.text)
        assertTrue(editor.restore(kept))
        assertEquals("one two three", editor.text)
        assertEquals(13, editor.selectionStart)

        // a selection first: what was selected comes back, the cursor after it
        val first = FakeEditor("hello world")
        first.setSelection(6, 11)
        first.backspace(kept)
        first.backspace(kept)
        assertEquals("hello", first.text)
        assertTrue(first.restore(kept))
        assertEquals("hello world", first.text)
        assertEquals(11, first.selectionStart)
    }

    @Test
    fun aSelectionElsewhereStartsAnotherRun() {
        val kept = ClearedText()
        val editor = FakeEditor("abc def")
        editor.backspace(kept)
        editor.setSelection(0, 2)
        editor.backspace(kept)
        assertEquals("c de", editor.text)
        editor.setSelection(0, 0)
        assertTrue(editor.restore(kept))
        assertEquals("abc de", editor.text)
        assertFalse(editor.restore(kept))
    }

    @Test
    fun beforeACursorFarIntoTheFieldTheRunIsToldWhereItsEndMeets() {
        val kept = ClearedText()
        val long = "x".repeat(ClearedText.READ_BEFORE + 500) + "abc"
        val editor = FakeEditor(long)
        repeat(3) { editor.backspace(kept) }
        assertTrue(editor.restore(kept))
        assertEquals(long, editor.text)
        // the same character over and over: the presses tell how many went
        val ha = "哈".repeat(ClearedText.READ_BEFORE * 2)
        val same = FakeEditor(ha + "!".repeat(ClearedText.READ_AFTER * 2), cursor = ha.length)
        repeat(2) { same.backspace(kept) }
        assertTrue(same.restore(kept))
        assertEquals(ha.length, same.selectionStart)
        assertEquals(ha + "!".repeat(ClearedText.READ_AFTER * 2), same.text)
        // more than is read: not put back
        val held = FakeEditor(long)
        repeat(ClearedText.READ_BEFORE) { held.backspace(kept) }
        assertFalse(held.restore(kept))
    }

    @Test
    fun theLastClearOrRunIsPutBackFirstThenTheOneBefore() {
        val kept = ClearedText()
        val editor = FakeEditor("one two three", cursor = 7)
        // a clear of the selection, a run after it
        editor.setSelection(4, 7)
        val clearing = EditPlan.clear(editor.field(), 4, 7, 10_000, password = false)
        EditPlan.apply(clearing.steps, editor) {}
        kept.remember(f, clearing.cleared)
        editor.backspace(kept)
        assertEquals("one three", editor.text)
        assertTrue(editor.restore(kept))
        assertEquals("one  three", editor.text)
        assertTrue(editor.restore(kept))
        assertEquals("one two three", editor.text)

        // a run, a clear after it: the clear first; a Backspace then is a run of its own
        val other = FakeEditor("abc")
        other.backspace(kept)
        val cleared = EditPlan.clear(other.field(), 2, 2, 10_000, password = false)
        EditPlan.apply(cleared.steps, other) {}
        kept.remember(f, cleared.cleared)
        assertTrue(other.restore(kept))
        assertEquals("ab", other.text)
        other.backspace(kept)
        assertTrue(other.restore(kept))
        assertEquals("ab", other.text)
        assertTrue(other.restore(kept))
        assertEquals("abc", other.text)
    }

    @Test
    fun aBackspaceWithNothingToDeleteLeavesAClearToPutBack() {
        val kept = ClearedText()
        val editor = FakeEditor("abc")
        val clearing = EditPlan.clear(editor.field(), 3, 3, 10_000, password = false)
        EditPlan.apply(clearing.steps, editor) {}
        kept.remember(f, clearing.cleared)
        editor.backspace(kept, units = 0)
        assertTrue(editor.restore(kept))
        assertEquals("abc", editor.text)
    }

    @Test
    fun aFieldNotReadKeepsNothingAndBreaksOffWhatWasBefore() {
        val kept = ClearedText()
        val editor = FakeEditor("abc def", cursor = 3)
        val clearing = EditPlan.clear(editor.field(), 3, 3, 10_000, password = false)
        EditPlan.apply(clearing.steps, editor) {}
        kept.remember(f, clearing.cleared)
        editor.setSelection(4, 4)
        kept.deleting(f, false) { _, _ -> null }
        editor.deleteSurroundingText(1, 0, false)
        editor.commitText("f", 1)
        editor.setSelection(0, 0)
        assertEquals(" def", editor.text)
        assertFalse("the field reads as the clear left it, but a deletion not kept came between", editor.restore(kept))
    }

    @Test
    fun anotherFieldForgetsTheRun() {
        val kept = ClearedText()
        val editor = FakeEditor("abc")
        editor.backspace(kept)
        val other = Any()
        assertEquals(null, kept.take(other, editor.field(), typing = false))
        kept.deleting(other, false) { b, a -> editor.read(b, a) }
        assertEquals(null, kept.take(f, editor.field(), typing = false))
        kept.forget()
        assertEquals(null, kept.take(other, editor.field(), typing = false))
    }

    // review M1: a clear since the run is told by the field alone, kept however the field is edited
    @Test
    fun aClearSinceTheRunOutlastsAnEditAsAClearAloneDoes() {
        val kept = ClearedText()
        val editor = FakeEditor("abc def")
        editor.backspace(kept)
        val clearing = EditPlan.clear(editor.field(), 6, 6, 10_000, password = false)
        EditPlan.apply(clearing.steps, editor) {}
        kept.remember(f, clearing.cleared)
        assertEquals("", editor.text)
        kept.edited()
        assertTrue(editor.restore(kept))
        assertEquals("abc de", editor.text)
        assertFalse("the run, left by a field edited since, is gone", editor.restore(kept))

        // a clear alone: an edit changing nothing the field reads leaves it
        val alone = FakeEditor("xyz")
        val cleared = EditPlan.clear(alone.field(), 3, 3, 10_000, password = false)
        EditPlan.apply(cleared.steps, alone) {}
        kept.remember(f, cleared.cleared)
        kept.edited()
        assertTrue(alone.restore(kept))
        assertEquals("xyz", alone.text)
    }

    // the app changed what is before the cursor, as long as the run left it: nothing put back, not hello's o after help
    @Test
    fun whatIsLeftNotTheStartOfWhatWasReadIsNotTheRunsAndNothingIsPutBack() {
        val kept = ClearedText()
        val editor = FakeEditor("hello")
        editor.backspace(kept)
        editor.setSelection(3, 4)
        editor.commitText("p", 1)
        assertEquals("help", editor.text)
        assertFalse(editor.restore(kept))
        assertEquals("help", editor.text)
    }

    // cut short, two lengths the presses can have taken (an e and its accent, or two accents and an e's): neither put back
    @Test
    fun whereTheEndMeetsInTwoPlacesThePressesMayHaveTakenNothingIsPutBack() {
        val kept = ClearedText()
        val accented = "e\u0301".repeat(ClearedText.READ_BEFORE)
        val editor = FakeEditor(accented)
        repeat(2) { editor.backspace(kept) }
        assertFalse(editor.restore(kept))
        assertEquals(accented.length - 2, editor.text.length)
    }

    // review L6: the same character a long way, every length meeting the end: a few presses' search
    @Test
    fun aRunFarIntoALongFieldOfOneCharacterIsToldQuickly() {
        val kept = ClearedText()
        val ha = "哈".repeat(5_000)
        val editor = FakeEditor(ha)
        repeat(300) { editor.backspace(kept) }
        val start = System.nanoTime()
        assertTrue(editor.restore(kept))
        val ms = (System.nanoTime() - start) / 1e6
        println("DeletionRunTest: 300 of 5000 哈 put back in $ms ms")
        assertEquals(ha, editor.text)
        assertTrue("$ms ms", ms < 500)
    }

    // review M7: an app giving less than asked of a field longer than that: its start is not the read's
    @Test
    fun anAppGivingLessThanAskedIsReadAsCutShort() {
        val kept = ClearedText()
        val a = "a".repeat(500)
        val editor = FakeEditor(a)
        repeat(30) { editor.backspace(kept, gives = 100) }
        assertTrue(editor.restore(kept, gives = 100))
        assertEquals(a, editor.text)
        assertEquals(500, editor.selectionStart)
    }

    // a tap on the field: the next Backspace starts another run, the last put back where it still can be
    @Test
    fun aTapOnTheFieldStartsAnotherRun() {
        val kept = ClearedText()
        val editor = FakeEditor("abcdef")
        editor.backspace(kept)
        kept.moved()
        editor.setSelection(2, 2)
        editor.backspace(kept)
        assertEquals("acde", editor.text)
        assertTrue(editor.restore(kept))
        assertEquals("abcde", editor.text)
        assertEquals(2, editor.selectionStart)
        assertFalse("the first, its cursor elsewhere", editor.restore(kept))

        // tapped where it was: the run kept to put back, a Backspace then one of its own
        val same = FakeEditor("abc")
        same.backspace(kept)
        kept.moved()
        assertTrue(same.restore(kept))
        assertEquals("abc", same.text)
        same.backspace(kept)
        kept.moved()
        same.backspace(kept)
        assertEquals("a", same.text)
        assertTrue(same.restore(kept))
        assertEquals("ab", same.text)
        assertTrue(same.restore(kept))
        assertEquals("abc", same.text)
    }

    // review C: cut short, a ZWJ and the emoji after it count two characters, the emoji before them
    // joined, one: the search goes on past the shorter end that counts one more than the press
    @Test
    fun aZwjSequenceFarIntoTheFieldIsToldPastAnEndCountingOneMore() {
        val kept = ClearedText()
        val text = "👩".repeat(ClearedText.READ_BEFORE) + "\u200D👧"
        val editor = FakeEditor(text)
        editor.backspace(kept, units = 5) // 👩‍👧 whole, one character
        assertTrue(editor.restore(kept))
        assertEquals(text, editor.text)
    }

    // review A2: an edit after a run drops the clears before it too, though the field reads as one left it
    @Test
    fun anEditAfterARunDropsTheClearsBeforeIt() {
        val kept = ClearedText()
        val editor = FakeEditor("abc")
        val clearing = EditPlan.clear(editor.field(), 3, 3, 10_000, password = false)
        EditPlan.apply(clearing.steps, editor) {}
        kept.remember(f, clearing.cleared)
        editor.commitText("x", 1)
        editor.backspace(kept)
        assertEquals("", editor.text)
        kept.edited()
        assertFalse(editor.restore(kept))
        assertEquals("", editor.text)
    }
}
