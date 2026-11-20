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
import kotlinx.coroutines.CancellationException
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
import java.io.IOException

/**
 * The streaming recognizer and the polish tagger ([VoicePolisher]): read once (a second or two, some
 * 400 MB) and kept while voice is in use; let go a while after the talk ends, so coming back soon is
 * instant without holding the memory all day.
 */
object VoiceEngine {

    const val SAMPLE_RATE = 16000

    // the microphone read 32 ms at a time
    const val WINDOW = 512

    /**
     * Zeros after the speech so the last chunk (1.92 s) is decoded, plus half a second; shorter lost the
     * last character more often. Changing it requires raising voice.minVersion (app/packs.properties): it
     * holds only for the pack's encoder chunk, and an older pack would still be Ready.
     */
    const val TAIL_PADDING = SAMPLE_RATE * 242 / 100

    private const val DIR = "voice"
    private const val KEEP_MS = 2 * 60 * 1000L
    // on a Snapdragon 845, 4 threads are no faster than 3, and 3 leave a big core to the UI
    private val THREADS = minOf(3, Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
    // the n-best VoiceRerank ranks again
    private const val BEAM = 16

    /** [polisher] null: the text as recognized, cleaned. */
    class Models(val recognizer: OnlineRecognizer, val polisher: VoicePolisher?, val dir: File?)

    /**
     * Every listener's recognizer calls, one at a time: a talk's tail still decoding and the next talk's
     * stream on the same recognizer (which keeps its last hotword graph) never run at once. Decodes
     * suspend, so listeners still interleave on this one thread.
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

    /** On a worker thread; PackUnavailableException without the voice pack. [release] when done. */
    fun acquire(): Models = synchronized(lock) {
        main.removeCallbacks(letGo)
        // a voice pack imported since: the new one's, once no talk holds the old
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

    // sherpa-onnx maps the .ort files by their paths (lib/sherpa-onnx README)
    private fun load(dir: File?): Models {
        val recognizer = OnlineRecognizer(config())
        val polisher = try {
            polisher()
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            // an Error from the tagger (out of memory, a library not linked): the recognizer not left to its finalizer
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
     * Its search never completes a word the user blocked, even inside a longer one. With user hotwords the
     * recognizer rebuilds its graph (the pack's 78 thousand too) only when they differ from the last
     * stream's: a tenth of a second or more.
     */
    fun stream(recognizer: OnlineRecognizer, words: VoiceHotwords.Words): OnlineStream = when {
        // the blocked words and 生僻字屏蔽's characters
        words.searchBlocked.isNotEmpty() -> recognizer.createStream(words.hotwords, words.searchBlocked)
        words.hotwords.isNotEmpty() -> recognizer.createStream(words.hotwords)
        else -> recognizer.createStream()
    }

    /** Of its hypotheses, the one [VoiceChoice] chooses, one with no 生僻字屏蔽 character first; blocked words were cut in the search. */
    suspend fun choose(result: OnlineRecognizerResult, words: VoiceHotwords.Words, language: VoiceChoice.Language): String {
        val heard = VoiceChoice.Heard(result.nbest.toList(), result.nbestScores, result.text)
        return VoiceChoice.choose(heard, words.blockedWords, language, held = words::holds) { heard }
    }

    /** Read again for each listener; with [habits] off (用我的输入习惯) none of the user's, the blocked still blocked. */
    suspend fun userHotwords(fcitx: HostConnection, habits: Boolean = true): VoiceHotwords.Words {
        // not cancelled with the wait: a late read is remembered for the next listener. One read at a time,
        // with habits on: shared, it serves listeners whose switch differs; each applies its own below
        val read = remembered.shared<Deferred<VoiceHotwords.Words>>({ it.isActive }) { ticket ->
            reading.async {
                // the homophones' failure costs the homophones only, never the hotwords
                val chars = recognizerChars()
                val words = fcitx.runOnReady {
                    userWords().let { all ->
                        // 生僻字屏蔽: a user word with a held-back character is neither listened for nor put in; held characters are
                        // never completed
                        val held = rareHeld(all.map { it.text } + chars)
                        val w = VoiceHotwords.unheld(all, held)
                        val homophones = try {
                            voiceHomophones(w)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                            Timber.w(e, "voice homophones not made")
                            VoiceHomophones.NONE
                        }
                        VoiceHotwords.ofUser(w, held, chars).copy(homophones = homophones)
                    }
                }
                remembered.read(ticket, words)
            }
        }
        // how many, not which: they are the user's
        return inTimeOrLast(read, remembered, USER_WORDS_MS).withHabits(habits).also { Timber.d("voice hotwords: %d listened for, %d blocked", it.hotwords.split('/').count { w -> w.isNotEmpty() }, it.blockedWords.size) }
    }

    // each a token of its own (tokens.txt), read once from the voice pack; again for a pack put in since
    @Volatile private var tokenChars: Pair<Long, List<String>>? = null

    private fun recognizerChars(): List<String> = try {
        val file = File(path("tokens.txt"))
        val stamp = file.lastModified()
        tokenChars?.takeIf { it.first == stamp }?.second ?: file.useLines { lines ->
            lines.map { it.substringBefore(' ') }
                .filter { it.codePointCount(0, it.length) == 1 && Character.UnicodeScript.of(it.codePointAt(0)) == Character.UnicodeScript.HAN }
                .toList()
        }.also { tokenChars = stamp to it }
    } catch (e: IOException) {
        Timber.w(e, "voice tokens not read: no character held back from the search")
        emptyList()
    }

    // the engine still starting or busy: voice input with the words last read, not a wait
    private const val USER_WORDS_MS = 2000L
    private val remembered = VoiceHotwords.Remembered()
    // a failed read is the awaiting listener's to log (its Deferred), not this scope's
    private val reading = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Each answer in time or none: no boundaries (VoiceBlocking takes a text as having the word), no scores. */
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

    // made ahead or kept from the last opening, so the press waits on no model read. Lock-free: under [lock]
    // the capture thread would wait out a whole model load (VoiceSpare)
    private val spareVad = VoiceSpare<Vad> { it.release() }

    fun takeVad(): Vad = spareVad.take() ?: vad()

    /** Kept for the next opening (its state cleared), or let go if one is kept. */
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
        // none loaded (they failed): let go now, no letGo would. Read without the lock: a loading talk is counted in users
        val clears = spareVad.clears
        spareVad.offer(vad, wanted = users > 0 || models != null, clearsBefore = clears)
        // letGo may have run between the read and the keep: not left ownerless until the next talk
        if (users == 0 && models == null) spareVad.clear()
    }

    /** One made for a talk whose models failed to load. */
    fun letGoVadIfIdle() = synchronized(lock) {
        if (users == 0) spareVad.clear()
    }

    /** Off the main thread. A failure, an Error too, is [failed]'s. */
    fun prepareVad(failed: (Throwable) -> Unit) {
        spareVad.tryBuild({ vad() }, failed)
    }

    /** Threshold 0.2: 0.5 lost half the soft speech at -58 dBFS. Segments as sherpa-onnx's defaults. */
    private fun vad() = Vad(
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(model = path("silero_vad.onnx"), threshold = VAD_THRESHOLD, windowSize = WINDOW),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    private const val VAD_THRESHOLD = 0.2f
    private const val RULE2_SILENCE_S = 1.2f

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
        // rule2 (silence after text) at 1.2 s, the C++ and Python default; the Kotlin API's default is 1.4 s
        enableEndpoint = true,
        endpointConfig = EndpointConfig(rule2 = EndpointRule(true, RULE2_SILENCE_S, 0.0f)),
        // hotwords and the n-best need the beam search
        decodingMethod = "modified_beam_search",
        maxActivePaths = BEAM,
        hotwordsFile = path("hotwords.txt"),
        hotwordsScore = VoiceHotwords.SCORE,
    )
}

internal fun modelsOutdated(users: Int, loadedDir: File?, loaded: Boolean, dir: File?): Boolean =
    users == 0 && loaded && loadedDir != dir

/**
 * [remembered]'s last read where [read] took longer, failed or was cancelled from elsewhere; a cancelled
 * caller stays cancelled ([cancelledElsewhereAs]). A failed read once fell back to no words, typing a blocked word.
 */
@VisibleForTesting
internal suspend fun inTimeOrLast(read: Deferred<VoiceHotwords.Words>, remembered: VoiceHotwords.Remembered, ms: Long): VoiceHotwords.Words {
    val inTime = withTimeoutOrNull(ms) {
        cancelledElsewhereAs(null) {
            try {
                read.await()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.w(e, "voice hotwords: the read failed")
                null
            }
        }
    }
    // say so: the user's words are dropped
    if (inTime == null) Timber.w("voice hotwords: no answer in %d ms or the read cancelled, the last read used", ms)
    return remembered.inTime(inTime)
}
