/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.engine.host.Engines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The models as packaged, through the VAD and the recognizer as the panel uses them, on a
 * recording in place of the microphone (an emulator has none): the sherpa-onnx test sentence,
 * twice, with silence around, to be cut into its two stretches and each recognized.
 */
class VoiceEngineTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val test = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun aRecordingIsCutAtItsPausesAndEachStretchRecognized() {
        val texts = hear(VoiceHotwords.Words.NONE)
        assertEquals(texts.toString(), 2, texts.size)
        for (text in texts) {
            // 开饭时间早上九点至下午五点, the numbers made digits. The first syllable comes back 开饭,
            // 开放 or 菜饭 as 0.1 s more or less of lead-in is given, and the beam search ends it
            // with a full stop or not: how well it is heard is the evaluation sets' to measure
            val typed = VoiceText.clean(text).orEmpty()
            assertTrue(typed, typed.contains("时间早上9点至下午5点"))
        }
    }

    @Test
    fun theUsersWordsAreListenedFor() {
        // the pack's hotwords read from the assets, the user's put in for each stretch: 早上, said,
        // scored the higher for the bonus. (It was 开饭 made of the first stretch's 开放, but with
        // a beam of 16 the search keeps 开放 heard, rightly. Not 开放 either: the pack has 开放时间,
        // and a hotword ending where a longer one goes on takes the search back to the start of
        // the graph, the longer one's bonus lost.)
        val word = Engines.UserWord("早上", "zao shang", Engines.UserWord.Kind.ADDED)
        val with = topScore(VoiceHotwords.ofUser(listOf(word)))
        val without = topScore(VoiceHotwords.Words.NONE)
        assertTrue("$with with, $without without", with > without)
    }

    @Test
    fun aBlockedWordStandingAloneIsNotWritten() {
        // 开放, said in each stretch, blocked: split char by char, it stands as words wherever it
        // is, and another hypothesis or the search again wins
        val blocked = hear(VoiceHotwords.Words(hotwords = "", blocked = "开 放")) { IntArray(it.length + 1) { i -> i } }
        assertEquals(blocked.toString(), 2, blocked.size)
        assertTrue(blocked.toString(), blocked.none { "开放" in it })
        // the rest still heard
        assertTrue(blocked.toString(), blocked.all { "时间早上九点" in it || "时间早上9点" in it })
    }

    @Test
    fun aBlockedWordWithinALongerOneIsWritten() {
        // each stretch one word: 开放 within it, not standing, kept as heard
        val heard = hear(VoiceHotwords.Words.NONE)
        val within = hear(VoiceHotwords.Words(hotwords = "", blocked = "开 放")) { intArrayOf(0, it.length) }
        assertTrue(heard.toString(), heard.any { "开放" in it })
        assertEquals(heard, within)
    }

    @Test
    fun theBlockInTheSearchTakesTheWordWhereverItIs() {
        val blocked = VoiceHotwords.Words(hotwords = "", blocked = "开 放")
        val recognizer = VoiceEngine.acquire(app.assets)
        try {
            val text = VoiceEngine.result(recognizer, VoiceEngine.stream(recognizer, blocked, block = true), wav("zh.wav")).text
            assertTrue(text, "开放" !in text)
            assertTrue(text, "时间" in text)
        } finally {
            VoiceEngine.release()
        }
    }

    @Test
    fun theBeamsOtherHypothesesComeBackBestFirst() {
        val recognizer = VoiceEngine.acquire(app.assets)
        try {
            val stream = VoiceEngine.stream(recognizer, VoiceHotwords.Words.NONE)
            stream.acceptWaveform(wav("zh.wav"), VoiceEngine.SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            stream.release()
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
        val recognizer = VoiceEngine.acquire(app.assets)
        try {
            val heard = VoiceEngine.result(recognizer, VoiceEngine.stream(recognizer, VoiceHotwords.Words.NONE), samples)
            val candidates = VoiceRerank.candidates(
                VoiceRerank.distinct(heard.nbest.indices.map { VoiceRerank.Hypothesis(heard.nbest[it], heard.nbestScores[it]) }),
            )
            assertTrue(candidates.toString(), candidates.size > 1)
            // a model sure of the last of them, the bare texts asked about
            var asked = emptyList<String>()
            val sure = object : VoiceEngine.Language {
                override suspend fun boundaries(texts: List<String>) = emptyMap<String, IntArray>()
                override suspend fun logProbs(texts: List<String>): FloatArray {
                    asked = texts
                    return FloatArray(texts.size) { if (it == texts.size - 1) 0f else -1000f }
                }
            }
            val text = runBlocking {
                VoiceEngine.recognize(recognizer, VoiceEngine.stream(recognizer, VoiceHotwords.Words.NONE), samples, VoiceHotwords.Words.NONE, sure)
            }
            assertEquals(candidates.map { VoiceRerank.bare(it.text) }, asked)
            assertEquals(candidates.last().text, text)
        } finally {
            VoiceEngine.release()
        }
    }

    private fun topScore(words: VoiceHotwords.Words): Float {
        val recognizer = VoiceEngine.acquire(app.assets)
        try {
            return VoiceEngine.result(recognizer, VoiceEngine.stream(recognizer, words), wav("zh.wav")).nbestScores[0]
        } finally {
            VoiceEngine.release()
        }
    }

    // split: TextWords.boundaries in place of the engine's
    private fun hear(hotwords: VoiceHotwords.Words, split: (String) -> IntArray = { IntArray(0) }): List<String> {
        val recognizer = VoiceEngine.acquire(app.assets)
        val vad = VoiceEngine.vad(app.assets)
        val history = VoiceEngine.history()
        val texts = mutableListOf<String>()
        try {
            val samples = wav("zh.wav")
            var i = 0
            while (i < samples.size) {
                val end = minOf(i + VoiceEngine.WINDOW, samples.size)
                val window = samples.copyOfRange(i, end)
                history.add(window)
                vad.acceptWaveform(window)
                i = end
                while (!vad.empty()) {
                    val stretch = VoiceEngine.nextStretch(vad, history)
                    texts += runBlocking {
                        VoiceEngine.recognize(recognizer, VoiceEngine.stream(recognizer, hotwords), stretch, hotwords, language(split))
                    }
                }
            }
        } finally {
            vad.release()
            VoiceEngine.release()
        }
        return texts
    }

    /** 16-bit mono PCM, its header skipped, as floats */
    private fun wav(name: String): FloatArray {
        val bytes = test.assets.open(name).use { it.readBytes() }
        val pcm = ByteBuffer.wrap(bytes, WAV_HEADER, bytes.size - WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(pcm.remaining()) { pcm.get(it) / 32768f }
    }

    companion object {
        private const val WAV_HEADER = 44
    }

    // the recognizer's order, and split as [split] says
    private fun language(split: (String) -> IntArray) = object : VoiceEngine.Language {
        override suspend fun boundaries(texts: List<String>) = texts.associateWith(split)
        override suspend fun logProbs(texts: List<String>) = null
    }
}
