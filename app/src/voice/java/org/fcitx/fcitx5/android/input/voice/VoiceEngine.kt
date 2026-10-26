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
import com.k2fsa.sherpa.onnx.SpeechSegment
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
                    // voice not used a while: the spare's native memory back too, made again on the next show
                    spareVad.clear()
                }
            }
        }
    }

    /** The recognizer, read in if it is not; on a worker thread. [release] when done with it. */
    fun acquire(assets: AssetManager): OfflineRecognizer = synchronized(lock) {
        main.removeCallbacks(letGo)
        // in use: the spare is let go with the recognizer (letGo), counted from the last use, not the last show
        main.removeCallbacks(letGoSpare)
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
    const val MAX_SPEECH_S = 20

    /** enough for the longest stretch the VAD lets through, its pause and lead-in (under 2 s) */
    fun history() = AudioHistory(SAMPLE_RATE * (MAX_SPEECH_S + 2))

    /** The front stretch the VAD has cut, popped, with its lead-in from [history]. */
    fun nextStretch(vad: Vad, history: AudioHistory, dropped: Long = 0): FloatArray {
        val segment = vad.front()
        vad.pop()
        return stretch(segment, history, dropped)
    }

    /** [segment], popped already, with its lead-in from [history]: front() copies the whole stretch over JNI, read once */
    fun stretch(segment: SpeechSegment, history: AudioHistory, dropped: Long = 0): FloatArray {
        // the VAD's positions start after what a backlog dropped before it came (VoiceBacklog.dropped)
        return history.before(segment.start + dropped, LEAD_IN) + segment.samples
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

    // a VAD made ahead for the next opening: its model read in before the press, not after the
    // microphone opens (the start's latency, dev/design/voice-start-latency.md). Its own, lock-free:
    // under [lock] the capture thread waited out a whole model load (VoiceSpare)
    private val spareVad = VoiceSpare<Vad> { it.release() }

    /** One for each time the microphone opens, the spare if one was made: it keeps the state of what it has heard. */
    fun takeVad(assets: AssetManager): Vad = spareVad.take() ?: vad(assets)

    // the spare made on a show with no use after it: let go after the same idle as the recognizer
    private val letGoSpare = Runnable {
        Dispatchers.IO.asExecutor().execute {
            synchronized(lock) { if (users == 0) spareVad.clear() }
        }
    }

    /**
     * [prepareVad] on a thread of its own: the voice key shown, the first press of a process finds
     * one made (built on show rather than on the press: the cold press stays fast). Let go [KEEP_MS]
     * after the last show if voice is not used meanwhile.
     */
    fun prepareVadSoon(assets: AssetManager) {
        main.removeCallbacks(letGoSpare)
        main.postDelayed(letGoSpare, KEEP_MS)
        if (spareVad.ready) return
        Dispatchers.IO.asExecutor().execute {
            // an Error too (sherpa's library not loading, memory running out): no handler on this
            // executor, it would end the process on every show
            @Suppress("TooGenericExceptionCaught")
            try {
                prepareVad(assets)
            } catch (e: Throwable) {
                // made when the microphone opens instead
                Timber.w(e, "voice vad")
            }
        }
    }

    /** [vad] made for an opening that closed before using it: kept for the next one, not made again */
    fun offerVad(vad: Vad) {
        spareVad.offer(vad)
    }

    /** A spare VAD made for the next opening, if there is none; off the main thread. */
    fun prepareVad(assets: AssetManager) {
        spareVad.build { vad(assets) }
    }

    private fun vad(assets: AssetManager) = Vad(
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
            // the only family built (lib/sherpa-onnx/README.md): empty or "transducer", Create refuses
            // any other (a misspelt type fails at once, not as a model that will not load)
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
