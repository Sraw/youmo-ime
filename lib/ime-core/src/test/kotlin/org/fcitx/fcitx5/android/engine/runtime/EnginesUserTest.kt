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
import org.fcitx.fcitx5.android.input.voice.VoiceHotwords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the engine hands on of the user's typing: an English correction tapped back, the words voice listens for. */
class EnginesUserTest : EnginesFixture() {

    // the cursor put back after a correction, English offers the word typed, shown by the key the host sends
    @Test
    fun aTapAfterACorrectionOffersTheWordTypedForARecallToShow() {
        val engines = Engines(::load, null)
        // a word before it: a correction with nothing known before it is none to tap back to
        engines.type(InputMethodCatalog.ENGLISH, "fed")
        engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, ' '.code)
        engines.type(InputMethodCatalog.ENGLISH, "teh")
        assertEquals("the ", engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, ' '.code).commit)
        engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.RESET, 0)
        // told while pinyin is shown, English offers nothing, and so takes the next context
        assertFalse(engines.context(InputMethodCatalog.PINYIN, "I fed the "))
        assertTrue(engines.context(InputMethodCatalog.ENGLISH, "I fed the "))
        val shown = engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, Keyboard.RECALL)
        assertEquals(listOf("teh"), shown.candidates)
        val back = engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.PICK, 0)
        assertEquals("teh " to 4, back.commit to back.deleteBefore)
        engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.RESET, 0)
        assertFalse(engines.context(InputMethodCatalog.ENGLISH, "I fed teh "))
        engines.close()
    }

    // voice listens for 我的词 alone: a text committed once is none yet, twice it is
    @Test
    fun voiceListensForAWordOnlyOnceItIsTheUsers() {
        Engines(::load, null).use { engines ->
            fun pickedApart() {
                engines.type(InputMethodCatalog.PINYIN, "nihao")
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.candidates(InputMethodCatalog.PINYIN, 0, 20).indexOfFirst { it.text == "拟" })
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, 0)
                engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            }
            pickedApart()
            assertEquals("", VoiceHotwords.ofUser(engines.userWords()).hotwords)
            pickedApart()
            assertEquals("拟 好", VoiceHotwords.ofUser(engines.userWords()).hotwords)
            assertEquals("", VoiceHotwords.ofUser(engines.userWords()).withHabits(false).hotwords)
        }
    }

    @Test
    fun anUndoInEnglishKeepsTheWordTyped() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { engines ->
            engines.type(InputMethodCatalog.ENGLISH, "youmo")
            engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, ' '.code)
            val shown = engines.type(InputMethodCatalog.ENGLISH, "you").candidates
            val at = shown.indexOf("youmo")
            assertTrue("$shown", at >= 0)
            val done = engines.learning(InputMethodCatalog.ENGLISH, LearningOp.Delete(at, "youmo")) as LearningResult.Done
            assertFalse("youmo" in engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, Keyboard.AGAIN).candidates)
            assertEquals(LearningResult.Done(), engines.learning(InputMethodCatalog.ENGLISH, LearningOp.Undo(done.undo)))
            val again = engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, Keyboard.AGAIN)
            assertEquals("you", again.preedit)
            assertTrue("${again.candidates}", "youmo" in again.candidates)
        }
    }

    @Test
    fun aWordEnglishLearnedIsTypedInPinyin() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { engines ->
            assertFalse("GitHub" in engines.pinyin("github"))
            engines.inEnglish("GitHub")
            val learned = engines.pinyin("github")
            assertTrue("$learned", "GitHub" in learned)
            engines.forgetEnglishWords(listOf("GitHub"))
            assertFalse("GitHub" in engines.pinyin("github"))
        }
    }

    private fun Engines.inEnglish(word: String) {
        type(InputMethodCatalog.ENGLISH, word)
        onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, ' '.code)
    }

    private fun Engines.pinyin(keys: String): List<String> = type(InputMethodCatalog.PINYIN, keys).candidates.also {
        onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
    }

    @Test
    fun englishsWordsInPinyinGoWithTheHabitsAndTheSetting() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { engines ->
            engines.inEnglish("GitHub")
            assertTrue("GitHub" in engines.pinyin("github"))
            engines.learning(null, LearningOp.UseHabits(false))
            assertFalse("GitHub" in engines.pinyin("github"))
            engines.learning(null, LearningOp.UseHabits(true))
            assertTrue("GitHub" in engines.pinyin("github"))
            engines.settings = EngineSettings(latinWords = false)
            assertFalse("GitHub" in engines.pinyin("github"))
        }
    }

    @Test
    fun aWordEnglishsDictionaryHasComesInOnlyAdded() {
        english = englishWith("GitHub")
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { engines ->
            engines.inEnglish("GitHub")
            assertFalse("GitHub" in engines.pinyin("github"))
            assertTrue(engines.addEnglishWord("GitHub"))
            assertTrue("GitHub" in engines.pinyin("github"))
        }
    }

    @Test
    fun pinyinTypedInEnglishGoesAfterWhatItReads() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { engines ->
            repeat(3) { engines.inEnglish("nihao") }
            assertEquals("你好", engines.pinyin("nihao").first())
        }
    }

    @Test
    fun aWordLearnedTwoWaysIsOfferedOnce() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { engines ->
            engines.inEnglish("Github")
            repeat(2) { engines.inEnglish("GitHub") }
            val offered = engines.pinyin("github")
            assertTrue("$offered", "GitHub" in offered)
            assertFalse("$offered", "Github" in offered)
        }
    }

    @Test
    fun englishOffersNoNextWordInASearchField() {
        Engines(::load, MemoryFiles(), warmUps = inPlace).use { engines ->
            fun afterThe(flags: Int): List<String> {
                engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.RESET, 0)
                engines.context(InputMethodCatalog.ENGLISH, "", learnFlags = flags)
                engines.type(InputMethodCatalog.ENGLISH, "the")
                return engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, ' '.code).candidates
            }
            repeat(3) {
                afterThe(0)
                engines.type(InputMethodCatalog.ENGLISH, "youmo")
                engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, ' '.code)
            }
            assertTrue("youmo" in afterThe(0))
            assertEquals(emptyList<String>(), afterThe(Commit.NO_PREDICT))
        }
    }

    // a word after the text there is no sentence's start, and no next word shown
    @Test
    fun englishMadeAfterASearchFieldsContextTakesItsFlagsAlone() {
        Engines(::load, null, warmUps = inPlace).use { engines ->
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            engines.context(InputMethodCatalog.PINYIN, "abc ", learnFlags = Commit.NO_PREDICT)
            engines.type(InputMethodCatalog.ENGLISH, "the")
            val space = engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, ' '.code)
            assertEquals("the ", space.commit)
            assertEquals(emptyList<String>(), space.candidates)
        }
    }
}
