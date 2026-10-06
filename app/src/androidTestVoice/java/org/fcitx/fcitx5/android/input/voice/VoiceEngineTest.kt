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
            // 开饭时间早上九点至下午五点。: the full stop put in, the numbers then made digits. The
            // first syllable comes back 开 or 菜 as 0.1 s more or less of lead-in is given: how
            // well it is heard is the evaluation sets' to measure, this the pipeline's
            val typed = VoiceText.clean(text).orEmpty()
            assertTrue(typed, typed.endsWith("饭时间早上9点至下午5点。"))
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
