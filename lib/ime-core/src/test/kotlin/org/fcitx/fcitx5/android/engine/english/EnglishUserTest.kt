/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executor

class EnglishUserTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val data = EnglishDecoderTest.data()

    private fun EnglishSession.type(text: String): Snapshot = text.map { apply(Action.Key(it)) }.last()

    private fun EnglishSession.commit(word: String): String {
        type(word)
        return apply(Action.Key(' ')).commit
    }

    @Test
    fun aPairTypedLeansOnWhatFollowedTheWordBefore() {
        val user = EnglishUser()
        user.learn(null, "the")
        user.learn("the", "cat")
        user.learn(null, "dog")
        assertTrue(user.probability("the", "cat") > user.probability(null, "cat"))
        assertEquals(0f, user.probability("the", "fish"))
        // a score the user's count raises, never past 0
        assertTrue(user.score(-5f, "the", "cat") > -5f)
        assertEquals(-5f, user.score(-5f, "the", "fish"))
        assertTrue(user.score(-0.001f, "the", "cat") <= 0f)
    }

    @Test
    fun pastItsLimitEveryCountIsHalvedAndTheRarestGo() {
        val user = EnglishUser(limit = 10f)
        repeat(8) { user.learn(null, "often") }
        user.learn(null, "once")
        user.learn("once", "after")
        user.learn(null, "more")
        assertEquals(4f, user.count("often"))
        assertTrue(user.total <= 10f)
        assertEquals(0.5f, user.count("once"))
        assertEquals(setOf("after"), user.followers("once"))
    }

    @Test
    fun forgottenAWordGoesWithEveryPairItIsIn() {
        val user = EnglishUser()
        user.learn(null, "the")
        user.learn("the", "cat")
        user.forget("cat")
        assertEquals(0f, user.count("cat"))
        assertEquals(emptySet<String>(), user.followers("the"))
        assertEquals(1f, user.total)
    }

    @Test
    fun whatIsLearnedIsReadAgainFromItsLog() {
        val file = dir.root.resolve("english.user")
        val user = EnglishUser()
        EnglishUserStore(file, user).use { store ->
            store.open()
            user.learn(null, "the")
            user.learn("the", "youmo", EnglishUser.LEARNED)
            user.learn(null, "gone")
            user.forget("gone")
        }
        val again = EnglishUser()
        EnglishUserStore(file, again).use { it.open() }
        assertEquals(EnglishUser.LEARNED, again.count("youmo"))
        assertEquals(setOf("youmo"), again.followers("the"))
        assertEquals(0f, again.count("gone"))
        assertEquals(user.total, again.total)
    }

    // every count, a pair's as "prev word"
    private fun counts(user: EnglishUser): Map<String, Float> =
        HashMap<String, Float>().also { out -> user.forEachCount { prev, word, count -> out[if (prev == null) word else "$prev $word"] = count } }

    @Test
    fun whatIsLearnedIsReadAgainAfterACompaction() {
        val file = dir.root.resolve("english.user")
        val user = EnglishUser()
        EnglishUserStore(file, user, compactAt = 100).use { store ->
            store.open()
            repeat(20) {
                user.learn(null, "the")
                user.learn("the", "youmo")
            }
            user.learn(null, "gone")
            user.forget("gone")
        }
        // forty records of some twenty bytes, yet the log holds only the counts and what came after them
        assertTrue(file.length().toString(), file.length() < 300)
        val again = EnglishUser()
        EnglishUserStore(file, again).use { it.open() }
        assertEquals(counts(user), counts(again))
        assertEquals(setOf("youmo"), again.followers("the"))
        assertEquals(user.total, again.total)
    }

    /** As the engine keeps it: the compaction an append makes due written by another executor, here when told. */
    @Test
    fun countsCompactedInTheBackgroundComeBackWithWhatWasLearnedMeanwhile() {
        val file = dir.root.resolve("english.user")
        val deferred = ArrayList<Runnable>()
        val user = EnglishUser()
        EnglishUserStore(file, user, background = Executor { deferred += it }, compactAt = 100).use { store ->
            store.open()
            // capped, or a store that never handed the counts on would hang here
            var learned = 0
            while (deferred.isEmpty() && learned < 100) {
                user.learn(null, "the")
                user.learn("the", "youmo")
                learned++
            }
            assertEquals(1, deferred.size)
            user.learn("the", "cat")
            deferred.single().run()
            user.learn(null, "dog")
        }
        val again = EnglishUser()
        EnglishUserStore(file, again).use { it.open() }
        assertEquals(counts(user), counts(again))
        assertEquals(setOf("youmo", "cat"), again.followers("the"))
        assertEquals(1f, again.count("dog"))
        assertEquals(user.total, again.total)
    }

    @Test
    fun aWordAddedStaysTheUsersHoweverOftenHalvedTillForgotten() {
        val user = EnglishUser(limit = 10f)
        user.add("youmo")
        user.learn(null, "typed", EnglishUser.LEARNED)
        repeat(40) { user.learn(null, "often") }
        // halved past the limit till a word typed as often is gone
        assertEquals(0f, user.count("typed"))
        assertEquals(EnglishUser.LEARNED, user.count("youmo"))
        assertTrue(user.learned("youmo"))
        assertEquals(setOf("youmo"), user.addedWords)
        user.forget("youmo")
        assertEquals(emptySet<String>(), user.addedWords)
        assertEquals(0f, user.count("youmo"))
    }

    @Test
    fun aWordAddedIsReadBackAddedFromItsLogAndAfterACompaction() {
        val file = dir.root.resolve("english.user")
        val user = EnglishUser()
        EnglishUserStore(file, user).use { store ->
            store.open()
            user.add("youmo")
            user.learn(null, "the")
        }
        val again = EnglishUser()
        EnglishUserStore(file, again).use { store ->
            store.open()
            assertEquals(setOf("youmo"), again.addedWords)
            assertEquals(EnglishUser.LEARNED, again.count("youmo"))
            store.compact()
        }
        val compacted = EnglishUser()
        EnglishUserStore(file, compacted).use { it.open() }
        assertEquals(setOf("youmo"), compacted.addedWords)
        assertEquals(counts(user), counts(compacted))
        assertEquals(user.total, compacted.total)
    }

    @Test
    fun wordsForgottenTogetherGoWithEveryPairTheyAreInAndFromTheLogCompacted() {
        val file = dir.root.resolve("english.user")
        val user = EnglishUser()
        EnglishUserStore(file, user).use { store ->
            store.open()
            user.learn(null, "the")
            user.learn("the", "cat")
            user.learn("cat", "sat")
            user.learn(null, "dog")
            user.learn("dog", "cat")
            user.forgetAll(listOf("cat", "dog", "never"))
            assertEquals(setOf("the", "sat"), user.words)
            assertEquals(emptySet<String>(), user.followers("the"))
            assertEquals(emptySet<String>(), user.followers("cat"))
            assertEquals(2f, user.total)
            store.compact()
        }
        val again = EnglishUser()
        EnglishUserStore(file, again).use { it.open() }
        assertEquals(counts(user), counts(again))
        assertEquals(setOf("the", "sat"), again.words)
        assertEquals(user.total, again.total)
    }

    @Test
    fun aWordCommittedTwiceIsNeverCorrected() {
        val user = EnglishUser()
        val session = EnglishSession(data, user = user)
        // nad, a word, but one the far likelier and is a slip from
        assertEquals("and ", session.commit("nad"))
        session.apply(Action.Context(""))
        session.type("nad")
        session.apply(Action.Pick(1))
        session.type("nad")
        session.apply(Action.Pick(1))
        assertTrue(user.learned("nad"))
        assertEquals("nad ", session.commit("nad"))
    }

    // the user's, kept by the host for the next session, as a start reads it from the log
    @Test
    fun aCorrectionTakenBackIsTheUsersInTheNextSession() {
        val user = EnglishUser()
        val first = EnglishSession(data, user = user)
        first.commit("teh")
        first.apply(Action.Backspace)
        assertEquals("teh ", EnglishSession(data, user = user).commit("teh"))
    }

    @Test
    fun aWordTheDataLacksIsOfferedByItsKeysOnceTyped() {
        val user = EnglishUser()
        val session = EnglishSession(data, user = user)
        session.type("youmo")
        session.apply(Action.Pick(0))
        assertTrue("youmo" in session.type("you").candidates)
        session.apply(Action.Reset)
        assertEquals("youmo", session.type("youmo").candidates.first())
    }

    @Test
    fun theWordsTypedAfterAWordAreOfferedAfterItAgain() {
        val user = EnglishUser()
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
        val user = EnglishUser()
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
        val user = EnglishUser()
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
        val user = EnglishUser()
        val session = EnglishSession(data, user = user)
        session.apply(Action.Context("I fed "))
        session.commit("teh")
        assertEquals(1f, user.count("the"))
        session.apply(Action.Backspace)
        assertEquals(0f, user.count("the"))
        assertEquals(EnglishUser.LEARNED, user.count("teh"))
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
        val user = EnglishUser()
        val session = EnglishSession(data, user = user)
        val shown = session.type("teh").candidates
        assertEquals("the", shown.first())
        session.apply(Action.Pick(shown.indexOf("teh")))
        assertTrue(user.learned("teh"))
    }

    // an arrow or a tab leaves the word as it is, maybe half typed
    @Test
    fun aWordLeftAsItIsIsNotLearned() {
        val user = EnglishUser()
        val session = EnglishSession(data, user = user)
        session.type("hel")
        assertEquals("hel", session.apply(Action.CommitRaw).commit)
        assertEquals(0f, user.total)
    }

    @Test
    fun withoutLearningNothingIsCounted() {
        val user = EnglishUser()
        val session = EnglishSession(data, user = user).apply { learning = false }
        session.commit("youmo")
        session.commit("teh")
        session.apply(Action.Backspace)
        assertEquals(0f, user.total)
    }
}
