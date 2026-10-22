/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.input.voice.VoiceHotwords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What the engine hands on of the user's typing: an English correction tapped back, the words voice listens for. */
class EnginesUserTest : EnginesFixture() {

    @get:Rule
    val folder = TemporaryFolder()

    // U-31: the cursor put back after a correction, English offers the word typed, shown by the key the host sends
    @Test
    fun aTapAfterACorrectionOffersTheWordTypedForARecallToShow() {
        val engines = Engines(::load, null)
        // a word before it: a correction with nothing known before it is none to tap back to (review-B S1)
        engines.type(Engines.ENGLISH, "fed")
        engines.onEvent(Engines.ENGLISH, EngineEvent.CHAR, ' '.code)
        engines.type(Engines.ENGLISH, "teh")
        assertEquals("the ", engines.onEvent(Engines.ENGLISH, EngineEvent.CHAR, ' '.code).commit)
        engines.onEvent(Engines.ENGLISH, EngineEvent.RESET, 0)
        // review-B M2: told while pinyin is shown, English offers nothing, and so takes the next context
        assertFalse(engines.context(Engines.PINYIN, "I fed the "))
        assertTrue(engines.context(Engines.ENGLISH, "I fed the "))
        val shown = engines.onEvent(Engines.ENGLISH, EngineEvent.CHAR, Keyboard.RECALL)
        assertEquals(listOf("teh"), shown.candidates)
        val back = engines.onEvent(Engines.ENGLISH, EngineEvent.PICK, 0)
        assertEquals("teh " to 4, back.commit to back.deleteBefore)
        engines.onEvent(Engines.ENGLISH, EngineEvent.RESET, 0)
        assertFalse(engines.context(Engines.ENGLISH, "I fed teh "))
        engines.close()
    }

    // U-30, D29: voice listens for 我的词 alone: a text committed once is none yet, twice it is
    @Test
    fun voiceListensForAWordOnlyOnceItIsTheUsers() {
        Engines(::load, null).use { engines ->
            fun pickedApart() {
                engines.type(Engines.PINYIN, "nihao")
                engines.onEvent(Engines.PINYIN, EngineEvent.PICK, engines.candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == "拟" })
                engines.onEvent(Engines.PINYIN, EngineEvent.PICK, 0)
                engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            }
            pickedApart()
            assertEquals("", VoiceHotwords.ofUser(engines.userWords()).hotwords)
            pickedApart()
            assertEquals("拟 好", VoiceHotwords.ofUser(engines.userWords()).hotwords)
            assertEquals("", VoiceHotwords.ofUser(engines.userWords(), habits = false).hotwords)
        }
    }

    // review-int S1: an undo in English keeps the session and the word typed, its candidates read anew
    @Test
    fun anUndoInEnglishKeepsTheWordTyped() {
        Engines(::load, folder.newFolder(), warmUps = inPlace).use { engines ->
            engines.type(Engines.ENGLISH, "youmo")
            engines.onEvent(Engines.ENGLISH, EngineEvent.CHAR, ' '.code)
            val shown = engines.type(Engines.ENGLISH, "you").candidates
            val at = shown.indexOf("youmo")
            assertTrue("$shown", at >= 0)
            val done = engines.learning(Engines.ENGLISH, LearningOp.Delete(at, "youmo")) as LearningResult.Done
            assertFalse("youmo" in engines.onEvent(Engines.ENGLISH, EngineEvent.CHAR, Keyboard.AGAIN).candidates)
            assertEquals(LearningResult.Done(), engines.learning(Engines.ENGLISH, LearningOp.Undo(done.undo)))
            val again = engines.onEvent(Engines.ENGLISH, EngineEvent.CHAR, Keyboard.AGAIN)
            assertEquals("you", again.preedit)
            assertTrue("${again.candidates}", "youmo" in again.candidates)
        }
    }

    // U-31 §3 (EN-24): a word English learned is typed in 全拼's 中英混输, as written there; forgotten, it goes
    @Test
    fun aWordEnglishLearnedIsTypedInPinyin() {
        Engines(::load, folder.newFolder(), warmUps = inPlace).use { engines ->
            assertFalse("GitHub" in engines.pinyin("github"))
            engines.inEnglish("GitHub")
            val learned = engines.pinyin("github")
            assertTrue("$learned", "GitHub" in learned)
            engines.forgetEnglishWords(listOf("GitHub"))
            assertFalse("GitHub" in engines.pinyin("github"))
        }
    }

    private fun Engines.inEnglish(word: String) {
        type(Engines.ENGLISH, word)
        onEvent(Engines.ENGLISH, EngineEvent.CHAR, ' '.code)
    }

    private fun Engines.pinyin(keys: String): List<String> = type(Engines.PINYIN, keys).candidates.also {
        onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
    }

    // review-uat-fixes S10: what EN-24 says of it, each
    @Test
    fun englishsWordsInPinyinGoWithTheHabitsAndTheSetting() {
        Engines(::load, folder.newFolder(), warmUps = inPlace).use { engines ->
            engines.inEnglish("GitHub")
            assertTrue("GitHub" in engines.pinyin("github"))
            // 不用我的输入习惯: none of English's learning either
            engines.learning(null, LearningOp.UseHabits(false))
            assertFalse("GitHub" in engines.pinyin("github"))
            engines.learning(null, LearningOp.UseHabits(true))
            assertTrue("GitHub" in engines.pinyin("github"))
            // 中英混输 off: no letters spelled
            engines.settings = EngineSettings(latinWords = false)
            assertFalse("GitHub" in engines.pinyin("github"))
        }
    }

    @Test
    fun aWordEnglishsDictionaryHasComesInOnlyAdded() {
        english = englishWith("GitHub")
        Engines(::load, folder.newFolder(), warmUps = inPlace).use { engines ->
            engines.inEnglish("GitHub")
            assertFalse("GitHub" in engines.pinyin("github"))
            assertTrue(engines.addEnglishWord("GitHub"))
            assertTrue("GitHub" in engines.pinyin("github"))
        }
    }

    // a string of pinyin typed in English (by a slip) does not go before what pinyin reads it as
    @Test
    fun pinyinTypedInEnglishGoesAfterWhatItReads() {
        Engines(::load, folder.newFolder(), warmUps = inPlace).use { engines ->
            repeat(3) { engines.inEnglish("nihao") }
            assertEquals("你好", engines.pinyin("nihao").first())
        }
    }

    // two spellings of one word learned: one offered, the one used more (review-uat-fixes N11)
    @Test
    fun aWordLearnedTwoWaysIsOfferedOnce() {
        Engines(::load, folder.newFolder(), warmUps = inPlace).use { engines ->
            engines.inEnglish("Github")
            repeat(2) { engines.inEnglish("GitHub") }
            val offered = engines.pinyin("github")
            assertTrue("$offered", "GitHub" in offered)
            assertFalse("$offered", "Github" in offered)
        }
    }

    // review-int S4: English offers no next word in a search field, as pinyin no 联想 (U-91)
    @Test
    fun englishOffersNoNextWordInASearchField() {
        Engines(::load, folder.newFolder(), warmUps = inPlace).use { engines ->
            fun afterThe(flags: Int): List<String> {
                engines.onEvent(Engines.ENGLISH, EngineEvent.RESET, 0)
                engines.context(Engines.ENGLISH, "", learnFlags = flags)
                engines.type(Engines.ENGLISH, "the")
                return engines.onEvent(Engines.ENGLISH, EngineEvent.CHAR, ' '.code).candidates
            }
            // youmo typed after the, the user's to follow it
            repeat(3) {
                afterThe(0)
                engines.type(Engines.ENGLISH, "youmo")
                engines.onEvent(Engines.ENGLISH, EngineEvent.CHAR, ' '.code)
            }
            assertTrue("youmo" in afterThe(0))
            assertEquals(emptyList<String>(), afterThe(Commit.NO_PREDICT))
        }
    }

    // review-int S5: English first used after the field's context (told pinyin's keyboard alone) takes its
    // flags, not an empty field: a word typed after the text there is no sentence's start, and no next word shown
    @Test
    fun englishMadeAfterASearchFieldsContextTakesItsFlagsAlone() {
        Engines(::load, null, warmUps = inPlace).use { engines ->
            engines.onEvent(Engines.PINYIN, EngineEvent.RESET, 0)
            engines.context(Engines.PINYIN, "abc ", learnFlags = Commit.NO_PREDICT)
            engines.type(Engines.ENGLISH, "the")
            val space = engines.onEvent(Engines.ENGLISH, EngineEvent.CHAR, ' '.code)
            assertEquals("the ", space.commit)
            assertEquals(emptyList<String>(), space.candidates)
        }
    }
}
