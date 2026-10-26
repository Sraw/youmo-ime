/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import timber.log.Timber
import java.util.Collections
import java.util.Locale
import kotlin.math.log10
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
    private val language: VoiceChoice.Language,
) : VoiceHoldSession.Listening {

    enum class Failure { NoModel, NoMicrophone }

    interface Events {
        fun loaded()
        fun failed(why: Failure)
        /** the microphone really on: its first sample not zero (VoiceOnset); 正在听 from here */
        fun ready()

        /** 2 s of nothing but zeroes, and still none other: the microphone gives no sound */
        fun silent()
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
    // read on the decoder too: no stream made for a next stretch once the microphone is closed
    @Volatile
    private var capture: Job? = null
    // read off the main thread too, as the models load
    @Volatile
    private var closed = false
    // the lift's uptime in ms, from the main thread: the capture thread stops VoiceCaptureEnd's tail after it
    @Volatile
    private var liftAt: Long? = null
    // start()'s uptime: the debug log's time to the first sample not zero
    @Volatile
    private var startedAt = 0L

    private fun tell(event: Events.() -> Unit) {
        main.post { if (!closed) events.event() }
    }

    // a failure logged and [fallback] in its place: one part lost, not the panel
    private inline fun <T> orFallback(tag: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Timber.w(e, tag)
        fallback
    }

    override fun load() {
        // no VAD made here: start() follows at once and takes or makes its own (a second beside it
        // only fought the cold start for the CPU); the next opening's is made as this one closes
        scope.launch {
            // asked for while the models load, not after: the engine may be busy a while
            val user = async { userHotwords() }
            val loaded = try {
                VoiceEngine.acquire(assets)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                // an Error too (its library not loading, memory running out); a model missing from
                // the assets is no exception: sherpa-onnx ends the process (file-utils.cc)
                Timber.w(e, "voice model")
                user.cancel()
                // what waits for the models: dropped, not waiting for good
                ready.cancel()
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
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                // memory running out for the graph of hotwords too: the models, not the microphone
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
    private val safeLanguage = object : VoiceChoice.Language {
        override suspend fun boundaries(texts: List<String>): Map<String, IntArray> =
            orFallback("voice word boundaries", emptyMap()) { language.boundaries(texts) }

        override suspend fun logProbs(texts: List<String>): FloatArray? =
            orFallback("voice log probs", null) { language.logProbs(texts) }
    }

    // voice input without them, not none
    private suspend fun userHotwords() = orFallback("voice hotwords", VoiceHotwords.Words.NONE) { hotwords() }

    /**
     * Opens the microphone; the caller has checked the permission. The models may still be
     * loading: what is heard meanwhile is recognized once they are in.
     */
    override fun start() {
        if (closed || capture?.isActive == true) return
        // a lift is this opening's: the next one is not stopped by the last one's
        liftAt = null
        startedAt = SystemClock.uptimeMillis()
        // the last one still closing, quickly paused and resumed: one microphone at a time
        val last = capture
        capture = scope.launch {
            last?.join()
            record()
        }
    }

    /**
     * Closes the microphone a tail after [liftedAt] (the lift's uptime, ms) and, once what was
     * heard is all recognized and told, [done]: the finger lifted from holding to talk. The tail
     * keeps the last word's sound still on its way in, said as the finger lifted. Not if closed meanwhile.
     */
    override fun finish(liftedAt: Long, done: () -> Unit) {
        liftAt = liftedAt
        val last = capture
        scope.launch {
            last?.join()
            stillPending().joinAll()
            main.post { if (!closed) done() }
        }
    }

    /** Closes the microphone now, the tail too; what was heard up to now is still recognized and typed. */
    override fun cut() {
        capture?.cancel()
    }

    /**
     * Closes the microphone, for good: nothing more is told. The recognizer is let go once what
     * was heard is recognized.
     */
    override fun close() {
        if (closed) return
        closed = true
        cut()
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
        val opening = Opening(record.bufferSizeInFrames, history)
        // the VAD on its way handed on as the next spare: no second one built beside it
        var handedOn = false
        try {
            record.startRecording()
            // the spare made ahead, else read in beside the reading: the microphone is read from
            // the moment it is on, whatever loads, and what is said meanwhile waits in the backlog
            opening.vadSoon = scope.async { vadOrTold() }
            listen(record, history, opening)
        } finally {
            record.stop()
            record.release()
            // closed, cut or lifted before the VAD came: it is still heard (cut and lift type what was said)
            withContext(NonCancellable) {
                // closed for good: nothing more is told, what was held is not worked through either
                if (closed) opening.handoff.close()
                val soon = opening.vadSoon
                if (closed && opening.handoff.vad == null && soon != null) {
                    // not waited for (a cold model load) only to be let go, the next start() waiting
                    // on this one: kept as the next opening's spare when it comes
                    soon.invokeOnCompletion { e -> if (e == null) soon.vadOrNull()?.let(VoiceEngine::offerVad) }
                    opening.vadSoon = null
                    handedOn = true
                }
                val late = if (opening.handoff.vad != null) null else runCatching { opening.vadSoon?.await() }.getOrNull()
                // the last stretch, cut short by the pause, flushed out
                (opening.handoff.end(late?.let(::SherpaVad)) as SherpaVad?)?.vad?.release()
            }
            // numbers only, nothing said: the UAT's check of the tail's timing and the level (uat/12 VO-23/24)
            if (BuildConfig.DEBUG) opening.log()
            // the next opening's, made now: not waited for then
            if (!handedOn) scope.launch { orFallback("voice vad", Unit) { VoiceEngine.prepareVad(assets) } }
        }
    }

    /**
     * This opening's VAD (VoiceEngine.takeVad); null if it could not be made, told as the models'
     * failing, once, before or after the lift alike (the microphone is fine: not NoMicrophone).
     */
    private fun vadOrTold(): Vad? = try {
        VoiceEngine.takeVad(assets)
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
        // an Error too: its library not loading, memory running out
        Timber.w(e, "voice vad")
        tell { failed(Failure.NoModel) }
        null
    }

    // a completed one's; its failure was told already
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Deferred<Vad?>.vadOrNull(): Vad? = runCatching { getCompleted() }.getOrNull()

    /** sherpa-onnx's VAD as VoiceVadHandoff takes it */
    private class SherpaVad(val vad: Vad) : VoiceVadHandoff.Vad<SpeechSegment> {
        override fun accept(samples: FloatArray) = vad.acceptWaveform(samples)
        override fun isSpeech() = vad.isSpeechDetected()
        override fun empty() = vad.empty()
        override fun front() = vad.front()
        override fun pop() = vad.pop()
        override fun flush() = vad.flush()
        override fun start(segment: SpeechSegment) = segment.start
    }

    /** One microphone opening's: the gain, where it stops, the VAD's handoff, and the debug log's numbers. */
    private inner class Opening(bufferFrames: Int, history: AudioHistory) {
        val gain = VoiceGain(VoiceEngine.SAMPLE_RATE)
        val end = VoiceCaptureEnd(VoiceEngine.SAMPLE_RATE, bufferFrames)
        val onset = VoiceOnset(VoiceEngine.SAMPLE_RATE)
        var vadSoon: Deferred<Vad?>? = null

        // what is read before the VAD is in held up to a stretch's longest (VoiceEngine.MAX_SPEECH_S)
        val handoff = VoiceVadHandoff(
            VoiceEngine.SAMPLE_RATE * VoiceEngine.MAX_SPEECH_S,
            object : VoiceVadHandoff.Events<SpeechSegment> {
                override fun speechStarted() {
                    tell { speechStarted() }
                    // the UAT's cue: VO-23 times its lift from here, on the device, not from the host's
                    // player, whose way into the emulator's microphone varies by ±500 ms
                    if (BuildConfig.DEBUG) Timber.d("voice speech started")
                }

                override fun stretch(segment: SpeechSegment, dropped: Long) {
                    val samples = VoiceEngine.stretch(segment, history, dropped)
                    tell { speechEnded() }
                    // the stretch before it suspended (its words split on the fcitx thread): still in order
                    val job = scope.launch(decoder) { serial.withLock { recognizeStretch(samples) } }
                    pending += job
                    job.invokeOnCompletion { pending -= job }
                }
            },
        )

        // Locale.ROOT: uat/scripts/12-voice.py parses it, a decimal comma would not
        fun log() = Timber.d(
            String.format(
                Locale.ROOT,
                "voice capture: liftFrame=%s stopAt=%s read=%d segment.start=%d segments=%d envPeak=%s peakGainDb=%.1f backlogDropped=%d",
                end.liftFrame, end.stopAt, end.read, handoff.lastStart, handoff.segments,
                gain.envPeak?.let { String.format(Locale.ROOT, "%.1f", it) }, 20 * log10(gain.peakGain), handoff.dropped,
            )
        )
    }

    /** The microphone, in the first of [ENCODINGS] it records in; null if in none. */
    private fun open(): AudioRecord? {
        for ((encoding, bytes) in ENCODINGS) {
            val min = AudioRecord.getMinBufferSize(VoiceEngine.SAMPLE_RATE, CHANNEL, encoding)
            // two seconds' room, should the reading fall behind; a HAL refusing that much still
            // records with its least rather than not at all
            for (size in listOf(maxOf(min, VoiceEngine.SAMPLE_RATE * bytes * BUFFER_S), min).distinct()) {
                if (size <= 0) continue
                val record = try {
                    record(encoding, size)
                } catch (e: SecurityException) {
                    // the permission taken back since the caller checked it: in no encoding either
                    Timber.w(e, "microphone")
                    return null
                }
                if (record != null) return record
            }
        }
        return null
    }

    /** The record in [encoding] with [size] bytes of buffer; null if it does not record so. */
    @SuppressLint("MissingPermission")
    private fun record(encoding: Int, size: Int): AudioRecord? {
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, VoiceEngine.SAMPLE_RATE, CHANNEL, encoding, size)
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "microphone")
            return null
        }
        if (record.state == AudioRecord.STATE_INITIALIZED) return record
        record.release()
        return null
    }

    private suspend fun listen(record: AudioRecord, history: AudioHistory, opening: Opening) {
        val buffer = FloatArray(VoiceEngine.WINDOW)
        val shorts = if (record.audioFormat == AudioFormat.ENCODING_PCM_16BIT) ShortArray(VoiceEngine.WINDOW) else null
        val timestamp = AudioTimestamp()
        var windows = 0
        while (currentCoroutineContext().isActive) {
            val n = read(record, buffer, shorts)
            if (n < 0) {
                // lifted already, in the tail: what was said is in, typed as at the tail's end; the
                // microphone failing is told only while it was still being spoken into
                if (liftAt == null) tell { failed(Failure.NoMicrophone) }
                return
            }
            val over = opening.end.window(n, SystemClock.uptimeMillis(), liftAt) { stamp(record, timestamp, it) }
            if (n == 0) {
                if (over) return
                continue
            }
            val onset = opening.onset.window(buffer, n)
            if (opening.onset.silent()) tell { silent() }
            if (onset) {
                tell { ready() }
                // the UAT's (VO-26): from start() to the first sample not zero
                if (BuildConfig.DEBUG) Timber.d("voice ready: fromStartMs=%d", SystemClock.uptimeMillis() - startedAt)
            }
            val samples = buffer.copyOf(n)
            // before all of it: the VAD, the lead-in, the recognizer and the level hear the same
            opening.gain.apply(samples)
            history.add(samples)
            opening.handoff.window(samples)
            if (opening.handoff.vad == null && opening.vadSoon?.isCompleted == true) {
                // in now: what was read meanwhile first, in order; none made: told (vadOrTold), nothing to hear with
                val made = opening.vadSoon?.await() ?: return
                opening.handoff.arrived(SherpaVad(made))
            }
            if (++windows % LEVEL_EVERY == 0) {
                val level = rms(samples)
                tell { level(level) }
            }
            if (over) return
        }
    }

    /**
     * The lift seen: where it fell in the record, from the record's timestamp (both on
     * CLOCK_MONOTONIC, as uptime is), so the tail counts from the finger, not from the capture
     * path's latency later; without one, from what is read.
     */
    private fun stamp(record: AudioRecord, timestamp: AudioTimestamp, at: Long): VoiceCaptureEnd.Stamp? {
        if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) return null
        val frame = VoiceCaptureEnd.frameAt(timestamp.framePosition, timestamp.nanoTime, at * NS_PER_MS, VoiceEngine.SAMPLE_RATE)
        return frame?.let { VoiceCaptureEnd.Stamp(it, timestamp.framePosition) }
    }

    /** A window into [buffer], as [AudioRecord.read]: a 16-bit record's through [shorts], to -1 to 1. */
    private fun read(record: AudioRecord, buffer: FloatArray, shorts: ShortArray?): Int {
        if (shorts == null) return record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
        val n = record.read(shorts, 0, shorts.size, AudioRecord.READ_BLOCKING)
        for (i in 0 until n) buffer[i] = shorts[i] / PCM_16BIT_SCALE
        return n
    }

    /** A stretch to its text, told; on the [decoder], within [serial]. */
    private suspend fun recognizeStretch(samples: FloatArray) {
        // ready cancelled, the stretch dropped untold: the models never came in (told as such) or it closed first
        val recognizer = ready.await()
        // closed meanwhile: its text would not be told, so it is not worked out either
        if (closed) return
        // one stretch lost, not the panel
        val text = orFallback("recognize", "") {
            val stream = next ?: VoiceEngine.stream(recognizer, words)
            next = null
            VoiceEngine.recognize(recognizer, stream, samples, words, safeLanguage)
        }
        tell { recognized(text) }
        // the next one's, made while it is said, once this one's text is out: none once the
        // microphone is closed, nothing more to hear and finish waiting for it
        if (capture?.isActive == true) {
            next = orFallback("voice stream", null) { VoiceEngine.stream(recognizer, words) }
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
        private const val NS_PER_MS = 1_000_000L
        private const val BUFFER_S = 2
    }
}
