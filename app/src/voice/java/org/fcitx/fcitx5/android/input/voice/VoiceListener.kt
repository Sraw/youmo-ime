/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.Vad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Collections
import kotlin.math.sqrt

/**
 * The microphone, the VAD and the recognizer: reads the microphone while open, cuts what it hears
 * at each pause and recognizes each stretch on a thread of its own, the reading going on meanwhile.
 * Tells [events] of it all on the main thread, until [close]d. Used from the main thread.
 */
class VoiceListener(
    private val assets: AssetManager,
    private val events: Events,
    /** the user's words to listen for and blocked (VoiceHotwords.ofUser), asked for as the models load */
    private val hotwords: suspend () -> VoiceHotwords.Words,
    /** the pinyin engine's models (VoiceEngine.language): to rank what is heard, and for the blocked words */
    private val language: VoiceEngine.Language,
) {

    enum class Failure { NoModel, NoMicrophone }

    interface Events {
        fun loaded()
        fun failed(why: Failure)
        fun speechStarted()
        fun speechEnded()
        fun recognized(text: String)

        /** how loud, 0 to 1, a few times a second */
        fun level(level: Float)
    }

    // a native error is the panel's to show, not the keyboard's to crash on
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        Timber.w(e, "voice")
        tell { failed(Failure.NoMicrophone) }
    })

    // one stretch at a time, in the order said
    @OptIn(ExperimentalCoroutinesApi::class)
    private val decoder = Dispatchers.Default.limitedParallelism(1)

    // a stretch's from its start to its text told and the next stream made, across suspensions
    private val serial = Mutex()

    private val main = Handler(Looper.getMainLooper())

    private var recognizer: OfflineRecognizer? = null

    // the recognizer once in: the microphone may open first (hold to talk), its stretches wait
    private val ready = CompletableDeferred<OfflineRecognizer>()

    // the stretches heard and not yet recognized: what finish and close wait for
    private val pending: MutableList<Job> = Collections.synchronizedList(mutableListOf())

    // the decoder's: the hotwords, and the stream made ready for the next stretch
    private var words = VoiceHotwords.Words.NONE
    private var next: OfflineStream? = null
    private var capture: Job? = null
    // read off the main thread too, as the models load
    @Volatile
    private var closed = false

    private fun tell(event: Events.() -> Unit) {
        main.post { if (!closed) events.event() }
    }

    fun load() {
        scope.launch {
            // asked for while the models load, not after: the engine may be busy a while
            val user = async { userHotwords() }
            val loaded = try {
                VoiceEngine.acquire(assets)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // sherpa-onnx's IllegalArgumentException for a model it could not read, or worse
                Timber.w(e, "voice model")
                user.cancel()
                ready.completeExceptionally(e)
                tell { failed(Failure.NoModel) }
                return@launch
            }
            // acquired, released whatever happens (an Error too) until it is handed over
            var handed = false
            try {
                val words = user.await()
                // closed meanwhile: not the graph of hotwords built for nothing
                if (!closed) {
                    withContext(decoder) {
                        this@VoiceListener.words = words
                        next = VoiceEngine.stream(loaded, words)
                    }
                }
                handed = true
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.w(e, "voice stream")
                tell { failed(Failure.NoModel) }
            } finally {
                if (!handed) {
                    VoiceEngine.release()
                    // what waits for the models: dropped, not waiting for good
                    ready.cancel()
                }
            }
            if (!handed) return@launch
            main.post {
                if (closed) {
                    // what was heard before it closed is not recognized: it is not told either
                    ready.cancel()
                    scope.launch(decoder) { letGoNext() }
                    VoiceEngine.release()
                } else {
                    recognizer = loaded
                    ready.complete(loaded)
                    events.loaded()
                }
            }
        }
    }

    // the engine failing: the text as heard, and each taken as having a blocked word
    private val safeLanguage = object : VoiceEngine.Language {
        override suspend fun boundaries(texts: List<String>) = try {
            language.boundaries(texts)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "voice word boundaries")
            emptyMap()
        }

        override suspend fun logProbs(texts: List<String>) = try {
            language.logProbs(texts)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "voice log probs")
            null
        }
    }

    private suspend fun userHotwords() = try {
        hotwords()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // voice input without them, not none
        Timber.w(e, "voice hotwords")
        VoiceHotwords.Words.NONE
    }

    /**
     * Opens the microphone; the caller has checked the permission. The models may still be
     * loading: what is heard meanwhile is recognized once they are in.
     */
    fun start() {
        if (closed || capture?.isActive == true) return
        // the last one still closing, quickly paused and resumed: one microphone at a time
        val last = capture
        capture = scope.launch {
            last?.join()
            record()
        }
    }

    /**
     * Closes the microphone and, once what was heard is all recognized and told, [done]: the
     * finger lifted from holding to talk. Not if closed meanwhile.
     */
    fun finish(done: () -> Unit) {
        stop()
        val last = capture
        scope.launch {
            last?.join()
            stillPending().joinAll()
            main.post { if (!closed) done() }
        }
    }

    /** Closes the microphone; what was heard up to now is still recognized and typed. */
    fun stop() {
        capture?.cancel()
    }

    /**
     * Closes the microphone, for good: nothing more is told. The recognizer is let go once what
     * was heard is recognized.
     */
    fun close() {
        if (closed) return
        closed = true
        stop()
        // still loading: once in, it is let go there, and what waits for it dropped
        recognizer ?: return
        recognizer = null
        val last = capture
        scope.launch {
            last?.join()
            stillPending().joinAll()
            withContext(decoder) { letGoNext() }
            main.post { VoiceEngine.release() }
        }
    }

    // toList() reads the size, then the first: a stretch done between the two would throw
    private fun stillPending() = synchronized(pending) { pending.toList() }

    private suspend fun record() {
        val record = open()
        if (record == null) {
            tell { failed(Failure.NoMicrophone) }
            return
        }
        val history = VoiceEngine.history()
        var vad: Vad? = null
        try {
            record.startRecording()
            // the VAD's model read in once the microphone is on, not before: what is said meanwhile
            // waits in the record's second
            vad = VoiceEngine.vad(assets)
            listen(record, vad, history)
        } finally {
            record.stop()
            record.release()
            vad?.let {
                // the last stretch, cut short by the pause
                it.flush()
                drain(it, history)
                it.release()
            }
        }
    }

    /** The microphone, in the first of [ENCODINGS] it records in; null if in none. */
    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? {
        for ((encoding, bytes) in ENCODINGS) {
            val min = AudioRecord.getMinBufferSize(VoiceEngine.SAMPLE_RATE, CHANNEL, encoding)
            // a second's room, should the reading fall behind
            val size = maxOf(min, VoiceEngine.SAMPLE_RATE * bytes)
            val record = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, VoiceEngine.SAMPLE_RATE, CHANNEL, encoding, size)
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "microphone")
                null
            }
            if (record?.state == AudioRecord.STATE_INITIALIZED) return record
            record?.release()
        }
        return null
    }

    private suspend fun listen(record: AudioRecord, vad: Vad, history: AudioHistory) {
        val buffer = FloatArray(VoiceEngine.WINDOW)
        val shorts = if (record.audioFormat == AudioFormat.ENCODING_PCM_16BIT) ShortArray(VoiceEngine.WINDOW) else null
        var speaking = false
        var windows = 0
        while (currentCoroutineContext().isActive) {
            val n = read(record, buffer, shorts)
            if (n < 0) {
                tell { failed(Failure.NoMicrophone) }
                return
            }
            if (n == 0) continue
            val samples = buffer.copyOf(n)
            history.add(samples)
            vad.acceptWaveform(samples)
            val now = vad.isSpeechDetected()
            if (now && !speaking) tell { speechStarted() }
            speaking = now
            drain(vad, history)
            if (++windows % LEVEL_EVERY == 0) {
                val level = rms(samples)
                tell { level(level) }
            }
        }
    }

    /** A window into [buffer], as [AudioRecord.read]: a 16-bit record's through [shorts], to -1 to 1. */
    private fun read(record: AudioRecord, buffer: FloatArray, shorts: ShortArray?): Int {
        if (shorts == null) return record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
        val n = record.read(shorts, 0, shorts.size, AudioRecord.READ_BLOCKING)
        for (i in 0 until n) buffer[i] = shorts[i] / PCM_16BIT_SCALE
        return n
    }

    private fun drain(vad: Vad, history: AudioHistory) {
        while (!vad.empty()) {
            val samples = VoiceEngine.nextStretch(vad, history)
            tell { speechEnded() }
            val job = scope.launch(decoder) {
                // the stretch before it suspended (its words split on the fcitx thread): still in order
                serial.withLock {
                    // dropped, not told, if the models never came in (told as such) or it closed first
                    val r = try {
                        ready.await()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                        Timber.d(e, "voice: a stretch dropped, no models")
                        return@launch
                    }
                    val text = try {
                        val stream = next ?: VoiceEngine.stream(r, words)
                        next = null
                        VoiceEngine.recognize(r, stream, samples, words, safeLanguage)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                        // one stretch lost, not the panel
                        Timber.w(e, "recognize")
                        ""
                    }
                    tell { recognized(text) }
                    // the next one's, made while it is said, once this one's text is out
                    next = try {
                        VoiceEngine.stream(r, words)
                    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                        Timber.w(e, "voice stream")
                        null
                    }
                }
            }
            pending += job
            job.invokeOnCompletion { pending -= job }
        }
    }

    private fun letGoNext() {
        next?.release()
        next = null
    }

    private fun rms(samples: FloatArray): Float {
        var sum = 0f
        for (s in samples) sum += s * s
        // speech at a phone's distance is some 0.05 to 0.2: scaled so that reads as most of the way
        return (sqrt(sum / samples.size) * LEVEL_SCALE).coerceIn(0f, 1f)
    }

    companion object {
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        // float as the VAD and the recognizer take it, where the capture path has it; else 16-bit,
        // which every device records in. Each with its bytes a sample
        private val ENCODINGS = listOf(
            AudioFormat.ENCODING_PCM_FLOAT to Float.SIZE_BYTES,
            AudioFormat.ENCODING_PCM_16BIT to Short.SIZE_BYTES,
        )
        private const val PCM_16BIT_SCALE = 32768f
        private const val LEVEL_EVERY = 3
        private const val LEVEL_SCALE = 5f
    }
}
