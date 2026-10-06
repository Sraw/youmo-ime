/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import androidx.test.platform.app.InstrumentationRegistry
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
                while (!vad.empty()) texts += VoiceEngine.recognize(recognizer, VoiceEngine.nextStretch(vad, history))
            }
        } finally {
            vad.release()
            VoiceEngine.release()
        }
        assertEquals(texts.toString(), 2, texts.size)
        for (text in texts) {
            // 开饭时间早上9点至下午5点。: numbers as numbers, the full stop put in; its first
            // consonant clipped by the VAD without the lead-in, it was 派饭 (开放 is the model's)
            assertTrue(text, text.matches(Regex("开[饭放]时间早上9点至下午5点。")))
            assertEquals(text, VoiceText.clean(text))
        }
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
