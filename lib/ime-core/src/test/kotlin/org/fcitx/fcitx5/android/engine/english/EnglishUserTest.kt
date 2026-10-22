/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.learn.FakeClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.EnCommit
import org.fcitx.fcitx5.android.engine.learn.LearnRecords
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnglishUserTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val data = EnglishDecoderTest.data()

    private val clock = FakeClock()
    private val file by lazy { File(dir.root, LearnLog.FILE) }
    private val log by lazy { LearnLog(file, clock, foldDaily = false).apply { open() } }
    private val user by lazy { EnglishUser({ log }) }

    @After
    fun close() = log.close()

    private fun EnglishSession.type(text: String): Snapshot = text.map { apply(Action.Key(it)) }.last()

    private fun EnglishSession.commit(word: String): String {
        type(word)
        return apply(Action.Key(' ')).commit
    }

    // the English commits written to the learning record, in order, as a start reads them
    private val recorded: List<EnCommit>
        get() {
            val reader = LearnRecords.Reader()
            val out = ArrayList<EnCommit>()
            LearnRecords.FORMAT.read(file.readBytes()) { type, input -> if (type != 0.toByte()) (reader.decode(type, input) as? EnCommit)?.let(out::add) }
            return out
        }

    @Test
    fun aPairTypedLeansOnWhatFollowedTheWordBefore() {
        user.commit(null, "the")
        user.commit("the", "cat")
        user.commit(null, "dog")
        assertTrue(user.probability("the", "cat") > user.probability(null, "cat"))
        assertEquals(0f, user.probability("the", "fish"))
        // a score the user's count raises, never past 0
        assertTrue(user.score(-5f, "the", "cat") > -5f)
        assertEquals(-5f, user.score(-5f, "the", "fish"))
        assertTrue(user.score(-0.001f, "the", "cat") <= 0f)
        assertEquals(setOf("cat"), user.followers("the"))
        assertEquals(3f, user.total)
    }

    // U-42: what is not typed for 30 typing days is forgotten; days nothing was typed do not count
    @Test
    fun aWordNotTypedForThirtyTypingDaysIsForgotten() {
        user.commit(null, "youmo")
        user.commit("youmo", "rocks")
        // a hundred calendar days with no typing: no typing day passed
        clock.days(100)
        user.commit(null, "the")
        assertEquals(1f, user.count("youmo"))
        repeat(29) {
            clock.days(1)
            user.commit(null, "the")
        }
        assertEquals(0f, user.count("youmo"))
        assertFalse("youmo" in user.words)
        assertEquals(emptySet<String>(), user.followers("youmo"))
        assertEquals(30f, user.count("the"))
    }

    // U-31, U-42: a word never corrected stays so however long untyped; one added counts as typed twice
    @Test
    fun whatIsNeverCorrectedStaysTillForgotten() {
        user.commit(null, "teh", how = EnCommit.REVERTED)
        user.add("youmo")
        assertEquals(EnglishUser.ADDED_COUNT, user.count("youmo"))
        assertEquals(setOf("youmo"), user.addedWords)
        repeat(40) {
            clock.days(1)
            user.commit(null, "the")
        }
        assertTrue(user.neverCorrected("teh"))
        assertTrue(user.neverCorrected("youmo"))
        assertEquals(0f, user.count("teh"))
        assertEquals(EnglishUser.ADDED_COUNT, user.count("youmo"))
        assertEquals(setOf("teh", "youmo"), user.neverCorrectedWords)
        user.forgetAll(listOf("teh", "youmo", "never"))
        assertFalse(user.neverCorrected("teh"))
        assertEquals(emptySet<String>(), user.addedWords)
        assertEquals(0f, user.count("youmo"))
    }

    // U-31 恢复: corrected again, the uses and pairs kept; as read again from the record (EN_CORRECT)
    @Test
    fun aWordRestoredIsCorrectedAgainItsUsesKept() {
        user.commit(null, "the")
        user.commit("the", "teh", how = EnCommit.REVERTED)
        user.add("youmo")
        user.restoreCorrection(listOf("teh", "youmo", "the"))
        assertFalse(user.neverCorrected("teh"))
        assertEquals(1f, user.count("teh"))
        assertEquals(setOf("teh"), user.followers("the"))
        // added and never typed: nothing of it left
        assertEquals(emptySet<String>(), user.addedWords)
        assertFalse("youmo" in user.words)
        val session = EnglishSession(data, user = user)
        assertEquals("the ", session.commit("teh"))
        log.close()
        val again = LearnLog(file, clock).apply { open() }
        val read = EnglishUser({ again })
        assertFalse(read.neverCorrected("teh"))
        assertEquals(setOf("teh"), read.followers("the"))
        assertEquals(emptySet<String>(), read.neverCorrectedWords)
        again.close()
    }

    // D11: it takes a correction taken back, not a count
    @Test
    fun aWordTypedTwiceIsCorrectedStill() {
        user.commit(null, "nad")
        user.commit(null, "nad")
        assertFalse(user.neverCorrected("nad"))
    }

    @Test
    fun forgottenAWordGoesWithEveryPairItIsIn() {
        user.commit(null, "the")
        user.commit("the", "cat")
        user.forget("cat")
        assertEquals(0f, user.count("cat"))
        assertEquals(emptySet<String>(), user.followers("the"))
        assertEquals(1f, user.total)
    }

    // PLAN §2.2: English learns into the record, read back as written by a start
    @Test
    fun whatIsLearnedIsReadAgainFromTheLearningRecord() {
        user.commit(null, "the")
        user.commit("the", "youmo", how = EnCommit.REVERTED)
        user.commit(null, "gone")
        user.forget("gone")
        user.add("Steve")
        log.close()
        val again = LearnLog(file, clock).apply { open() }
        val read = EnglishUser({ again })
        assertEquals(1f, read.count("youmo"))
        assertTrue(read.neverCorrected("youmo"))
        assertEquals(setOf("youmo"), read.followers("the"))
        assertEquals(0f, read.count("gone"))
        assertEquals(setOf("Steve"), read.addedWords)
        again.close()
    }

    @Test
    fun anUndoTakesTheCommitBack() {
        user.commit(null, "the")
        val seq = checkNotNull(user.commit("the", "cat"))
        assertTrue(user.undo(seq))
        assertEquals(0f, user.count("cat"))
        assertEquals(emptySet<String>(), user.followers("the"))
        assertFalse(user.undo(seq))
    }

    // review-int-records S2: an undo takes back only a pair its commit counted, none in a field that learns none
    @Test
    fun anUndoOfAnUnpairedCommitLeavesThePairLearned() {
        user.commit(null, "hello")
        user.commit("hello", "world")
        val seq = checkNotNull(user.commit("hello", "world", flags = Commit.UNPAIRED))
        assertTrue(user.undo(seq))
        assertEquals(setOf("world"), user.followers("hello"))
    }

    @Test
    fun offNothingIsLearnedNorKnown() {
        val off = EnglishUser.OFF
        assertNull(off.commit(null, "youmo"))
        off.add("youmo")
        assertFalse(off.learns)
        assertEquals(emptySet<String>(), off.words)
        val session = EnglishSession(data, user = off)
        session.commit("youmo")
        assertEquals(0f, off.count("youmo"))
    }

    // each word an EN_COMMIT (design-1a §1): the word before it, the keys typed, how it came, the field's flags
    @Test
    fun eachWordCommittedIsAnEnglishCommitOfTheRecord() {
        val session = EnglishSession(data, user = user)
        session.apply(Action.Context("I fed ", learnFlags = Commit.NO_PAIRS))
        session.commit("teh")
        session.commit("cat")
        val shown = session.type("nad").candidates
        session.apply(Action.Pick(shown.indexOf("nad")))
        assertEquals(
            listOf(
                Triple("fed", "the", "teh") to EnCommit.AUTOCORRECTED,
                Triple("the", "cat", "cat") to EnCommit.TYPED,
                Triple("cat", "nad", "nad") to EnCommit.REVERTED,
            ),
            recorded.map { Triple(it.prev, it.word, it.typed) to it.how },
        )
        assertTrue(recorded.all { it.flags == Commit.NO_PAIRS })
        // an address field's: no pairs
        assertEquals(emptySet<String>(), user.followers("the"))
    }

    @Test
    fun aWordPickedIsRecordedPicked() {
        val session = EnglishSession(data, user = user)
        session.apply(Action.Context(""))
        val shown = session.type("th").candidates
        session.apply(Action.Pick(shown.indexOf("the")))
        assertEquals(listOf("the" to EnCommit.PICKED), recorded.map { it.word to it.how })
    }

    @Test
    fun aWordPickedOverItsCorrectionIsNeverCorrected() {
        val session = EnglishSession(data, user = user)
        // nad, a word, but one the far likelier and is a slip from
        assertEquals("and ", session.commit("nad"))
        session.apply(Action.Context(""))
        session.type("nad")
        session.apply(Action.Pick(1))
        assertTrue(user.neverCorrected("nad"))
        assertEquals("nad ", session.commit("nad"))
    }

    // the user's, kept by the host for the next session, as a start reads it from the log
    @Test
    fun aCorrectionTakenBackOnceIsNeverMadeAgain() {
        val first = EnglishSession(data, user = user)
        first.commit("teh")
        first.apply(Action.Backspace)
        assertEquals("teh ", EnglishSession(data, user = user).commit("teh"))
    }

    @Test
    fun aWordTheDataLacksIsOfferedByItsKeysOnceTyped() {
        val session = EnglishSession(data, user = user)
        session.type("youmo")
        session.apply(Action.Pick(0))
        assertTrue("youmo" in session.type("you").candidates)
        session.apply(Action.Reset)
        assertEquals("youmo", session.type("youmo").candidates.first())
    }

    @Test
    fun theWordsTypedAfterAWordAreOfferedAfterItAgain() {
        val session = EnglishSession(data, user = user)
        session.apply(Action.Context(""))
        session.commit("the")
        session.commit("youmo")
        session.apply(Action.Context("I like "))
        val after = session.commit("the")
        assertEquals("the ", after)
        val offered = session.candidates(0, 10).map { it.text }
        assertTrue("youmo among $offered", "youmo" in offered)
    }

    @Test
    fun aLongPressForgetsAWordCounted() {
        val session = EnglishSession(data, user = user)
        session.commit("youmo")
        val shown = session.type("you").candidates
        val at = shown.indexOf("youmo")
        assertEquals(setOf(Offer.FORGET), session.offers(at))
        assertEquals(emptySet<Offer>(), session.offers(shown.indexOf("you")))
        assertFalse("youmo" in session.apply(Action.Forget(at)).candidates)
        assertEquals(0f, user.count("youmo"))
    }

    // as the data has its words: hello, not Hello; London and I as written
    @Test
    fun aCapitalASentencesStartAccountsForIsNotLearned() {
        val session = EnglishSession(data, user = user)
        session.apply(Action.Context(""))
        session.commit("Hello")
        session.apply(Action.Context(""))
        session.commit("London")
        session.apply(Action.Context(""))
        session.commit("Youmo")
        assertEquals(setOf("hello", "London", "youmo"), user.words)
        // inside a sentence a capital is the word's
        session.apply(Action.Context("I met "))
        session.commit("Steve")
        assertEquals(1f, user.count("Steve"))
    }

    @Test
    fun aCorrectionTakenBackIsNotCounted() {
        val session = EnglishSession(data, user = user)
        session.apply(Action.Context("I fed "))
        session.commit("teh")
        assertEquals(1f, user.count("the"))
        session.apply(Action.Backspace)
        assertEquals(0f, user.count("the"))
        assertEquals(1f, user.count("teh"))
        assertTrue(user.neverCorrected("teh"))
        // committed as shown again: the revert was its use, not counted twice
        assertEquals("teh ", session.apply(Action.Key(' ')).commit)
        assertEquals(1f, user.count("teh"))
        assertEquals(listOf("the" to EnCommit.AUTOCORRECTED, "teh" to EnCommit.REVERTED), recorded.map { it.word to it.how })
        // a capital at a sentence's start: theirs inside one too
        val start = EnglishSession(data, user = EnglishUser())
        start.apply(Action.Context(""))
        start.commit("Teh")
        start.apply(Action.Backspace)
        start.apply(Action.Context("I fed "))
        assertEquals("teh ", start.commit("teh"))
    }

    // the word typed kept over its correction, as by a correction taken back
    @Test
    fun theWordTypedPickedOverItsCorrectionIsTheUsersAtOnce() {
        val session = EnglishSession(data, user = user)
        val shown = session.type("teh").candidates
        assertEquals("the", shown.first())
        session.apply(Action.Pick(shown.indexOf("teh")))
        assertTrue(user.neverCorrected("teh"))
    }

    // U-31: a tap back right after a correction offers the word typed in its place; picked, it is never corrected again
    @Test
    fun aTapBackAfterACorrectionOffersTheWordTypedInItsPlace() {
        val session = EnglishSession(data, user = user)
        session.apply(Action.Context("I fed "))
        session.commit("teh")
        session.commit("cat")
        assertEquals(1f, user.count("the"))
        // the cursor put after "the " again, by a tap
        session.apply(Action.Reset)
        val shown = session.apply(Action.Context("I fed the "))
        assertEquals(listOf("teh"), shown.candidates)
        assertTrue(shown.predicting)
        val back = session.apply(Action.Pick(0))
        assertEquals("teh ", back.commit)
        assertEquals(4, back.deleteBefore)
        assertEquals(0f, user.count("the"))
        assertTrue(user.neverCorrected("teh"))
        assertEquals(Triple("fed", "teh", EnCommit.REVERTED), recorded.last().let { Triple(it.prev, it.word, it.how) })
        // taken back once: not offered again there
        session.apply(Action.Reset)
        assertEquals(emptyList<String>(), session.apply(Action.Context("I fed the ")).candidates)
    }

    // R11 F2650: a word picked is the user's own, no correction: a backspace or a tap after it takes nothing back
    @Test
    fun aWordPickedIsNoCorrectionToTakeBack() {
        val session = EnglishSession(data, user = user)
        session.apply(Action.Context("I said "))
        session.type("hel")
        val at = session.candidates(0, 10).map { it.text }.indexOf("hello")
        assertTrue(at >= 0)
        assertEquals("hello ", session.apply(Action.Pick(at)).commit)
        val back = session.apply(Action.Backspace)
        assertEquals(0, back.deleteBefore)
        assertFalse(user.neverCorrected("hel"))
        assertEquals(EnCommit.PICKED, recorded.last().how)
        session.apply(Action.Reset)
        assertEquals(emptyList<String>(), session.apply(Action.Context("I said hello ")).candidates)
    }

    // R11 F2650: on the nine keys, a word other than the first picked and a backspace after it leave the keys alone
    @Test
    fun onTheNineKeysAWordPickedIsNoCorrectionToTakeBack() {
        val session = EnglishSession(data, user = user)
        session.apply(Action.Context("I said "))
        "43".forEach { session.apply(Action.NineKey(it, false)) }
        val words = session.candidates(0, 10).map { it.text }
        assertTrue(words.size > 1)
        assertEquals("${words[1]} ", session.apply(Action.Pick(1)).commit)
        val back = session.apply(Action.Backspace)
        assertEquals("", back.preedit)
        assertEquals(0, back.deleteBefore)
        assertTrue(recorded.none { it.how == EnCommit.REVERTED })
    }

    // R11 F2740: after the field cleared itself (a chat sent), a correction at its new start is found by a tap
    @Test
    fun aCorrectionAfterTheFieldClearedItselfIsOfferedBack() {
        val session = EnglishSession(data, user = user)
        session.apply(Action.Context("I fed the cat", cleared = true))
        assertEquals("the ", session.commit("teh"))
        session.apply(Action.Reset)
        assertEquals(listOf("teh"), session.apply(Action.Context("the ")).candidates)
    }

    @Test
    fun aTapRightAfterTheCorrectedWordTakesItWithoutItsSpace() {
        val session = EnglishSession(data, user = user)
        session.apply(Action.Context("I fed "))
        session.commit("teh")
        session.apply(Action.Reset)
        assertEquals(listOf("teh"), session.apply(Action.Context("I fed the")).candidates)
        val back = session.apply(Action.Pick(0))
        assertEquals("teh", back.commit)
        assertEquals(3, back.deleteBefore)
    }

    // review R11 F3073/F3085: the word typed offered back goes with the offer when anything but its pick
    // comes: no longer shown, nor committed by a pick after it
    @Test
    fun anOfferBackLeftByAnotherActIsNoLongerShown() {
        val session = EnglishSession(data, user = user)
        session.apply(Action.Context("I fed "))
        session.commit("teh")
        session.apply(Action.Reset)
        assertEquals(listOf("teh"), session.apply(Action.Context("I fed the ")).candidates)
        assertEquals(emptyList<String>(), session.apply(Action.NextPage).candidates)
        assertEquals("", session.apply(Action.Pick(0)).commit)
        assertFalse(user.neverCorrected("teh"))
        assertEquals(1f, user.count("the"))
    }

    @Test
    fun theSameWordElsewhereIsNoCorrectionToTakeBack() {
        val session = EnglishSession(data, user = user)
        session.apply(Action.Context("I fed "))
        session.commit("teh")
        session.apply(Action.Reset)
        assertEquals(emptyList<String>(), session.apply(Action.Context("I saw the ")).candidates)
        // a key after the offer goes on as typed, the offer gone
        session.apply(Action.Reset)
        session.apply(Action.Context("I fed the "))
        assertEquals("cat ", session.commit("cat"))
        assertEquals(1f, user.count("the"))
    }

    // review-B M1: an incognito field learns nothing, yet a backspace right after a correction takes it back
    @Test
    fun withoutLearningBackspaceStillTakesTheCorrectionBack() {
        val session = EnglishSession(data, user = user)
        session.learning = false
        session.apply(Action.Context("I fed "))
        assertEquals("the ", session.commit("teh"))
        val back = session.apply(Action.Backspace)
        assertEquals("teh", back.preedit)
        assertFalse(user.neverCorrected("teh"))
        assertEquals(emptyList<EnCommit>(), recorded)
    }

    // review-B S4: a word committed is counted at once, asked next with nothing else read between
    // (the session makes its own copy of the words again at its next key)
    @Test
    fun aWordCommittedChangesTheWordsAtOnce() {
        user.commit(null, "youmo")
        assertTrue("youmo" in user.words)
        assertEquals(1f, user.count("youmo"))
        user.commit(null, "youmo")
        assertEquals(2f, user.count("youmo"))
    }

    // a field that asks nothing be learned: no correction kept to take back
    @Test
    fun withoutLearningNoCorrectionIsOfferedBack() {
        val session = EnglishSession(data, user = user)
        session.learning = false
        session.apply(Action.Context("I fed "))
        session.commit("teh")
        session.apply(Action.Reset)
        assertEquals(emptyList<String>(), session.apply(Action.Context("I fed the ")).candidates)
    }

    // an arrow or a tab leaves the word as it is, maybe half typed
    @Test
    fun aWordLeftAsItIsIsNotLearned() {
        val session = EnglishSession(data, user = user)
        session.type("hel")
        assertEquals("hel", session.apply(Action.CommitRaw).commit)
        assertEquals(0f, user.total)
    }

    @Test
    fun withoutLearningNothingIsCounted() {
        val session = EnglishSession(data, user = user).apply { learning = false }
        session.commit("youmo")
        session.commit("teh")
        session.apply(Action.Backspace)
        assertEquals(0f, user.total)
        assertEquals(emptyList<EnCommit>(), recorded)
    }
}
