/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The packaged models through the streaming recognizer on a recording (an emulator has no microphone). Needs the voice pack. */
class VoiceEngineTest {

    private val test = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun aRecordingIsRecognizedAsItStreamsBothTimes() {
        val texts = hear(VoiceHotwords.Words.NONE)
        assertTrue(texts.toString(), texts.isNotEmpty())
        // the first syllable comes back 开饭, 开放 or 菜饭 as the chunk falls; accuracy is the eval sets' to measure.
        // No polish here, and digits are only the polish model's to write: the numbers stay as heard
        val live = VoiceLive()
        texts.forEach(live::closed)
        val typed = live.finish().orEmpty()
        assertEquals(typed, 2, Regex("早上九点至下午五点").findAll(typed).count())
    }

    @Test
    fun theUsersWordsAreListenedFor() {
        // not 开放 for a hotword: the pack has 开放时间, and a hotword ending inside a longer one loses that one's bonus
        val word = UserWord("早上", "zao shang", UserWord.Kind.ADDED)
        val with = topScore(VoiceHotwords.ofUser(listOf(word)))
        val without = topScore(VoiceHotwords.Words.NONE)
        assertTrue("$with with, $without without", with > without)
    }

    @Test
    fun theLastWordsGraphIsTakenAgainForTheSameWordsOnly() {
        // the recognizer keeps the last graph: other words (苹果, not said) get their own, without 早上's bonus
        val said = VoiceHotwords.ofUser(listOf(UserWord("早上", "zao shang", UserWord.Kind.ADDED)))
        val unsaid = VoiceHotwords.ofUser(listOf(UserWord("苹果", "ping guo", UserWord.Kind.ADDED)))
        val first = topScore(said)
        assertEquals(first, topScore(said), SAME_SCORE)
        val other = topScore(unsaid)
        assertTrue("$other other, $first first", other < first)
        assertEquals(first, topScore(said), SAME_SCORE)
    }

    @Test
    fun aBlockedWordStandingAloneIsNotWritten() {
        // blocked words are split char by char, so another hypothesis wins
        val blocked = hear(VoiceHotwords.Words(hotwords = "", blocked = "开 放")) { IntArray(it.length + 1) { i -> i } }
        assertTrue(blocked.toString(), blocked.isNotEmpty())
        assertTrue(blocked.toString(), blocked.none { "开放" in it })
        assertTrue(blocked.toString(), blocked.all { "时间早上九点" in it || "时间早上9点" in it })
    }

    @Test
    fun theBlockInTheSearchTakesTheWordWhereverItIs() {
        val blocked = VoiceHotwords.Words(hotwords = "", blocked = "开 放")
        val recognizer = VoiceEngine.acquire().recognizer
        try {
            val text = result(recognizer, VoiceEngine.stream(recognizer, blocked), wav("zh.wav")).text
            assertTrue(text, "开放" !in text)
            assertTrue(text, "时间" in text)
        } finally {
            VoiceEngine.release()
        }
    }

    @Test
    fun theBlockInTheSearchTakesAWordWithinTheSentenceToo() {
        // not at the sentence start: its tokens there are blocked as at the start
        val blocked = VoiceHotwords.Words(hotwords = "", blocked = "早 上")
        val recognizer = VoiceEngine.acquire().recognizer
        try {
            val text = result(recognizer, VoiceEngine.stream(recognizer, blocked), wav("zh.wav")).text
            assertTrue(text, "早上" !in text)
            assertTrue(text, "时间" in text && "下午" in text)
        } finally {
            VoiceEngine.release()
        }
    }

    @Test
    fun theBeamsOtherHypothesesComeBackBestFirst() {
        val recognizer = VoiceEngine.acquire().recognizer
        try {
            val result = result(recognizer, VoiceEngine.stream(recognizer, VoiceHotwords.Words.NONE), wav("zh.wav"))
            assertTrue(result.nbest.toList().toString(), result.nbest.size > 1)
            assertEquals(result.text, result.nbest[0])
            assertEquals(result.nbest.size, result.nbestScores.size)
            assertTrue(result.nbestScores.toList().toString(), result.nbestScores.toList().zipWithNext().all { (a, b) -> a >= b })
        } finally {
            VoiceEngine.release()
        }
    }

    @Test
    fun theLanguageModelPicksAmongWhatWasHeardAlike() {
        val samples = wav("zh.wav")
        val recognizer = VoiceEngine.acquire().recognizer
        try {
            val heard = result(recognizer, VoiceEngine.stream(recognizer, VoiceHotwords.Words.NONE), samples)
            val candidates = VoiceRerank.candidates(
                VoiceRerank.distinct(heard.nbest.indices.map { VoiceRerank.Hypothesis(heard.nbest[it], heard.nbestScores[it]) }),
            )
            assertTrue(candidates.toString(), candidates.size > 1)
            val last = VoiceRerank.bare(candidates.last().text)
            var asked = emptyList<String>()
            val sure = object : VoiceChoice.Language {
                override suspend fun boundaries(texts: List<String>) = emptyMap<String, IntArray>()
                override suspend fun logProbs(texts: List<String>): FloatArray {
                    asked = texts
                    return FloatArray(texts.size) { if (texts[it] == last) 0f else -1000f }
                }
            }
            val text = runBlocking {
                VoiceEngine.choose(heard, VoiceHotwords.Words.NONE, sure)
            }
            assertTrue(asked.toString(), candidates.all { VoiceRerank.bare(it.text) in asked })
            assertEquals(candidates.last().text, text)
        } finally {
            VoiceEngine.release()
        }
    }

    private fun topScore(words: VoiceHotwords.Words): Float {
        val recognizer = VoiceEngine.acquire().recognizer
        try {
            return result(recognizer, VoiceEngine.stream(recognizer, words), wav("zh.wav")).nbestScores[0]
        } finally {
            VoiceEngine.release()
        }
    }

    private fun hear(hotwords: VoiceHotwords.Words, split: (String) -> IntArray = { IntArray(0) }): List<String> {
        val recognizer = VoiceEngine.acquire().recognizer
        try {
            val segments = segments(recognizer, VoiceEngine.stream(recognizer, hotwords), wav("zh.wav"))
            return runBlocking { segments.map { VoiceEngine.choose(it, hotwords, language(split)) } }
        } finally {
            VoiceEngine.release()
        }
    }

    /** Decoded whole as the listener's last segment: tail padded, input finished. */
    private fun result(recognizer: OnlineRecognizer, stream: OnlineStream, samples: FloatArray): OnlineRecognizerResult {
        try {
            stream.acceptWaveform(samples, VoiceEngine.SAMPLE_RATE)
            stream.acceptWaveform(FloatArray(VoiceEngine.TAIL_PADDING), VoiceEngine.SAMPLE_RATE)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            return recognizer.getResult(stream)
        } finally {
            stream.release()
        }
    }

    /** Through the listener's own loop ([VoiceSegments]) so that loop is tested on the real recognizer. */
    private fun segments(recognizer: OnlineRecognizer, stream: OnlineStream, samples: FloatArray): List<OnlineRecognizerResult> {
        val out = mutableListOf<OnlineRecognizerResult>()
        try {
            val segment = VoiceSegments(
                VoiceListener.Recognition(recognizer, stream),
                VoiceEngine.SAMPLE_RATE,
                choose = { result -> result.text.also { out += result } },
                live = {},
                closed = {},
                VoiceQuiet(),
            )
            runBlocking {
                var i = 0
                while (i < samples.size) {
                    val end = minOf(i + VoiceEngine.WINDOW, samples.size)
                    segment.accept(samples.copyOfRange(i, end))
                    i = end
                }
                segment.finish(FloatArray(VoiceEngine.TAIL_PADDING))
            }
        } finally {
            stream.release()
        }
        return out
    }

    private fun wav(name: String): FloatArray {
        val bytes = test.assets.open(name).use { it.readBytes() }
        val pcm = ByteBuffer.wrap(bytes, WAV_HEADER, bytes.size - WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(pcm.remaining()) { pcm.get(it) / 32768f }
    }

    companion object {
        private const val WAV_HEADER = 44
        // another graph is off by VoiceHotwords.SCORE a token
        private const val SAME_SCORE = 1e-3f
    }

    private fun language(split: (String) -> IntArray) = object : VoiceChoice.Language {
        override suspend fun boundaries(texts: List<String>) = texts.associateWith(split)
        override suspend fun logProbs(texts: List<String>) = null
    }
}
