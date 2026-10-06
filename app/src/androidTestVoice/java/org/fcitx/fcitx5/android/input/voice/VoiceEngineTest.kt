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
        // the pack's hotwords read from the assets, the user's put in for each stretch: of the
        // two, the first goes from 开放 to 开饭 with the bonus (the second is said more like 开放)
        val word = Engines.UserWord("开饭", "kai fan", Engines.UserWord.Kind.ADDED)
        val with = heard(VoiceHotwords.ofUser(listOf(word)))
        val without = heard(VoiceHotwords.Words.NONE)
        assertTrue("$with with, $without without", with > without)
    }

    @Test
    fun aBlockedWordStandingAloneIsNotWrittenEvenListenedFor() {
        // the bonus that made 开饭 of the first stretch, and the word blocked: split char by char,
        // 开饭 stands as words wherever it is, and another hypothesis or the search again wins
        val listened = heard(VoiceHotwords.Words(hotwords = "开 饭", blocked = ""))
        val blocked = hear(VoiceHotwords.Words(hotwords = "开 饭", blocked = "开 饭")) { IntArray(it.length + 1) { i -> i } }
        assertTrue("$listened 开饭 listened for", listened > 0)
        assertTrue(blocked.toString(), blocked.none { "开饭" in it })
        // the rest still heard
        assertTrue(blocked.toString(), blocked.all { "时间早上九点" in it || "时间早上9点" in it })
    }

    @Test
    fun aBlockedWordWithinALongerOneIsWritten() {
        // each stretch one word: 开饭 within it, not standing, kept as heard
        val listened = heard(VoiceHotwords.Words(hotwords = "开 饭", blocked = ""))
        val within = heard(VoiceHotwords.Words(hotwords = "开 饭", blocked = "开 饭")) { intArrayOf(0, it.length) }
        assertEquals(listened, within)
    }

    @Test
    fun theBlockInTheSearchTakesTheWordWhereverItIs() {
        val blocked = VoiceHotwords.Words(hotwords = "开 饭", blocked = "开 饭")
        val recognizer = VoiceEngine.acquire(app.assets)
        try {
            val text = VoiceEngine.result(recognizer, VoiceEngine.stream(recognizer, blocked, block = true), wav("zh.wav")).text
            assertTrue(text, "开饭" !in text)
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

    private fun heard(words: VoiceHotwords.Words, split: (String) -> IntArray = { IntArray(0) }) =
        hear(words, split).count { it.startsWith("开饭") }

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
                        VoiceEngine.recognize(recognizer, VoiceEngine.stream(recognizer, hotwords), stretch, hotwords) { suspects ->
                            suspects.associateWith(split)
                        }
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
}
