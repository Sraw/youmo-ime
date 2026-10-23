/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.assertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class VoiceChoiceTest {

    private val blocked = listOf("开饭")

    // 开饭 standing as a word of its own in each
    private val words = mapOf("开饭了" to intArrayOf(0, 2, 3), "开饭啦" to intArrayOf(0, 2, 3))

    @Test
    fun theModelPicksAmongWhatWasHeardAlike() {
        val language = FakeLanguage(likely = { texts -> FloatArray(texts.size) { if (texts[it] == "很佛系") 0f else -10f } })
        val heard = VoiceChoice.Heard(listOf("恨佛系。", "很佛系。"), floatArrayOf(-0.1f, -0.2f), "恨佛系。")
        assertEquals("很佛系。", choose(heard, emptyList(), language))
    }

    @Test
    fun withNothingToRankTheModelIsNotAsked() {
        val language = FakeLanguage()
        // English set apart: no candidate to rank
        assertEquals("好的", choose(VoiceChoice.Heard(listOf("好的", "hello"), FloatArray(0), "好的"), emptyList(), language))
        // no hypotheses, only its best
        assertEquals("好的", choose(VoiceChoice.Heard(emptyList(), FloatArray(0), "好的"), emptyList(), language))
        assertTrue(language.scored.isEmpty())
    }

    @Test
    fun withNoBlockedWordInAnyTheBestIsTyped() {
        val language = FakeLanguage(words)
        assertEquals("开放了", choose(heard("开放了", "开饭了"), emptyList(), language))
        assertEquals("开放了", choose(heard("开放了"), blocked, language))
        assertTrue(language.split.isEmpty())
    }

    @Test
    fun whatIsTypedInsteadOfABlockedWord() = assertRows(
        "the first without the blocked word standing is typed" to {
            assertEquals("开放了", choose(heard("开饭了", "开放了"), blocked, FakeLanguage(words)))
        },
        "before the search again one below with the word at all is passed over" to {
            // 开饭啦 below the first, though not split: passed over for 开放了
            assertEquals("开放了", choose(heard("开饭了", "开饭啦", "开放了"), blocked, FakeLanguage(mapOf("开饭了" to intArrayOf(0, 2, 3)))))
        },
        "the search again with it in none is typed" to {
            assertEquals("开放了", choose(heard("开饭了"), blocked, FakeLanguage(words)) { heard("开放了") })
        },
        "the search again still with it in each types its best" to {
            val again = VoiceChoice.Heard(listOf("开饭啦", "开饭了"), FloatArray(0), "开饭啦")
            assertEquals("开饭啦", choose(heard("开饭了"), blocked, FakeLanguage(words)) { again })
        },
        "the search again's best is typed as the recognizer writes it whichever others have the word" to {
            // a lower hypothesis with it standing: its best still typed as written, not as its first hypothesis
            val again = VoiceChoice.Heard(listOf("开放了", "开饭了"), FloatArray(0), "开放了。")
            assertEquals("开放了。", choose(heard("开饭了"), blocked, FakeLanguage(words)) { again })
        },
        "the search again's lower without it standing beats its best with it" to {
            // the search has nothing after it: 打开饭盒 (开饭 across two words) over 开饭啦 typed with it
            val split = words + ("打开饭盒" to intArrayOf(0, 2, 4))
            assertEquals("打开饭盒", choose(heard("开饭了"), blocked, FakeLanguage(split)) { heard("开饭啦", "打开饭盒") })
        },
    )

    @Test
    fun eachWithItTheSearchAgainsFirstWithoutItIsTyped() {
        var searched = 0
        val typed = choose(heard("开饭了"), blocked, FakeLanguage(words)) {
            searched++
            heard("开饭啦", "开放啦")
        }
        assertEquals("开放啦", typed)
        assertEquals(1, searched)
    }

    @Test
    fun theSearchAgainWithItInNoneIsTypedAsTheRecognizerWritesIt() {
        // its text, where it is not its first hypothesis as written
        val again = VoiceChoice.Heard(listOf("开放了"), FloatArray(0), "开放了。")
        assertEquals("开放了。", choose(heard("开饭了"), blocked, FakeLanguage(words)) { again })
        // and with no hypotheses, only its text
        assertEquals("开放啦", choose(heard("开饭了"), blocked, FakeLanguage(words)) { VoiceChoice.Heard(emptyList(), FloatArray(0), "开放啦") })
    }

    @Test
    fun theModelsPickWithItStandingGivesWayToTheRecognizersNext() {
        // the model likes 开饭了 best, then 开发了; without 开饭了 the recognizer's order decides
        val likely = mapOf("开饭了" to 0f, "开发了" to -1f)
        val language = FakeLanguage(words, likely = { texts -> FloatArray(texts.size) { likely[texts[it]] ?: -5f } })
        val heard = VoiceChoice.Heard(listOf("开放了", "开饭了", "开发了"), floatArrayOf(0f, 0f, 0f), "开放了")
        assertEquals("开饭了", choose(heard, emptyList(), language))
        assertEquals("开放了", choose(heard, blocked, language))
    }

    private fun heard(vararg nbest: String) = VoiceChoice.Heard(nbest.toList(), FloatArray(0), nbest.first())

    private fun choose(
        heard: VoiceChoice.Heard,
        blocked: List<String>,
        language: VoiceChoice.Language,
        searchBlocked: () -> VoiceChoice.Heard = { error("searched again") },
    ): String = now { VoiceChoice.choose(heard, blocked, language) { searchBlocked() } }

    // the fakes never suspend: it runs to its end at once
    private fun <T> now(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return checkNotNull(outcome).getOrThrow()
    }

    // splits as [words] has it, scores as [likely] does, and keeps what it was asked
    private class FakeLanguage(
        private val words: Map<String, IntArray> = emptyMap(),
        private val likely: (List<String>) -> FloatArray? = { null },
    ) : VoiceChoice.Language {
        val split = mutableListOf<List<String>>()
        val scored = mutableListOf<List<String>>()

        override suspend fun boundaries(texts: List<String>): Map<String, IntArray> {
            split.add(texts)
            return words
        }

        override suspend fun logProbs(texts: List<String>): FloatArray? {
            scored.add(texts)
            return likely(texts)
        }
    }
}
