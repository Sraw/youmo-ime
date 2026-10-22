/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.learn.Habits
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The journal every session keeps the same way (U-03, F2655): which calls see the record as it is
 * now (settle, backspace, a context, a commit followed, one of its own) and which the journal as
 * it stands (unverify, a password's context, a commit), as CommitRecorder split them.
 */
class FieldJournalTest {

    private var learns = true
    private var rewrites = 0L
    private val undone = ArrayList<Commit>()

    private val habits = object : Habits by Habits.OFF {
        override val learns get() = this@FieldJournalTest.learns
        override val rewrites get() = this@FieldJournalTest.rewrites
        override fun undo(commit: Commit): Boolean {
            undone += commit
            return true
        }
    }

    private val traditional = mapOf('說' to '说', '話' to '话')
    private var clears = 0
    private val journal = FieldJournal({ habits }, { t -> t.map { traditional[it] ?: it }.joinToString("") }) { clears++ }

    private var seq = 0L
    private fun commit() = Commit(++seq, 0, 0, 0, 0, emptyList())

    private fun deleteAndSettle(n: Int) {
        repeat(n) { journal.backspace() }
        journal.settle()
    }

    @Test
    fun aCommitDeletedWholeIsTakenBackAtTheNextAction() {
        val c = commit()
        journal.committed("好", c, learning = true)
        journal.backspace()
        assertTrue(undone.isEmpty())
        journal.settle()
        assertEquals(listOf(c), undone)
        assertEquals(listOf(c), journal.pending)
    }

    @Test
    fun learningTurnedOffMidRunDropsIt() {
        journal.committed("好", commit(), learning = true)
        journal.backspace()
        learns = false
        journal.settle()
        assertTrue(undone.isEmpty())
        assertTrue(clears > 0)
    }

    @Test
    fun aRewrittenRecordDropsItBeforeTheSettle() {
        journal.committed("好", commit(), learning = true)
        journal.backspace()
        rewrites++
        journal.settle()
        assertTrue(undone.isEmpty())
    }

    // the raw journal's contract, which no session reaches (each calls before() or pending first,
    // which see a rewrite): committed() looks for none, so a rewrite just before it, seen at the next
    // settle, drops that commit with what was before; the commit after is kept as the record now has it
    @Test
    fun aCommitRightAfterARewriteGoesWithItTheNextIsKept() {
        rewrites++
        val c = commit()
        journal.committed("好", c, learning = true)
        deleteAndSettle(1)
        // the rewrite came before the commit was written: dropped with what was before it
        assertTrue(undone.isEmpty())
        val d = commit()
        journal.committed("好", d, learning = true)
        deleteAndSettle(1)
        assertEquals(listOf(d), undone)
    }

    @Test
    fun aPasswordsContextKeepsNothing() {
        journal.committed("好", commit(), learning = true)
        journal.told(Action.Context("好", learnFlags = 0), learning = false)
        deleteAndSettle(1)
        assertTrue(undone.isEmpty())
    }

    @Test
    fun aCommitWhereNothingIsLearnedKeepsNothing() {
        journal.committed("好", commit(), learning = true)
        journal.committed("的", null, learning = false)
        deleteAndSettle(2)
        assertTrue(undone.isEmpty())
    }

    // a glide's word held back: written as its own, the context the engine gave itself then fits, still verified
    @Test
    fun aWordOfItsOwnIsWrittenAndItsOwnContextFits() {
        val c = commit()
        journal.committed("好", c, learning = true)
        journal.own("的", null)
        journal.told(Action.Context("好的"), learning = true)
        assertTrue(journal.journal.verified)
        deleteAndSettle(2)
        assertEquals(listOf(c), undone)
    }

    // the engine's own context of what it never wrote: unverified, and a Backspace then drops all
    @Test
    fun anOwnContextNotWrittenUnverifies() {
        journal.committed("好", commit(), learning = true)
        journal.told(Action.Context("好的"), learning = true)
        assertFalse(journal.journal.verified)
        deleteAndSettle(1)
        assertTrue(undone.isEmpty())
    }

    @Test
    fun aKeyPassedOnLeavesTheFieldUnknown() {
        journal.committed("好", commit(), learning = true)
        journal.passedOn()
        deleteAndSettle(1)
        assertTrue(undone.isEmpty())
    }

    // review F2655 S: a traditional table's text, written and read simplified alike
    @Test
    fun traditionalTextIsComparedSimplified() {
        val c = commit()
        journal.committed("說話", c, learning = true)
        journal.told(Action.Context("說話", learnFlags = 0), learning = true)
        assertTrue(journal.journal.verified)
        deleteAndSettle(2)
        assertEquals(listOf(c), undone)
    }

    @Test
    fun aCommitFollowedIsNothingToTakeBackUnlessGivenIts() {
        journal.followed("好", learning = true)
        deleteAndSettle(1)
        assertTrue(undone.isEmpty())
        val c = commit()
        journal.followed("好", learning = true, commit = c)
        deleteAndSettle(1)
        assertEquals(listOf(c), undone)
    }
}
