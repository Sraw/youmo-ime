/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The field as a session wrote it (U-03, impl/design-1c.md §2; review-design-1c M1): deletions, contexts, a cursor put back. */
class CommitJournalTest {

    private val journal = CommitJournal()
    private val undone = ArrayList<Long>()
    private var undoes = true
    private val undo = { c: Commit -> undoes.also { if (it) undone += c.seq } }

    private fun commit(seq: Long) = Commit(seq, 0, 0, 0, 0, emptyList())

    private fun write(vararg texts: String) = texts.forEachIndexed { i, t -> journal.committed(t, commit(i + 1L)) }

    // a Backspace, as the session has it: what the last deleted, taken back first
    private fun backspaces(n: Int) = repeat(n) {
        journal.settle(undo)
        journal.backspace()
    }

    private fun context(before: String, after: String? = null, started: Boolean = false, cleared: Boolean = false, selected: Boolean = false, said: Boolean = false) =
        journal.context(before, after, started, cleared, selected, said)

    // HW-23: an empty field focused again after a Reset. Not said (the editor would not): still not known, a Backspace
    // drops all (review-1c M1); said empty by the editor, the field is empty, known: what is then written and deleted is taken back
    @Test
    fun anEmptyFieldTheEditorSaidIsKnownOneItWouldNotSayIsNot() {
        write("打了")
        journal.unverify()
        context("")
        write("吗")
        backspaces(1)
        journal.settle(undo)
        assertTrue(undone.isEmpty())
        journal.unverify()
        context("", said = true)
        write("吗")
        backspaces(1)
        // the field said empty again after the Backspace: as written, the deletion stands
        context("", said = true)
        journal.settle(undo)
        assertEquals(listOf(1L), undone)
        assertEquals(listOf(1L), journal.pending.map { it.seq })
    }

    // 打了 吗, Backspace three times: both gone whole, the last first taken back, at the next action; in the order written waiting
    @Test
    fun commitsGoneWholeAreTakenBackNewestFirst() {
        write("打了", "吗")
        backspaces(1)
        assertTrue(undone.isEmpty())
        backspaces(1)
        assertEquals(listOf(2L), undone)
        assertTrue(journal.pending.isEmpty())
        backspaces(1)
        journal.settle(undo)
        assertEquals(listOf(2L, 1L), undone)
        assertEquals(listOf(1L, 2L), journal.pending.map { it.seq })
    }

    @Test
    fun aCommitGonePartlyIsNoUndoAndNothingWaits() {
        write("你好")
        backspaces(1)
        journal.settle(undo)
        assertTrue(undone.isEmpty())
        assertTrue(journal.pending.isEmpty())
    }

    // a code point a Backspace: 𠮷 is two chars, one character
    @Test
    fun aBackspaceTakesACodePoint() {
        write("𠮷")
        backspaces(1)
        journal.settle(undo)
        assertEquals(listOf(1L), undone)
    }

    // an emoji sequence: how much a Backspace takes is the editor's; the next Backspace, not checked, drops all
    @Test
    fun aCommitOfSeveralCodePointsToACharacterIsNotCounted() {
        assertFalse(CommitJournal.countable("👍🏽"))
        assertFalse(CommitJournal.countable("❤️"))
        assertFalse(CommitJournal.countable("🇨🇳"))
        assertTrue(CommitJournal.countable("😀你"))
        write("吗", "👍🏽")
        backspaces(3)
        journal.settle(undo)
        assertTrue(undone.isEmpty())
    }

    // one not taken back (past the tail) ends the run: none deleted before it wait
    @Test
    fun oneNotTakenBackEndsTheRun() {
        write("打了", "吗")
        undoes = false
        backspaces(3)
        journal.settle(undo)
        assertTrue(journal.pending.isEmpty())
    }

    // 吗 deleted whole, then something before it this session never wrote: 吗 taken back, nothing waits
    @Test
    fun deletingPastWhatWasWrittenDropsAll() {
        write("吗")
        backspaces(2)
        journal.settle(undo)
        assertEquals(listOf(1L), undone)
        assertTrue(journal.pending.isEmpty())
        journal.committed("吧", commit(9))
        backspaces(1)
        journal.settle(undo)
        assertEquals(listOf(1L, 9L), undone)
    }

    // review-design-1c M1: text the session did not write (punctuation passed on, a panel's), then a Backspace: all dropped
    @Test
    fun aBackspaceAfterTextNotWrittenHereDropsAll() {
        write("你好")
        journal.unverify()
        backspaces(3)
        journal.settle(undo)
        assertTrue(undone.isEmpty())
        // a context that agrees makes it sure again
        write("吗")
        journal.unverify()
        context("你吗", "")
        assertTrue(journal.verified)
        backspaces(1)
        journal.settle(undo)
        assertEquals(listOf(1L), undone)
    }

    // a selection elsewhere deleted by a Backspace: the context read right after says 吗 is still there, 给 gone
    @Test
    fun aDeletionTheContextContradictsIsNotTakenBack() {
        write("打了", "吗")
        context("给打了吗", "")
        backspaces(1)
        context("打了吗", "")
        journal.settle(undo)
        assertTrue(undone.isEmpty())
        // all dropped, as not known
        backspaces(2)
        journal.settle(undo)
        assertTrue(undone.isEmpty())
    }

    // the same, the selection not told: the context right after the Backspace says what it deleted (review-1c S2)
    @Test
    fun aBackspaceTheContextSaysDeletedASelectionIsChargedToIt() {
        context("", "", started = true)
        write("给", "打了", "吗")
        backspaces(1)
        context("给", "吗")
        journal.settle(undo)
        assertEquals(listOf(2L), undone)
        assertEquals(listOf(2L), journal.pending.map { it.seq })
        // 吗 still there
        context("给", "吗")
        assertTrue(journal.verified)
    }

    // an editor that reports each Backspace: the context after it agrees, and takes back what went
    @Test
    fun aContextAfterEachBackspaceChecksIt() {
        write("打了", "吗")
        context("给打了吗", "")
        backspaces(1)
        context("给打了", "")
        // taken back at the next action, not by the context
        assertTrue(undone.isEmpty())
        journal.settle(undo)
        assertEquals(listOf(2L), undone)
    }

    // an editor that tells a context after each Backspace: 吗 and 打了 deleted one by one both wait to be typed again (verify-1c)
    @Test
    fun aContextAfterEachBackspaceKeepsTheRunOfDeletions() {
        context("给", "", started = true)
        write("打了", "吗")
        for (left in listOf("给打了", "给打", "给")) {
            backspaces(1)
            context(left, "")
        }
        journal.settle(undo)
        assertEquals(listOf(2L, 1L), undone)
        assertEquals(listOf(1L, 2L), journal.pending.map { it.seq })
    }

    // cut or a selection deleted: the context alone says which commits went whole
    @Test
    fun commitsDeletedWholeAsTheContextSaysAreTakenBack() {
        write("打了", "吗", "吧")
        context("给打了吗吧", "后面")
        // 吗 cut out of the middle
        context("给打了", "吧后面")
        journal.settle(undo)
        assertEquals(listOf(2L), undone)
        assertEquals(listOf(2L), journal.pending.map { it.seq })
    }

    @Test
    fun aFieldStartedOrClearedIsAnothers() {
        write("打了")
        context("打了", started = true)
        backspaces(2)
        journal.settle(undo)
        assertTrue(undone.isEmpty())
        write("打了")
        context("打了", cleared = true)
        backspaces(2)
        journal.settle(undo)
        assertTrue(undone.isEmpty())
    }

    @Test
    fun aContextThatDisagreesDropsAll() {
        write("打了")
        context("给打了")
        backspaces(2)
        journal.settle(undo)
        assertEquals(listOf(1L), undone)
        write("打了")
        context("别的")
        backspaces(2)
        journal.settle(undo)
        assertEquals(listOf(1L), undone)
    }

    // the cursor put back after 打了, 吗 after it unchanged: 打了 deleted there is taken back, and waits there
    @Test
    fun anEditAmongTheCommitsIsSeen() {
        context("给", "")
        write("打了", "吗")
        context("给打了", "吗")
        backspaces(2)
        context("给", "吗")
        journal.settle(undo)
        assertEquals(listOf(1L), undone)
        assertEquals(listOf(1L), journal.pending.map { it.seq })
        // the text after changed: not known where the cursor is
        context("给", "吧")
        assertTrue(journal.pending.isEmpty())
    }

    // the text after the commits never read: no edit among them is known
    @Test
    fun withoutTheTextAfterNoEditAmongThemIsKnown() {
        write("打了", "吗")
        context("给打了", "吗")
        backspaces(2)
        journal.settle(undo)
        assertTrue(undone.isEmpty())
    }

    // typed again in part: those not yet typed again wait for the next commit, if it went on to them
    @Test
    fun whatIsNotTypedAgainWaitsOnlyWhereTheCommitWentOn() {
        write("打了", "吗")
        backspaces(3)
        journal.settle(undo)
        journal.committed("点了", commit(3), typedAgain = 1, goesOn = true)
        assertEquals(listOf(2L), journal.pending.map { it.seq })
        journal.committed("吧", commit(4), typedAgain = 0, goesOn = false)
        assertTrue(journal.pending.isEmpty())
    }

    // its own context (a glide's word held back, written here): agrees; one it did not write leaves it unsure
    @Test
    fun theEnginesOwnContextIsWhatItWrote() {
        write("打了")
        journal.own("给打了")
        assertTrue(journal.verified)
        journal.own("给打了别的")
        assertFalse(journal.verified)
    }

    // review-1c M1: a field started empty, then text this session never wrote put after its commits (a
    // paste, an emoji from the panel, another keyboard's): no commit read as deleted
    @Test
    fun textAfterTheCommitsNotWrittenHereIsNoDeletion() {
        for (more in listOf("https://x", "😀")) {
            context("", "", started = true)
            write("给", "打了", "吗")
            context("给打了吗$more", "")
            journal.settle(undo)
            assertTrue(undone.isEmpty())
            backspaces(3)
            journal.settle(undo)
            assertTrue(undone.isEmpty())
        }
    }

    // an empty text before the cursor where commits are is not known (an editor that would not say): nothing read from it
    @Test
    fun anEmptyContextIsNotKnown() {
        context("", "", started = true)
        write("给", "打了", "吗")
        context("", "吗")
        journal.settle(undo)
        assertTrue(undone.isEmpty())
        assertFalse(journal.verified)
        backspaces(2)
        journal.settle(undo)
        assertTrue(undone.isEmpty())
    }

    // text before the cursor shorter than written, read in full: something written is gone, not the cursor put back
    @Test
    fun aContextShorterThanWrittenPutsNoCursorAmongTheCommits() {
        context("", "", started = true)
        write("给", "打了", "吗")
        // 给打了 selected and deleted, the cursor at the start: 吗 after it
        context("吗", "")
        journal.settle(undo)
        assertTrue(undone.isEmpty())
        backspaces(1)
        journal.settle(undo)
        assertTrue(undone.isEmpty())
    }

    // review-1c M2: 吗 selected (to copy it), then the cursor put back after it: nothing deleted
    @Test
    fun aSelectionIsNoCut() {
        context("", "", started = true)
        write("给", "打了", "吗")
        context("给打了", "", selected = true)
        journal.settle(undo)
        context("给打了吗", "")
        journal.settle(undo)
        assertTrue(undone.isEmpty())
        assertTrue(journal.verified)
        // selected and deleted by Backspace, the context read as it goes: 吗 taken back
        context("给打了", "", selected = true)
        backspaces(1)
        context("给打了", "")
        journal.settle(undo)
        assertEquals(listOf(3L), undone)
    }

    // review-1c M1: unverified, the field may hold text never seen: a context reads no deletion from it
    @Test
    fun anUnverifiedJournalReadsNoDeletion() {
        context("", "", started = true)
        write("给", "打了", "吗")
        journal.unverify()
        context("给打了", "")
        journal.settle(undo)
        assertTrue(undone.isEmpty())
        backspaces(2)
        journal.settle(undo)
        assertTrue(undone.isEmpty())
    }

    // review-1c M2: typed over a selection, what it took is not known
    @Test
    fun aCommitOverASelectionDropsAll() {
        context("", "", started = true)
        write("给", "打了")
        context("给", "", selected = true)
        journal.committed("吗", commit(3))
        // 打了 typed over: not deleted, nor 吗
        context("给吗", "")
        journal.settle(undo)
        assertTrue(undone.isEmpty())
        backspaces(2)
        journal.settle(undo)
        assertEquals(listOf(3L), undone)
    }

    // review-1c S1: a chat's box emptied by Backspace (an editor telling each), the text sent before remembered: 打了 taken back;
    // emptied with 打了 still written, as a message sent: nothing
    @Test
    fun aFieldEmptiedByBackspaceTakesBackWhatWent() {
        context("你好", cleared = true)
        write("打了")
        backspaces(2)
        context("你好", "", cleared = true)
        journal.settle(undo)
        assertEquals(listOf(1L), undone)
        write("吗")
        context("你好吗", "", cleared = true)
        journal.settle(undo)
        assertEquals(listOf(1L), undone)
        backspaces(1)
        journal.settle(undo)
        assertEquals(listOf(1L), undone)
    }

    // review-1c S7: one deleted before the cursor, then one after it (a forward delete): waiting in the order written
    @Test
    fun deletionsEitherSideOfTheCursorWaitInTheOrderWritten() {
        context("前", "", started = true)
        write("给", "打了", "吗")
        context("前给", "打了吗")
        backspaces(1)
        context("前", "打了吗")
        journal.settle(undo)
        context("前", "吗")
        journal.settle(undo)
        assertEquals(listOf(1L, 2L), undone)
        assertEquals(listOf(1L, 2L), journal.pending.map { it.seq })
    }

    // review-1c N5: a commit after what is left of one: deleting the rest later takes back only the newer
    @Test
    fun whatIsLeftOfACommitWrittenAfterIsNotTakenBack() {
        write("你好")
        backspaces(1)
        journal.committed("吗", commit(2))
        backspaces(2)
        journal.settle(undo)
        assertEquals(listOf(2L), undone)
    }

    // a long note: the host reads its last HOST_CHARS alone, and that is no shorter text than written (review-1c M1)
    @Test
    fun aContextReadAsFarAsTheHostReadsFitsTheCommits() {
        val long = "长".repeat(Action.Context.HOST_CHARS)
        context(long, "")
        write("打了", "吗")
        context((long + "打了吗").takeLast(Action.Context.HOST_CHARS), "")
        assertTrue(journal.verified)
        backspaces(3)
        journal.settle(undo)
        assertEquals(listOf(2L, 1L), undone)
    }

    // the head known, then more commits than kept: those let go are the head's, so the field still fits
    @Test
    fun theCommitsLetGoAreTheHeadsText() {
        context("你", "")
        repeat(CommitJournal.KEPT + 2) { journal.committed("字", commit(it + 1L)) }
        context("你" + "字".repeat(CommitJournal.KEPT + 2), "")
        assertTrue(journal.verified)
        backspaces(1)
        journal.settle(undo)
        assertEquals(listOf(CommitJournal.KEPT + 2L), undone)
    }

    // F2804: the cursor back before all those kept, a commit there is kept, not let go at once
    @Test
    fun aCommitBeforeAllThoseKeptIsKept() {
        context("你", "")
        repeat(CommitJournal.KEPT) { journal.committed("字", commit(it + 1L)) }
        context("你", "字".repeat(CommitJournal.KEPT))
        journal.committed("吗", commit(99))
        // the trimmed tail read as the host reads it after: still the field (review F3148)
        context("你吗", "字".repeat(CommitJournal.KEPT))
        assertTrue(journal.verified)
        backspaces(1)
        journal.settle(undo)
        assertEquals(listOf(99L), undone)
    }

    @Test
    fun aboutAsManyCommitsAsTheRecordCanTakeBackAreKept() {
        assertEquals(LearnLog.UNDOABLE, CommitJournal.KEPT)
        repeat(CommitJournal.KEPT + 2) { journal.committed("字", commit(it + 1L)) }
        backspaces(CommitJournal.KEPT + 2)
        journal.settle(undo)
        assertEquals(CommitJournal.KEPT, undone.size)
        assertTrue(journal.pending.isEmpty())
    }
}
