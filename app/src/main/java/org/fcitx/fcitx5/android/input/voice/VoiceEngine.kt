/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.data.pack.AppData
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.pack.PackKind
import timber.log.Timber
import java.io.File

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

    /**
     * The recognizer and the tagger, if it loaded (none: the text as recognized, cleaned), read
     * from the voice pack in [dir].
     */
    class Models(val recognizer: OnlineRecognizer, val polisher: VoicePolisher?, val dir: File?)

    /**
     * The recognizer's calls, every listener's, one at a time: a talk's tail still decoding and the
     * next talk's stream made on the same recognizer (which keeps its last graph of hotwords) do not overlap.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val decoder: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val lock = Any()
    // written under [lock]; read without it by offerVad (the capture thread), which must not wait out a model load
    @Volatile
    private var models: Models? = null
    @Volatile
    private var users = 0
    private val main = Handler(Looper.getMainLooper())
    // on a worker: onnxruntime tearing its sessions down on the main thread could drop the keyboard's frames
    private val letGo = Runnable {
        Dispatchers.IO.asExecutor().execute {
            synchronized(lock) {
                if (users == 0) unload()
            }
        }
    }

    // under [lock], no talk holding them
    private fun unload() {
        models?.recognizer?.release()
        models?.polisher?.close()
        models = null
        spareVad.clear()
    }

    /**
     * The models, read in if they are not, from the voice pack (AppData: PackUnavailableException
     * without it); on a worker thread. [release] when done with them.
     */
    fun acquire(): Models = synchronized(lock) {
        main.removeCallbacks(letGo)
        // a voice pack imported since they were read: the new one's, once no talk holds the old
        val dir = PackStore.dir(PackKind.VOICE)
        if (modelsOutdated(users, models?.dir, models != null, dir)) unload()
        // counted before the load: offerVad, reading it without the lock, keeps the VAD for this talk
        users++
        try {
            models ?: load(dir).also { models = it }
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            users--
            throw t
        }
    }

    // the pack's files by their paths: sherpa-onnx maps the .ort ones (lib/sherpa-onnx README)
    private fun load(dir: File?): Models {
        val recognizer = OnlineRecognizer(null, config())
        val polisher = try {
            polisher()
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            // an Error from the tagger (out of memory, a library not linked): the recognizer just made not left to its finalizer
            recognizer.release()
            throw t
        }
        return Models(recognizer, polisher, dir)
    }

    fun release() = synchronized(lock) {
        users--
        if (users == 0) main.postDelayed(letGo, KEEP_MS)
    }

    // voice input without the polish rather than none
    @Suppress("TooGenericExceptionCaught")
    private fun polisher(): VoicePolisher? = try {
        VoicePolisher.open()
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
            reading.async {
                // the homophones made of the same words, at once: their words are the hotwords'
                val words = fcitx.runOnReady { userWords().let { VoiceHotwords.ofUser(it).copy(homophones = voiceHomophones(it)) } }
                remembered.read(ticket, words)
            }
        }
        // how many, not which: they are the user's
        return inTimeOrLast(read, remembered, USER_WORDS_MS).withHabits(habits).also { Timber.d("voice hotwords: %d listened for, %d blocked", it.hotwords.split('/').count { w -> w.isNotEmpty() }, it.blockedWords.size) }
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

        override suspend fun homophonesAllowed(spans: List<VoiceHomophones.Span>) =
            withTimeoutOrNull(LANGUAGE_MS) { fcitx.runOnReady { voiceHomophonesAllowed(spans) } }
    }

    // the engine busy or starting: the text as heard, not a wait
    private const val LANGUAGE_MS = 1000L

    // a VAD made ahead (the talk's start, beside the models) or kept from the last opening: the
    // press waits on no model read (dev/design/voice-start-latency.md). Lock-free: under [lock] the
    // capture thread would wait out a whole model load (VoiceSpare); let go with the models
    private val spareVad = VoiceSpare<Vad> { it.release() }

    /** One for this microphone opening: the spare if there is one, else made now. */
    fun takeVad(): Vad = spareVad.take() ?: vad()

    /** [vad], done with, kept for the next opening (its state cleared) or let go if one is kept. */
    fun offerVad(vad: Vad) {
        @Suppress("TooGenericExceptionCaught")
        try {
            vad.reset()
        } catch (e: Throwable) {
            // not kept in a state no one knows: let go
            Timber.w(e, "voice vad")
            vad.release()
            return
        }
        // no talk holding the models and none loaded (they failed): let go now, no letGo would clear it.
        // Read without the lock: a talk loading its models (seconds) is counted in users already
        val clears = spareVad.clears
        spareVad.offer(vad, wanted = users > 0 || models != null, clearsBefore = clears)
        // letGo may have run between the read and the keep: not left without an owner until the next talk
        // (a talk starting meanwhile at worst makes its VAD anew)
        if (users == 0 && models == null) spareVad.clear()
    }

    /** The spare let go if no talk holds the models: one made for a talk whose models failed to load. */
    fun letGoVadIfIdle() = synchronized(lock) {
        if (users == 0) spareVad.clear()
    }

    /** A spare VAD made, if there is none; off the main thread. A failure, an Error too, is [failed]'s. */
    fun prepareVad(failed: (Throwable) -> Unit) {
        spareVad.tryBuild({ vad() }, failed)
    }

    /**
     * silero VAD for one microphone opening (VoiceQuiet): the threshold that kept the soft speech
     * measured (0.2; 0.5 lost half of it at -58 dBFS), segments as sherpa-onnx's defaults.
     */
    private fun vad() = Vad(
        null,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(model = path("silero_vad.onnx"), threshold = VAD_THRESHOLD, windowSize = WINDOW),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    private const val VAD_THRESHOLD = 0.2f
    private const val RULE2_SILENCE_S = 1.2f

    // a voice pack file's path; without the pack, PackUnavailableException
    private fun path(name: String) = AppData.file("$DIR/$name").path

    private fun config() = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = path("encoder.ort"),
                decoder = path("decoder.ort"),
                joiner = path("joiner.ort"),
            ),
            tokens = path("tokens.txt"),
            numThreads = THREADS,
            modelingUnit = "bpe",
            bpeVocab = path("bpe.vocab"),
        ),
        // a segment ends where its rules say, its hypotheses carried over (lib/sherpa-onnx README):
        // sherpa-onnx's defaults, rule2 (silence after text) as measured, 1.2 s (the C++ and
        // Python default, voice-eval's); the Kotlin API's default is 1.4 s
        enableEndpoint = true,
        endpointConfig = EndpointConfig(rule2 = EndpointRule(true, RULE2_SILENCE_S, 0.0f)),
        // hotwords and the n-best need the beam search
        decodingMethod = "modified_beam_search",
        maxActivePaths = BEAM,
        hotwordsFile = path("hotwords.txt"),
        hotwordsScore = VoiceHotwords.SCORE,
    )
}

/**
 * The models read are another pack's than the one installed now ([dir]: a voice pack imported since) and no talk holds
 * them: let go, to be read anew from [dir].
 */
internal fun modelsOutdated(users: Int, loadedDir: File?, loaded: Boolean, dir: File?): Boolean =
    users == 0 && loaded && loadedDir != dir

/**
 * [read]'s words in [ms], or [remembered]'s last read where it took longer or was
 * cancelled from elsewhere (its scope ending); the caller cancelled stays cancelled ([cancelledElsewhereAs])
 */
@VisibleForTesting
internal suspend fun inTimeOrLast(read: Deferred<VoiceHotwords.Words>, remembered: VoiceHotwords.Remembered, ms: Long): VoiceHotwords.Words {
    val inTime = withTimeoutOrNull(ms) { cancelledElsewhereAs(null) { read.await() } }
    // a timeout was silent: the user's words were dropped and nothing said so
    if (inTime == null) Timber.w("voice hotwords: no answer in %d ms or the read cancelled, the last read used", ms)
    return remembered.inTime(inTime)
}
