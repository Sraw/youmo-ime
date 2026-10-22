/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.handwriting

import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.KeyedRows
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.learn.Words
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A sentence put in and deleted at once is taken back; one written again for it is a pick there (U-03, U-29, F2655). */
class HandwritingSessionUndoTest {

    private val d = handwritingData
    private val learner = Learner(d)
    private val session = HandwritingSession(
        handwritingDecoder(learner.model), TextWords(d.model, d.wordIndex), user = learner.model,
        predictor = Predictor(d.model, d.vocabulary, d.dictionary, learner.model), habits = learner.habits,
    )

    private var pieces = 0

    private fun write(reading: InkReading): Snapshot {
        session.offer(reading, pieces++)
        return session.apply(Action.Ink)
    }

    // [reading] written, [text] picked of what it shows: what goes in
    private fun put(reading: InkReading, text: String): String {
        val shown = write(reading).candidates
        assertTrue("$text in $shown", text in shown)
        return session.apply(Action.Pick(shown.indexOf(text))).commit
    }

    // [n] Backspaces the app takes, then another action: what they deleted whole taken back
    private fun delete(n: Int) {
        repeat(n) { assertFalse(session.apply(Action.Backspace).handled) }
        session.apply(Action.Refine)
    }

    private fun code(vararg read: String) = Code(Kinds.HANDWRITING, KeyedRows.handwriting(read.toList()))
    private fun uses(code: Code, text: String) = learner.habits.uses(code, Words.of(text))
    private fun corrections(code: Code, text: String) = learner.habits.corrections(code, Words.of(text))

    private val tianRead = read("天", "人", "山")
    private val tian = code("天", "人", "山")

    @Test
    fun aSentenceDeletedWholeIsTakenBack() {
        assertEquals("人", put(tianRead, "人"))
        assertEquals(1, uses(tian, "人"))
        assertTrue("人" in learner.log.state.wordUses)
        delete(1)
        assertEquals(0, uses(tian, "人"))
        assertFalse("人" in learner.log.state.wordUses)
    }

    // switched to in an empty field (UAT HW-22/23): fcitx's reset of the session leaves the field
    // unknown, an empty context does not say it; told the field started (FieldPolicy.inputMethodChanged), it is known
    @Test
    fun switchedToInAnEmptyFieldTheFieldToldStartedLetsADeletionBeTakenBack() {
        session.apply(Action.Reset)
        session.apply(Action.Context("", learnFlags = 0))
        put(tianRead, "人")
        delete(1)
        // kept: the Backspace was not known to take it
        assertEquals(1, uses(tian, "人"))
        session.apply(Action.Reset)
        session.apply(Action.Context("", learnFlags = 0, started = true))
        put(tianRead, "人")
        delete(1)
        // the second taken back
        assertEquals(1, uses(tian, "人"))
    }

    @Test
    fun aSentenceDeletedInPartIsLearnedAsItWas() {
        write(read("淡", "谈", step = 0.3))
        write(read("话"))
        assertEquals("谈话", session.apply(Action.Pick(0)).commit)
        delete(1)
        assertEquals(1, uses(code("话"), "话"))
    }

    @Test
    fun writtenAgainInItsCandidatesIsAPickUnderTheDeletedCode() {
        assertEquals("天", put(tianRead, "天"))
        delete(1)
        // another ink, another three read: the character is still one of the deleted's
        assertEquals("人", put(read("人", "高", "话"), "人"))
        assertEquals(1, corrections(tian, "人"))
        assertEquals(0, uses(tian, "天"))
        assertEquals(1, uses(code("人", "高", "话"), "人"))
    }

    @Test
    fun writtenAgainOutsideThemIsLearnedAsWrittenAlone() {
        put(tianRead, "天")
        delete(1)
        assertEquals("高", put(read("高", "话"), "高"))
        assertEquals(0, uses(tian, "高"))
        assertEquals(1, uses(code("高", "话"), "高"))
    }

    // HW-22: 人 picked over 天, 天 put in by space and deleted, 人 picked again: two running, 人 first
    @Test
    fun aDeletedAutoCommitBreaksNoCorrectionsRunning() {
        put(tianRead, "人")
        write(tianRead)
        assertEquals("天", session.apply(Action.Select(0)).commit)
        delete(1)
        put(tianRead, "人")
        assertEquals("人", write(tianRead).candidates.first())
        // the same ink again: this commit's own pick is all there is
        assertEquals(2, corrections(tian, "人"))
    }

    // HW-23: 山 picked and deleted: not used, the order as before
    @Test
    fun aDeletedPickIsNoUse() {
        val before = write(tianRead).candidates
        // what was written dropped, the field as the host reads it
        session.apply(Action.Context("前", learnFlags = 0))
        put(tianRead, "山")
        delete(1)
        assertEquals(0, uses(tian, "山"))
        assertEquals(before, write(tianRead).candidates)
    }

    // review F3124: 谈话 deleted, 谈 written again and deleted too: 谈 again is 谈话's first again
    @Test
    fun aPartRetypeDeletedLeavesTheSentenceToTypeAgain() {
        write(read("淡", "谈", step = 0.3))
        write(read("话"))
        assertEquals("谈话", session.apply(Action.Pick(0)).commit)
        delete(2)
        put(read("谈", "人"), "谈")
        delete(1)
        assertEquals(0, uses(code("淡", "谈"), "谈"))
        assertEquals("谈", put(read("谈", "人"), "谈"))
        assertEquals(1, uses(code("淡", "谈"), "谈"))
    }

    // review F3110: a deleted commit's correction of one before it is no character of its own
    @Test
    fun aDeletedCorrectionIsNoCharacterTypedAgain() {
        put(tianRead, "天")
        delete(1)
        put(read("人", "高", "话"), "人")
        put(read("话", "山"), "话")
        delete(2)
        write(read("人", "高"))
        // another ink for 话: what is under 话's deleted code is the retype's alone
        write(read("话", "高"))
        assertEquals("人话", session.apply(Action.Pick(0)).commit)
        assertEquals(1, uses(code("话", "山"), "话"))
    }

    // review F2655 S: 谈话 deleted, written again as 谈, then 话: each a pick under its own deleted code
    @Test
    fun aSentenceWrittenAgainACharacterAtATime() {
        write(read("淡", "谈", step = 0.3))
        write(read("话"))
        assertEquals("谈话", session.apply(Action.Pick(0)).commit)
        delete(2)
        assertEquals(0, uses(code("话"), "话"))
        assertEquals("谈", put(read("谈", "人"), "谈"))
        assertEquals(1, uses(code("淡", "谈"), "谈"))
        assertEquals("话", put(read("话", "山"), "话"))
        assertEquals(1, uses(code("话"), "话"))
        assertEquals(0, corrections(code("话"), "话"))
    }

    @Test
    fun aDeletedPredictionIsTakenBack() {
        assertEquals("高", put(read("高"), "高"))
        val shown = session.apply(Action.Refine).candidates
        assertTrue(shown.toString(), "山" in shown)
        assertEquals("山", session.apply(Action.Pick(shown.indexOf("山"))).commit)
        assertTrue("高 山" in learner.used())
        delete(1)
        assertFalse("高 山" in learner.used())
        // the 高 before it stands
        assertTrue("高" in learner.used())
    }

    @Test
    fun aContextThatDoesNotFitDropsAll() {
        put(tianRead, "人")
        session.apply(Action.Context("别的", learnFlags = 0))
        delete(1)
        assertEquals(1, uses(tian, "人"))
    }

    @Test
    fun aPasswordKeepsNoJournal() {
        put(tianRead, "人")
        session.learning = false
        session.apply(Action.Context("人", learnFlags = 0))
        session.learning = true
        delete(1)
        assertEquals(1, uses(tian, "人"))
    }

    @Test
    fun olderThanTheTailIsNotTakenBack() {
        put(tianRead, "人")
        // written yesterday and over an hour ago, as PinyinSessionUndoTest has it
        learner.clock.minutes(15 * 60)
        delete(1)
        assertEquals(1, uses(tian, "人"))
    }

    @Test
    fun anotherDayIsNotTakenBack() {
        put(tianRead, "人")
        learner.clock.days(1)
        delete(1)
        assertEquals(1, learner.habits.corrections(tian, Words.of("人")))
    }

    @Test
    fun aKeyPassedOnLeavesTheFieldUnknown() {
        put(tianRead, "人")
        session.passedOn()
        delete(1)
        assertEquals(1, uses(tian, "人"))
    }
}
