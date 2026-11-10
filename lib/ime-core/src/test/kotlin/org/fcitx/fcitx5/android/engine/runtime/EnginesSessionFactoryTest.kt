/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the sessions made between two [Engines.invalidate]s share (F2 S8, review A 2.3): one
 * ChineseLanguage for handwriting and the tables, one list of coarse words for every Chinese session,
 * pinyin's too; made anew once what they were made of changes.
 */
class EnginesSessionFactoryTest : EnginesFixture() {

    private fun Engines.resetEach(vararg ims: String) = ims.forEach { onEvent(it, EngineEvent.RESET, 0) }

    @Test
    fun handwritingAndEveryTableShareOneLanguageAndEverySessionOneListOfCoarseWords() {
        Engines(::load, null, warmUps = inPlace).use { engines ->
            engines.resetEach(InputMethodCatalog.PINYIN, InputMethodCatalog.T9, InputMethodCatalog.SHUANGPIN, InputMethodCatalog.HANDWRITING, InputMethodCatalog.WUBI)
            assertEquals(1, engines.factory.languagesMade)
            assertEquals(1, engines.factory.coarseMade)
            val language = engines.factory.language()
            // asked again, the one made
            assertSame(language, engines.factory.language())
            assertEquals(1, engines.factory.languagesMade)
        }
    }

    @Test
    fun invalidateHasEverySessionMadeAnewAndWhatTheyShare() {
        Engines(::load, null, warmUps = inPlace).use { engines ->
            assertEquals("ni", engines.type(InputMethodCatalog.PINYIN, "ni").preedit)
            engines.resetEach(InputMethodCatalog.WUBI)
            val language = engines.factory.language()
            engines.invalidate()
            // no session till the next key: none to ask of
            assertTrue(engines.candidates(InputMethodCatalog.PINYIN, 0, 5).isEmpty())
            // what was typed dropped, a new session made
            assertEquals("h", engines.type(InputMethodCatalog.PINYIN, "h").preedit)
            engines.resetEach(InputMethodCatalog.WUBI)
            assertNotSame(language, engines.factory.language())
            assertEquals(2, engines.factory.languagesMade)
            assertEquals(2, engines.factory.coarseMade)
        }
    }

    // a word added drops the sessions (UserLexicon): the coarse words then leave it out, made anew
    @Test
    fun aWordAddedMakesTheCoarseWordsAnew() {
        Engines(::load, null, warmUps = inPlace).use { engines ->
            engines.resetEach(InputMethodCatalog.PINYIN)
            assertEquals(1, engines.factory.coarseMade)
            assertTrue(engines.addWord("你好", "ni hao"))
            engines.resetEach(InputMethodCatalog.PINYIN, InputMethodCatalog.T9)
            assertEquals(2, engines.factory.coarseMade)
        }
    }

    // a long press's delete of a word added by hand keeps the sessions (UserLexicon.unAdd): handwriting
    // and the tables keep the list they were made with, a pinyin session made after makes its own
    // without the word held back, as each did before the split
    @Test
    fun aWordAddedByHandDeletedLeavesTheSharedListAndANewPinyinSessionMakesItsOwn() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { engines ->
            assertTrue(engines.addWord("拟好", "ni hao"))
            assertEquals("拟好", engines.type(InputMethodCatalog.PINYIN, "nihao").candidates.first())
            engines.resetEach(InputMethodCatalog.WUBI)
            val language = engines.factory.language()
            assertSame(language.coarse, engines.factory.lastCoarse)
            val at = engines.candidates(InputMethodCatalog.PINYIN, 0, 10).map { it.text }.indexOf("拟好")
            assertTrue(engines.learning(InputMethodCatalog.PINYIN, LearningOp.Delete(at, "拟好")) is LearningResult.Done)
            assertTrue(engines.userWords(UserWord.Kind.ADDED).isEmpty())
            // no session made anew: the tables' language and its list as they were
            engines.resetEach(InputMethodCatalog.T9)
            assertSame(language, engines.factory.language())
            assertEquals(2, engines.factory.coarseMade)
            assertNotSame(language.coarse, engines.factory.lastCoarse)
            // what the user sees of it: pinyin typed again offers it no more as the word added (above 你好), only as
            // the sentence 拟 and 好 make, below
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            assertEquals("你好", engines.type(InputMethodCatalog.PINYIN, "nihao").candidates.first())
        }
    }

    // U-93: habits off, the sessions read a fresh user model, and their language is another; on again, another still
    @Test
    fun turningTheHabitsOffOrOnMakesTheLanguageAnew() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { engines ->
            engines.resetEach(InputMethodCatalog.WUBI)
            val on = engines.factory.language()
            engines.useHabits = false
            engines.resetEach(InputMethodCatalog.WUBI)
            val off = engines.factory.language()
            assertNotSame(on, off)
            assertNotSame(on.user, off.user)
            engines.useHabits = true
            engines.resetEach(InputMethodCatalog.WUBI)
            assertNotSame(off, engines.factory.language())
            assertEquals(3, engines.factory.languagesMade)
        }
    }

    // the host's flags, kept for the engines it makes anew in the same field (review B 3.8)
    @Test
    fun theFieldsFlagsAreTheHostsOneCopy() {
        val flags = FieldFlags()
        Engines(::load, null, warmUps = inPlace, fieldFlags = flags).use { it.context(InputMethodCatalog.PINYIN, "", learnFlags = 3) }
        assertEquals(3, flags.value)
        Engines(::load, null, warmUps = inPlace, fieldFlags = flags).use { it.context(InputMethodCatalog.PINYIN, "", learnFlags = 0) }
        assertEquals(0, flags.value)
    }

    // and read by them: an engines made anew in a search field, told no context of its own, offers no next word
    @Test
    fun anEnginesMadeAnewInTheFieldKeepsItsFlags() {
        val dir = MemoryFiles()
        val flags = FieldFlags()
        fun Engines.afterThe(): List<String> {
            onEvent(InputMethodCatalog.ENGLISH, EngineEvent.RESET, 0)
            type(InputMethodCatalog.ENGLISH, "the")
            return onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, ' '.code).candidates
        }
        Engines(::load, dir, warmUps = inPlace, fieldFlags = flags).use { engines ->
            engines.context(InputMethodCatalog.ENGLISH, "", learnFlags = 0)
            // youmo typed after the, the user's to follow it
            repeat(3) {
                engines.afterThe()
                engines.type(InputMethodCatalog.ENGLISH, "youmo")
                engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, ' '.code)
            }
            engines.context(InputMethodCatalog.ENGLISH, "", learnFlags = Commit.NO_PREDICT)
        }
        Engines(::load, dir, warmUps = inPlace, fieldFlags = flags).use { assertFalse("youmo" in it.afterThe()) }
        // the same record in a field of its own flags: the word follows
        Engines(::load, dir, warmUps = inPlace, fieldFlags = FieldFlags()).use { assertTrue("youmo" in it.afterThe()) }
    }
}
