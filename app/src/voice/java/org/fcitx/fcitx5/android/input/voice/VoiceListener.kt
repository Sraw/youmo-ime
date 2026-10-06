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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.sqrt

/**
 * The microphone, the VAD and the recognizer: reads the microphone while open, cuts what it hears
 * at each pause and recognizes each stretch on a thread of its own, the reading going on meanwhile.
 * Tells [events] of it all on the main thread, until [close]d. Used from the main thread.
 */
class VoiceListener(
    private val assets: AssetManager,
    private val events: Events,
    /** the user's words to listen for (VoiceHotwords.ofUser), asked for as the models load */
    private val hotwords: suspend () -> String,
) {

    interface Events {
        fun loaded()
        fun failed(why: VoiceSession.Failure)
        fun speechStarted()
        fun speechEnded()
        fun recognized(text: String)

        /** how loud, 0 to 1, a few times a second */
        fun level(level: Float)
    }

    // a native error is the panel's to show, not the keyboard's to crash on
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        Timber.w(e, "voice")
        tell { failed(VoiceSession.Failure.NoMicrophone) }
    })

    // one stretch at a time, in the order said
    @OptIn(ExperimentalCoroutinesApi::class)
    private val decoder = Dispatchers.Default.limitedParallelism(1)

    private val main = Handler(Looper.getMainLooper())

    private var recognizer: OfflineRecognizer? = null

    // the decoder's: the hotwords, and the stream made ready for the next stretch
    private var words = ""
    private var next: OfflineStream? = null
    private var capture: Job? = null
    // read off the main thread too, as the models load
    @Volatile
    private var closed = false

    /** closed, the stretches heard before are still told (the main thread's, as [closed]) */
    private var keepLast = false

    /** [stretch]: a stretch recognized, the one event still told after close(keepLast = true) */
    private fun tell(stretch: Boolean = false, event: Events.() -> Unit) {
        main.post { if (!closed || (stretch && keepLast)) events.event() }
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
                tell { failed(VoiceSession.Failure.NoModel) }
                return@launch
            }
            // acquired, released whatever happens next until it is handed over
            try {
                val words = user.await()
                // closed meanwhile: not the graph of hotwords built for nothing
                if (!closed) {
                    withContext(decoder) {
                        this@VoiceListener.words = words
                        next = VoiceEngine.stream(loaded, words)
                    }
                }
            } catch (e: CancellationException) {
                VoiceEngine.release()
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.w(e, "voice stream")
                VoiceEngine.release()
                tell { failed(VoiceSession.Failure.NoModel) }
                return@launch
            }
            main.post {
                if (closed) {
                    scope.launch(decoder) { letGoNext() }
                    VoiceEngine.release()
                } else {
                    recognizer = loaded
                    events.loaded()
                }
            }
        }
    }

    private suspend fun userHotwords() = try {
        hotwords()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // voice input without them, not none
        Timber.w(e, "voice hotwords")
        ""
    }

    /** Opens the microphone once the models are in; the caller has checked the permission. */
    fun start() {
        val r = recognizer ?: return
        if (closed || capture?.isActive == true) return
        // the last one still closing, quickly paused and resumed: one microphone at a time
        val last = capture
        capture = scope.launch {
            last?.join()
            record(r)
        }
    }

    /** Closes the microphone; what was heard up to now is still recognized and typed. */
    fun stop() {
        capture?.cancel()
    }

    /**
     * Closes the microphone, for good. [keepLast]: the stretches already heard are still told
     * (the panel left for the keyboard, in the same field); else nothing more is (the field left,
     * the keyboard hidden). The recognizer is let go once the last of them is recognized.
     */
    fun close(keepLast: Boolean) {
        if (closed) return
        closed = true
        this.keepLast = keepLast
        stop()
        val r = recognizer ?: return
        recognizer = null
        val last = capture
        scope.launch {
            last?.join()
            // queued after the stretches the capture's end left to recognize: run after them
            withContext(decoder) { letGoNext() }
            main.post { VoiceEngine.release() }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun record(r: OfflineRecognizer) {
        val vad = VoiceEngine.vad(assets)
        val history = VoiceEngine.history()
        val min = AudioRecord.getMinBufferSize(VoiceEngine.SAMPLE_RATE, CHANNEL, ENCODING)
        // a second's room, should the reading fall behind
        val size = maxOf(min, VoiceEngine.SAMPLE_RATE * Float.SIZE_BYTES)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, VoiceEngine.SAMPLE_RATE, CHANNEL, ENCODING, size)
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "microphone")
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            vad.release()
            tell { failed(VoiceSession.Failure.NoMicrophone) }
            return
        }
        try {
            record.startRecording()
            listen(record, vad, r, history)
        } finally {
            record.stop()
            record.release()
            // the last stretch, cut short by the pause
            vad.flush()
            drain(vad, r, history)
            vad.release()
        }
    }

    private suspend fun listen(record: AudioRecord, vad: Vad, r: OfflineRecognizer, history: AudioHistory) {
        val buffer = FloatArray(VoiceEngine.WINDOW)
        var speaking = false
        var windows = 0
        while (currentCoroutineContext().isActive) {
            val n = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (n < 0) {
                tell { failed(VoiceSession.Failure.NoMicrophone) }
                return
            }
            if (n == 0) continue
            val samples = buffer.copyOf(n)
            history.add(samples)
            vad.acceptWaveform(samples)
            val now = vad.isSpeechDetected()
            if (now && !speaking) tell { speechStarted() }
            speaking = now
            drain(vad, r, history)
            if (++windows % LEVEL_EVERY == 0) {
                val level = rms(samples)
                tell { level(level) }
            }
        }
    }

    private fun drain(vad: Vad, r: OfflineRecognizer, history: AudioHistory) {
        while (!vad.empty()) {
            val samples = VoiceEngine.nextStretch(vad, history)
            tell { speechEnded() }
            scope.launch(decoder) {
                val text = try {
                    val stream = next ?: VoiceEngine.stream(r, words)
                    next = null
                    VoiceEngine.recognize(r, stream, samples)
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // one stretch lost, not the panel
                    Timber.w(e, "recognize")
                    ""
                }
                tell(stretch = true) { recognized(text) }
                // the next one's, made while it is said, once this one's text is out
                next = try {
                    VoiceEngine.stream(r, words)
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Timber.w(e, "voice stream")
                    null
                }
            }
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
        private const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT
        private const val LEVEL_EVERY = 3
        private const val LEVEL_SCALE = 5f
    }
}
