/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

import org.fcitx.fcitx5.android.engine.english.EnglishDecoderTest
import org.fcitx.fcitx5.android.engine.english.EnglishPhrases
import org.fcitx.fcitx5.android.engine.english.EnglishSession
import org.fcitx.fcitx5.android.engine.english.EnglishUser
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.LogHabits
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.session.sessionTestData
import org.fcitx.fcitx5.android.engine.table.TableOptions
import org.fcitx.fcitx5.android.engine.table.TableSession
import org.fcitx.fcitx5.android.engine.table.dictionary
import org.fcitx.fcitx5.android.engine.table.learning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * A1: a custom phrase typed by its key on every input method and keyboard that has them, one
 * phrase each: offered, picked as it is and never learned, and deleted from the phrases by a long
 * press. On keys of letter groups the key is typed as its letters' keys.
 */
@RunWith(Parameterized::class)
class PhrasesOnEveryKeyboardTest(private val name: String, private val keyboard: KeyboardCase) {

    /**
     * An input method on a keyboard: its session over the phrases learning into the log, the keys
     * typing a key's letters, and the letters of an ordinary word it learns when picked.
     */
    class KeyboardCase(val make: (PhraseBook, LearnLog) -> Session, val type: (Session, String) -> Unit, val word: String = "ni")

    @Test
    fun aPhraseIsOfferedByItsKeyPickedAsItIsAndDeleted() {
        val saved = ArrayList<CustomPhrases>()
        val book = PhraseBook(CustomPhrases.parse("$KEY,2=$PHRASE\n")) { saved += it }
        val log = LearnLog(null).apply { open() }
        val written = ArrayList<LearnRecord>()
        log.appended = { written += it }
        val session = keyboard.make(book, log)
        keyboard.type(session, KEY)
        val shown = session.candidates(0, 20).map { it.text }
        val at = shown.indexOf(PHRASE)
        assertTrue("$name: $shown", at >= 0)
        assertTrue(name, Offer.DELETE_PHRASE in session.offers(at))
        assertEquals(name, PHRASE, session.apply(Action.Pick(at)).commit)
        // text, no word: nothing of it written to the learning record
        assertEquals(name, emptyList<LearnRecord>(), written)
        // typed again, deleted from the keyboard: gone from the file and the list
        keyboard.type(session, KEY)
        val again = session.candidates(0, 20).map { it.text }
        // the long press's 从自定义词组中删除 arrives as an unpin (LearningMenu), on a phrase its deletion
        session.apply(Action.Unpin(again.indexOf(PHRASE)))
        assertEquals(name, "", saved.last().all.joinToString { it.toString() })
        assertTrue(name, PHRASE !in session.candidates(0, 20).map { it.text })
        // the control: a word picked the same way is written, so the empty record above is the phrase's doing
        val other = keyboard.make(book, log)
        keyboard.type(other, keyboard.word)
        other.apply(Action.Pick(0))
        assertTrue(name, written.isNotEmpty())
    }

    companion object {
        const val KEY = "qm"
        const val PHRASE = "张三 138 0000 0000"

        private val day = { GregorianCalendar(2026, Calendar.OCTOBER, 31) }

        private fun pinyin(segmenter: Segmenter, spell: Boolean) = { book: PhraseBook, log: LearnLog ->
            PinyinSession(sessionTestData(), segmenter, spell = spell, prediction = false, phraseBook = book, now = day, learnTo = LearnTo(LogHabits({ log })))
        }

        private val english = { book: PhraseBook, log: LearnLog ->
            EnglishSession(EnglishDecoderTest.data(), user = EnglishUser { log }, phrases = EnglishPhrases(book, day))
        }

        // each letter as the key of [letters] that types it
        private fun keyed(letters: NineKeyLetters) = { s: Session, key: String -> key.forEach { s.apply(Action.Key(letters.key(it))) } }

        private val letters = { s: Session, key: String -> key.forEach { s.apply(Action.Key(it)) } }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun keyboards(): List<Array<Any>> = listOf(
            "全拼 26 键" to KeyboardCase(pinyin(PinyinSegmenter(), spell = false), letters),
            "拼音 14 键" to KeyboardCase(pinyin(T9Segmenter(abbreviations = false, letters = NineKeyLetters.G14), spell = true), keyed(NineKeyLetters.G14)),
            "九键拼音" to KeyboardCase(pinyin(T9Segmenter(abbreviations = false), spell = true), keyed(NineKeyLetters.STANDARD)),
            "双拼 26 键" to KeyboardCase(pinyin(ShuangpinSegmenter(ShuangpinScheme.ZIRANMA), spell = true), letters),
            "双拼 18 键" to KeyboardCase(pinyin(GroupedShuangpinSegmenter(ShuangpinScheme.ZIRANMA, NineKeyLetters.G18), spell = true), keyed(NineKeyLetters.G18)),
            "九键双拼" to KeyboardCase(pinyin(GroupedShuangpinSegmenter(ShuangpinScheme.ZIRANMA, NineKeyLetters.NINE_SP), spell = true), keyed(NineKeyLetters.NINE_SP)),
            "五笔" to KeyboardCase({ book, log -> TableSession(dictionary("w 人", "wq 你"), TableOptions.WUBI, language = learning(habits = LogHabits({ log })), phrases = book, clock = day) }, letters, word = "wq"),
            "英文 26 键" to KeyboardCase(english, letters, word = "the"),
            "英文九键" to KeyboardCase(
                english,
                { s, key -> key.forEach { s.apply(Action.NineKey(NineKeyLetters.STANDARD.key(it), false)) } },
                word = "the",
            ),
        ).map { (name, keyboard) -> arrayOf(name, keyboard) }
    }
}
