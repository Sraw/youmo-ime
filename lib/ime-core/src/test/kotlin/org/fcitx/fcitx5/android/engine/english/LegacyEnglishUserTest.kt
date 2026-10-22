/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.english

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executor

/** The older builds' English log, read now only to convert it into the learning record. */
class LegacyEnglishUserTest {

    @get:Rule
    val dir = TemporaryFolder()

    @Test
    fun pastItsLimitEveryCountIsHalvedAndTheRarestGo() {
        val user = LegacyEnglishUser(limit = 10f)
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
        val user = LegacyEnglishUser()
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
        val user = LegacyEnglishUser()
        EnglishUserStore(file, user).use { store ->
            store.open()
            user.learn(null, "the")
            user.learn("the", "youmo", LegacyEnglishUser.LEARNED)
            user.learn(null, "gone")
            user.forget("gone")
        }
        val again = LegacyEnglishUser()
        EnglishUserStore(file, again).use { it.open() }
        assertEquals(LegacyEnglishUser.LEARNED, again.count("youmo"))
        assertEquals(setOf("youmo"), again.followers("the"))
        assertEquals(0f, again.count("gone"))
        assertEquals(user.total, again.total)
    }

    // every count, a pair's as "prev word"
    private fun counts(user: LegacyEnglishUser): Map<String, Float> =
        HashMap<String, Float>().also { out -> user.forEachCount { prev, word, count -> out[if (prev == null) word else "$prev $word"] = count } }

    @Test
    fun whatIsLearnedIsReadAgainAfterACompaction() {
        val file = dir.root.resolve("english.user")
        val user = LegacyEnglishUser()
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
        val again = LegacyEnglishUser()
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
        val user = LegacyEnglishUser()
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
        val again = LegacyEnglishUser()
        EnglishUserStore(file, again).use { it.open() }
        assertEquals(counts(user), counts(again))
        assertEquals(setOf("youmo", "cat"), again.followers("the"))
        assertEquals(1f, again.count("dog"))
        assertEquals(user.total, again.total)
    }

    @Test
    fun aWordAddedStaysTheUsersHoweverOftenHalvedTillForgotten() {
        val user = LegacyEnglishUser(limit = 10f)
        user.add("youmo")
        user.learn(null, "typed", LegacyEnglishUser.LEARNED)
        repeat(40) { user.learn(null, "often") }
        // halved past the limit till a word typed as often is gone
        assertEquals(0f, user.count("typed"))
        assertEquals(LegacyEnglishUser.LEARNED, user.count("youmo"))
        assertTrue(user.learned("youmo"))
        assertEquals(setOf("youmo"), user.addedWords)
        user.forget("youmo")
        assertEquals(emptySet<String>(), user.addedWords)
        assertEquals(0f, user.count("youmo"))
    }

    @Test
    fun aWordAddedIsReadBackAddedFromItsLogAndAfterACompaction() {
        val file = dir.root.resolve("english.user")
        val user = LegacyEnglishUser()
        EnglishUserStore(file, user).use { store ->
            store.open()
            user.add("youmo")
            user.learn(null, "the")
        }
        val again = LegacyEnglishUser()
        EnglishUserStore(file, again).use { store ->
            store.open()
            assertEquals(setOf("youmo"), again.addedWords)
            assertEquals(LegacyEnglishUser.LEARNED, again.count("youmo"))
            store.compact()
        }
        val compacted = LegacyEnglishUser()
        EnglishUserStore(file, compacted).use { it.open() }
        assertEquals(setOf("youmo"), compacted.addedWords)
        assertEquals(counts(user), counts(compacted))
        assertEquals(user.total, compacted.total)
    }

    @Test
    fun wordsForgottenTogetherGoWithEveryPairTheyAreInAndFromTheLogCompacted() {
        val file = dir.root.resolve("english.user")
        val user = LegacyEnglishUser()
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
        val again = LegacyEnglishUser()
        EnglishUserStore(file, again).use { it.open() }
        assertEquals(counts(user), counts(again))
        assertEquals(setOf("the", "sat"), again.words)
        assertEquals(user.total, again.total)
    }
}
