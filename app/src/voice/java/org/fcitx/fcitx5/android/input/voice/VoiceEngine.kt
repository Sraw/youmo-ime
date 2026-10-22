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
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import timber.log.Timber

/**
 * The recognizer (X-ASR, VoiceDataPlugin): read in once, which takes a second or two and some
 * 200 MB, and kept while voice input is in use; let go a while after the panel closes, so
 * that coming back to it soon is instant and the memory is not held all day.
 */
object VoiceEngine {

    const val SAMPLE_RATE = 16000

    // the VAD's window: 32 ms at 16 kHz
    const val WINDOW = 512

    private const val DIR = "voice"
    private const val KEEP_MS = 2 * 60 * 1000L
    private const val THREADS = 4
    // hypotheses kept: the n-best VoiceRerank ranks again; a quarter slower to decode than 4,
    // the encoder most of the work either way
    private const val BEAM = 16

    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var users = 0
    private val main = Handler(Looper.getMainLooper())
    // on a worker: onnxruntime tearing its sessions down on the main thread could drop the keyboard's frames
    private val letGo = Runnable {
        Dispatchers.IO.asExecutor().execute {
            synchronized(lock) {
                if (users == 0) {
                    recognizer?.release()
                    recognizer = null
                }
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

    // the longest stretch the VAD lets through, in seconds: the history holds as much and more
    private const val MAX_SPEECH_S = 20

    /** enough for the longest stretch the VAD lets through, its pause and lead-in (under 2 s) */
    fun history() = AudioHistory(SAMPLE_RATE * (MAX_SPEECH_S + 2))

    /** The front stretch the VAD has cut, popped, with its lead-in from [history]. */
    fun nextStretch(vad: Vad, history: AudioHistory): FloatArray {
        val segment = vad.front()
        vad.pop()
        return history.before(segment.start.toLong(), LEAD_IN) + segment.samples
    }

    /**
     * A stream to recognize a stretch in, listening for the user's [words] (VoiceHotwords.ofUser)
     * besides the pack's; with [block], its search never completing a word they blocked, even
     * within a longer one (see [recognize]). With hotwords of theirs, the recognizer builds its
     * graph of hotwords, the pack's 78 thousand too, only when they differ from the last stream's
     * (it keeps that graph): a tenth of a second or more, so made before the stretch it is for.
     */
    fun stream(recognizer: OfflineRecognizer, words: VoiceHotwords.Words, block: Boolean = false): OfflineStream = when {
        block && words.blocked.isNotEmpty() -> recognizer.createStream(words.hotwords, words.blocked)
        words.hotwords.isNotEmpty() -> recognizer.createStream(words.hotwords)
        else -> recognizer.createStream()
    }

    /** A stretch of speech to the recognizer's result, in [stream], which it releases. */
    fun result(recognizer: OfflineRecognizer, stream: OfflineStream, samples: FloatArray): OfflineRecognizerResult {
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream)
        } finally {
            stream.release()
        }
    }

    /**
     * A stretch of speech to text, in [prepared] (made by [stream] for [words], not blocking),
     * which it releases: of the recognizer's hypotheses, the one [VoiceChoice] chooses with
     * [language]; if each has a word the user blocked, the stretch searched again with them blocked.
     */
    suspend fun recognize(
        recognizer: OfflineRecognizer,
        prepared: OfflineStream,
        samples: FloatArray,
        words: VoiceHotwords.Words,
        language: VoiceChoice.Language,
    ): String = VoiceChoice.choose(heard(result(recognizer, prepared, samples)), words.blockedWords, language) {
        heard(result(recognizer, stream(recognizer, words, block = true), samples))
    }

    private fun heard(result: OfflineRecognizerResult) =
        VoiceChoice.Heard(result.nbest.toList(), result.nbestScores, result.text)

    /**
     * the words the user added and made (VoiceHotwords.ofUser), read again for each listener; with
     * [habits] off (U-93's 用我的输入习惯) none of theirs, the blocked still blocked
     */
    suspend fun userHotwords(fcitx: FcitxConnection, habits: Boolean = true): VoiceHotwords.Words {
        // not cancelled with the wait: a read that comes late is still remembered for the next listener
        // one read at a time: a hold while one is still waiting on a busy engine waits on it too
        // read with habits on: shared and remembered, it serves later listeners whose switch may differ;
        // each listener's own switch is applied below
        val read = remembered.shared<Deferred<VoiceHotwords.Words>>({ it.isActive }) { ticket ->
            reading.async { remembered.read(ticket, VoiceHotwords.ofUser(fcitx.runOnReady { userWords() })) }
        }
        val inTime = withTimeoutOrNull(USER_WORDS_MS) { read.await() }
        // a timeout was silent: the user's words were dropped and nothing said so
        if (inTime == null) Timber.w("voice hotwords: no answer in %d ms, the last read used", USER_WORDS_MS)
        // how many, not which: they are the user's
        return remembered.inTime(inTime).withHabits(habits).also { Timber.d("voice hotwords: %d listened for, %d blocked", it.hotwords.split('/').count { w -> w.isNotEmpty() }, it.blockedWords.size) }
    }

    // the engine still starting or busy: voice input with the words last read, not a wait
    private const val USER_WORDS_MS = 2000L
    private val remembered = VoiceHotwords.Remembered()
    // a failed read is the awaiting listener's to log (its Deferred), not this scope's
    private val reading = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The pinyin engine's [VoiceChoice.Language], each answer in time or none: no boundaries
     * (VoiceBlocking takes a text as having the word), no scores (the recognizer's order).
     */
    fun language(fcitx: FcitxConnection) = object : VoiceChoice.Language {
        override suspend fun boundaries(texts: List<String>) =
            withTimeoutOrNull(LANGUAGE_MS) { fcitx.runOnReady { wordBoundaries(texts) } }.orEmpty()

        override suspend fun logProbs(texts: List<String>) =
            withTimeoutOrNull(LANGUAGE_MS) { fcitx.runOnReady { logProbs(texts) } }
    }

    // the engine busy or starting: the text as heard, not a wait
    private const val LANGUAGE_MS = 1000L

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
                // a long run is cut and goes in by parts, each recognized while the next is heard
                maxSpeechDuration = MAX_SPEECH_S.toFloat(),
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    private fun config() = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = "$DIR/encoder.ort",
                decoder = "$DIR/decoder.ort",
                joiner = "$DIR/joiner.ort",
            ),
            tokens = "$DIR/tokens.txt",
            numThreads = THREADS,
            // given, not found out: for that, sherpa-onnx reads all of encoder.ort onto the heap and
            // makes a session of it, its weights copied, before mapping it (OfflineRecognizerImpl::Create)
            modelType = "transducer",
            modelingUnit = "bpe",
            bpeVocab = "$DIR/bpe.vocab",
        ),
        // hotwords and the n-best need the beam search
        decodingMethod = "modified_beam_search",
        maxActivePaths = BEAM,
        hotwordsFile = "$DIR/hotwords.txt",
        hotwordsScore = VoiceHotwords.SCORE,
    )
}
