/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * The recognizer (SenseVoice-Small, VoiceDataPlugin): read in once, which takes a few seconds and
 * some 300 MB, and kept while voice input is in use; let go a while after the panel closes, so
 * that coming back to it soon is instant and the memory is not held all day.
 */
object VoiceEngine {

    const val SAMPLE_RATE = 16000

    // the VAD's window: 32 ms at 16 kHz
    const val WINDOW = 512

    private const val DIR = "voice"
    private const val KEEP_MS = 2 * 60 * 1000L
    private const val THREADS = 4

    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var users = 0
    private val main = Handler(Looper.getMainLooper())
    private val letGo = Runnable {
        synchronized(lock) {
            if (users == 0) {
                recognizer?.release()
                recognizer = null
            }
        }
    }

    /** The recognizer, read in if it is not; on a worker thread. [release] when done with it. */
    fun acquire(assets: AssetManager): OfflineRecognizer = synchronized(lock) {
        main.removeCallbacks(letGo)
        val loaded = recognizer ?: OfflineRecognizer(assets, config()).also { recognizer = it }
        users++
        loaded
    }

    fun release() = synchronized(lock) {
        users--
        if (users == 0) main.postDelayed(letGo, KEEP_MS)
    }

    // the VAD hears speech start a little late: this much before is put back (AudioHistory)
    private const val LEAD_IN = SAMPLE_RATE * 3 / 10

    /** enough for the longest stretch the VAD lets through, its pause and lead-in */
    fun history() = AudioHistory(SAMPLE_RATE * 22)

    /** The front stretch the VAD has cut, popped, with its lead-in from [history]. */
    fun nextStretch(vad: Vad, history: AudioHistory): FloatArray {
        val segment = vad.front()
        vad.pop()
        return history.before(segment.start.toLong(), LEAD_IN) + segment.samples
    }

    /** A stretch of speech to text. */
    fun recognize(r: OfflineRecognizer, samples: FloatArray): String {
        val stream = r.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            r.decode(stream)
            r.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    /** One for each time the microphone opens: it keeps the state of what it has heard. */
    fun vad(assets: AssetManager) = Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "$DIR/silero_vad.onnx",
                threshold = 0.5f,
                // a breath between phrases, not the end of what is being said
                minSilenceDuration = 0.6f,
                minSpeechDuration = 0.25f,
                windowSize = WINDOW,
                // SenseVoice takes up to 30 s at a time; a long run is cut and goes in by parts
                maxSpeechDuration = 20f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    private fun config() = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = "$DIR/model.int8.onnx",
                // Mandarin first (dev/TRAINING-PLAN.md 14.3): told so, it does not hear a short
                // phrase as Cantonese or Japanese; English words in it still come out English
                language = "zh",
                // punctuation, and numbers written as numbers
                useInverseTextNormalization = true,
            ),
            tokens = "$DIR/tokens.txt",
            numThreads = THREADS,
        ),
    )
}
