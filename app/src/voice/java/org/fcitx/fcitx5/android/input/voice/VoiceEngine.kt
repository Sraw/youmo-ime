/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.daemon.HostConnection
import timber.log.Timber

/**
 * The streaming recognizer (X-ASR's 1920 ms streaming transducer, its encoder fine-tuned for
 * accented Mandarin; 060-voice-tagger.md §4.4) and the polish tagger ([VoicePolisher]): read in
 * once, which takes a second or two and some 400 MB, and kept while voice input is in use; let go a
 * while after the talk ends, so that coming back to it soon is instant and the memory is not held all day.
 */
object VoiceEngine {

    const val SAMPLE_RATE = 16000

    // the microphone read 32 ms at a time
    const val WINDOW = 512

    /**
     * Zeros after the last of the speech so the last chunk is decoded: a chunk (1.92 s) and a second
     * more, as voice-eval's seg_app.py measured it.
     */
    const val TAIL_PADDING = SAMPLE_RATE * 292 / 100

    private const val DIR = "voice"
    private const val KEEP_MS = 2 * 60 * 1000L
    private const val THREADS = 2
    // hypotheses kept: the n-best VoiceRerank ranks again, as measured (seg_app.py)
    private const val BEAM = 16

    /** The recognizer and the tagger, if it loaded (none: the text as recognized, cleaned). */
    class Models(val recognizer: OnlineRecognizer, val polisher: VoicePolisher?)

    private val lock = Any()
    private var models: Models? = null
    private var users = 0
    private val main = Handler(Looper.getMainLooper())
    // on a worker: onnxruntime tearing its sessions down on the main thread could drop the keyboard's frames
    private val letGo = Runnable {
        Dispatchers.IO.asExecutor().execute {
            synchronized(lock) {
                if (users == 0) {
                    models?.recognizer?.release()
                    models?.polisher?.close()
                    models = null
                }
            }
        }
    }

    /** The models, read in if they are not; on a worker thread. [release] when done with them. */
    fun acquire(assets: AssetManager): Models = synchronized(lock) {
        main.removeCallbacks(letGo)
        val loaded = models ?: Models(OnlineRecognizer(assets, config()), polisher(assets)).also { models = it }
        users++
        loaded
    }

    fun release() = synchronized(lock) {
        users--
        if (users == 0) main.postDelayed(letGo, KEEP_MS)
    }

    // voice input without the polish rather than none
    @Suppress("TooGenericExceptionCaught")
    private fun polisher(assets: AssetManager): VoicePolisher? = try {
        VoicePolisher.open(assets)
    } catch (e: Exception) {
        Timber.w(e, "voice polish tagger")
        null
    }

    /**
     * A stream to recognize a hold in, listening for the user's [words] (VoiceHotwords.ofUser)
     * besides the pack's, its search never completing a word they blocked, even within a longer
     * one. With hotwords of theirs, the recognizer builds its graph of hotwords, the pack's 78
     * thousand too, only when they differ from the last stream's (it keeps that graph): a tenth of a
     * second or more.
     */
    fun stream(recognizer: OnlineRecognizer, words: VoiceHotwords.Words): OnlineStream = when {
        words.blocked.isNotEmpty() -> recognizer.createStream(words.hotwords, words.blocked)
        words.hotwords.isNotEmpty() -> recognizer.createStream(words.hotwords)
        else -> recognizer.createStream()
    }

    /**
     * A segment's text, the recognizer's [result] at its endpoint: of its hypotheses, the one
     * [VoiceChoice] chooses with [language]; the blocked words were blocked in the search already.
     */
    suspend fun choose(result: OnlineRecognizerResult, words: VoiceHotwords.Words, language: VoiceChoice.Language): String {
        val heard = VoiceChoice.Heard(result.nbest.toList(), result.nbestScores, result.text)
        return VoiceChoice.choose(heard, words.blockedWords, language) { heard }
    }

    /**
     * the words the user added and made (VoiceHotwords.ofUser), read again for each listener; with
     * [habits] off (U-93's 用我的输入习惯) none of theirs, the blocked still blocked
     */
    suspend fun userHotwords(fcitx: HostConnection, habits: Boolean = true): VoiceHotwords.Words {
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
    fun language(fcitx: HostConnection) = object : VoiceChoice.Language {
        override suspend fun boundaries(texts: List<String>) =
            withTimeoutOrNull(LANGUAGE_MS) { fcitx.runOnReady { wordBoundaries(texts) } }.orEmpty()

        override suspend fun logProbs(texts: List<String>) =
            withTimeoutOrNull(LANGUAGE_MS) { fcitx.runOnReady { logProbs(texts) } }
    }

    // the engine busy or starting: the text as heard, not a wait
    private const val LANGUAGE_MS = 1000L

    private fun config() = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$DIR/encoder.ort",
                decoder = "$DIR/decoder.ort",
                joiner = "$DIR/joiner.ort",
            ),
            tokens = "$DIR/tokens.txt",
            numThreads = THREADS,
            modelingUnit = "bpe",
            bpeVocab = "$DIR/bpe.vocab",
        ),
        // a segment ends where its rules say (sherpa-onnx's defaults), its hypotheses carried over (lib/sherpa-onnx README)
        enableEndpoint = true,
        // hotwords and the n-best need the beam search
        decodingMethod = "modified_beam_search",
        maxActivePaths = BEAM,
        hotwordsFile = "$DIR/hotwords.txt",
        hotwordsScore = VoiceHotwords.SCORE,
    )
}
