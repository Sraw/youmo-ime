/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.Vad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.BuildConfig
import timber.log.Timber
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The microphone and the streaming recognizer, for one talk: reads the microphone while open and
 * hands what it reads to the recognizer on a thread of its own, which decodes it a chunk at a time,
 * closes a segment where its endpoint rules say ([VoiceEngine.choose] picking its text) and polishes
 * the text as it streams ([VoiceLive]). Tells [events] of it all on the main thread, until [close]d.
 * Used from the main thread.
 */
class VoiceListener(
    private val events: Events,
    /** the user's words to listen for and blocked (VoiceHotwords.ofUser), asked for as the models load */
    private val hotwords: suspend () -> VoiceHotwords.Words,
    /** the pinyin engine's models (VoiceEngine.language): to rank what is heard, and for the blocked words */
    private val language: VoiceChoice.Language,
) : VoiceSession.Listening {

    enum class Failure { NoModel, NoMicrophone }

    interface Events {
        fun loaded()
        fun failed(why: Failure)
        /** the microphone really on: its first sample not zero (VoiceOnset); 正在听 from here */
        fun ready()

        /** 2 s of nothing but zeroes, and still none other: the microphone gives no sound */
        fun silent()

        /** what was said so far, as it shows ([VoiceLive]): the segments closed, polished, and the one being said */
        fun heard(heard: VoiceLive.Heard)

        /** how loud, 0 to 1, a few times a second */
        fun level(level: Float)
    }

    // a native error is the session's to show, not the keyboard's to crash on
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        Timber.w(e, "voice")
        tell { failed(Failure.NoMicrophone) }
    })

    // the recognizer's thread: the stream, the hotwords and VoiceLive are its alone. One for every
    // listener (VoiceEngine.decoder): the last talk's tail still decoding does not overlap the next
    // one's stream made on the same recognizer
    private val decoder = VoiceEngine.decoder

    private val main = Handler(Looper.getMainLooper())

    // what the microphone read, in order, for the recognizer; closed when the microphone is. Read
    // before the models are in too: a hold may start talking at once, nothing of it is lost
    private val audio = Channel<FloatArray>(Channel.UNLIMITED)

    // the models once in, the user's words with them; cancelled if they never come
    private val ready = CompletableDeferred<Pair<VoiceEngine.Models, VoiceHotwords.Words>>()

    // what was said (VoiceLive.finished, before the clean-up), once the microphone is closed and all
    // of it decoded; true: the raw text, the polish having failed
    private val said = CompletableDeferred<Pair<String, Boolean>?>()

    private var acquired = false
    private var decoding: Job? = null
    private var capture: Job? = null
    // read off the main thread too, as the models load and on the recognizer's
    @Volatile
    private var closed = false
    // the lift's uptime in ms, from the main thread: the capture thread stops VoiceCaptureEnd's tail after it
    @Volatile
    private var liftAt: Long? = null

    // what the microphone heard, window by window, for the recognizer to ask whether a segment was
    // the room (a lifted noise floor recognized as 「I'm sorry.」): fed on the capture thread
    private val quiet = VoiceQuiet()
    // start()'s uptime: the debug log's time to the first sample not zero
    @Volatile
    private var startedAt = 0L

    private fun tell(event: Events.() -> Unit) {
        main.post { if (!closed) events.event() }
    }

    // a failure logged and [fallback] in its place: one part lost, not the talk
    private inline fun <T> orFallback(tag: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Timber.w(e, tag)
        fallback
    }

    override fun load() {
        // the opening's VAD made beside the models: none, or failing, it is made or done without then
        // (an Error too, not only an Exception: on this scope it would end the process)
        scope.launch(Dispatchers.IO) { VoiceEngine.prepareVad { Timber.w(it, "voice vad") } }
        scope.launch {
            // asked for while the models load, not after: the engine may be busy a while
            // an Error of it caught in here: thrown out of the async it would cancel this launch and reach the
            // scope's handler (told as the microphone's failure) whichever message came first
            val user = async { caught { userHotwords() } }
            val loaded = try {
                VoiceEngine.acquire()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                // an Error too (its library not loading, memory running out); the voice pack gone
                // (PackUnavailableException), or a file of it (sherpa-onnx's config check, an exception)
                Timber.w(e, "voice model")
                user.cancel()
                ready.cancel()
                // the spare made beside them: not held for good, no idle timer being set without users
                VoiceEngine.letGoVadIfIdle()
                tell { failed(Failure.NoModel) }
                return@launch
            }
            val words = try {
                user.await()
            } catch (e: CancellationException) {
                // closed while they loaded: the models let go, the cancellation not taken for a failure
                ready.cancel()
                main.post { VoiceEngine.release() }
                throw e
            }.getOrElse { e ->
                // an Error past the acquire (userHotwords falls back on exceptions only): the models
                // let go, not held for good, and the decoder not left waiting for them; told as the
                // models' failure, not the microphone's (the scope's handler)
                Timber.w(e, "voice hotwords")
                ready.cancel()
                main.post { VoiceEngine.release() }
                tell { failed(Failure.NoModel) }
                return@launch
            }
            main.post {
                if (closed) {
                    // closed while they loaded: not used, let go
                    ready.cancel()
                    VoiceEngine.release()
                } else {
                    acquired = true
                    ready.complete(loaded to words)
                    events.loaded()
                }
            }
        }
        decoding = scope.launch(decoder) { decode() }
    }

    // the engine failing: the text as heard, and each taken as having a blocked word
    private val safeLanguage = object : VoiceChoice.Language {
        override suspend fun boundaries(texts: List<String>): Map<String, IntArray> =
            orFallback("voice word boundaries", emptyMap()) { language.boundaries(texts) }

        override suspend fun logProbs(texts: List<String>): FloatArray? =
            orFallback("voice log probs", null) { language.logProbs(texts) }
    }

    // [block]'s result, or what it threw but a cancellation (which goes on, as cancellations must)
    private suspend fun <T> caught(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
        Result.failure(e)
    }

    // voice input without them, not none: their read cancelled from elsewhere too (VoiceEngine's
    // shared read, the host's scope ending), which is not this listener closing
    private suspend fun userHotwords() = cancelledElsewhereAs(VoiceHotwords.Words.NONE) {
        orFallback("voice hotwords", VoiceHotwords.Words.NONE) { hotwords() }
    }

    /**
     * Opens the microphone, once; the caller has checked the permission. The models may still be
     * loading: what is heard meanwhile is recognized once they are in.
     */
    override fun start() {
        if (closed || capture != null) return
        startedAt = SystemClock.uptimeMillis()
        // closed on completion, not in a finally: a launch cancelled before it ran never runs its
        // finally, and the decoder would wait on the microphone for good, the models with it
        capture = scope.launch { record() }.also { it.invokeOnCompletion { audio.close() } }
    }

    /**
     * Closes the microphone a tail after [stoppedAt] (the lift's or the tap's uptime, ms) and,
     * once what was heard is all recognized, [done] with what was said (null: nothing came back):
     * the finger lifted from holding to talk, or the talk tapped to a stop. The tail keeps the last
     * word's sound still on its way in, said as it stopped. Not if closed meanwhile.
     */
    override fun finish(stoppedAt: Long, done: (said: String?, raw: Boolean) -> Unit) {
        liftAt = stoppedAt
        scope.launch {
            val text = runCatching { said.await() }.getOrNull()
            main.post { if (!closed) done(text?.first, text?.second == true) }
        }
    }

    /** Closes the microphone now, the tail too; what was heard up to now is still recognized and typed. */
    override fun cut() {
        capture?.cancel()
        // never started: nothing more to hear
        if (capture == null) audio.close()
    }

    /** Closes the microphone, for good: nothing more is told. The models are let go once the recognizer stops. */
    override fun close() {
        if (closed) return
        closed = true
        cut()
        // still loading: once in, they are let go there
        if (!acquired) return
        acquired = false
        val last = decoding
        scope.launch {
            last?.join()
            main.post { VoiceEngine.release() }
        }
    }

    /**
     * The recognizer's loop, on the [decoder]: what the microphone read, in order, decoded as it
     * comes; once it is closed, the tail padded, the last decoded, and what was said [said].
     */
    private suspend fun decode() {
        val (models, words) = try {
            ready.await()
        } catch (e: CancellationException) {
            // the models never came in (told as such) or it closed first: nothing to type, said so
            said.cancel(e)
            return
        }
        val recognizer = models.recognizer
        val live = VoiceLive(models.polisher?.stream())
        // the polish failing: the raw text as it is after this step (read once the step has run), told as such
        fun polished(step: () -> VoiceLive.Heard) =
            show(orFallback<VoiceLive.Heard?>("voice polish", null) { step() } ?: VoiceLive.Heard(live.raw, 0, fallback = true))
        val stream = try {
            VoiceEngine.stream(recognizer, words)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // memory running out for the graph of hotwords too: the models, not the microphone
            Timber.w(e, "voice stream")
            tell { failed(Failure.NoModel) }
            said.cancel()
            return
        }
        try {
            val segment = VoiceSegments(
                Recognition(recognizer, stream),
                VoiceEngine.SAMPLE_RATE,
                choose = { result -> orFallback("voice choice", result.text) { VoiceEngine.choose(result, words, safeLanguage) } },
                live = { heard -> polished { live.heard(heard) } },
                closed = { text -> polished { live.closed(text) } },
                quiet,
            )
            for (samples in audio) {
                if (closed) return
                segment.accept(samples)
            }
            if (closed) return
            segment.finish(FloatArray(VoiceEngine.TAIL_PADDING))
            // numbers only, nothing said: the UAT's measure of the lift against the speech's end (uat/12 VO-23/24)
            if (BuildConfig.DEBUG) Timber.d("voice segments: speechStart=%s segments=%d", segment.speechStart, segment.kept)
            // the polish failing: the raw text, told as such (what was typed as it was said is polished)
            val polished = orFallback<String?>("voice polish", null) { live.finished() }
            said.complete(if (polished != null) polished to false else live.raw to true)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // the recognizer or the polish failing natively: the models', not the microphone's
            Timber.w(e, "voice decode")
            tell { failed(Failure.NoModel) }
        } finally {
            stream.release()
            said.cancel()
        }
    }

    /** [stream] of [recognizer] as VoiceSegments drives it */
    private class Recognition(private val recognizer: OnlineRecognizer, private val stream: OnlineStream) : VoiceRecognition<OnlineRecognizerResult> {
        override fun accept(samples: FloatArray) = stream.acceptWaveform(samples, VoiceEngine.SAMPLE_RATE)
        override fun inputFinished() = stream.inputFinished()
        override fun isReady() = recognizer.isReady(stream)
        override fun decode() = recognizer.decode(stream)
        override fun isEndpoint() = recognizer.isEndpoint(stream)
        override fun reset() = recognizer.reset(stream)
        override fun result() = recognizer.getResult(stream)
        override fun text(result: OnlineRecognizerResult) = result.text
        override fun firstTime(result: OnlineRecognizerResult) = result.timestamps.firstOrNull()
    }

    private fun show(shown: VoiceLive.Heard) = tell { heard(shown) }

    private suspend fun record() {
        val record = open()
        if (record == null) {
            tell { failed(Failure.NoMicrophone) }
            return
        }
        val opening = Opening(record.bufferSizeInFrames)
        // whether a window is speech (VoiceQuiet), on the gained samples: silero did not fire at the
        // -50 to -60 dBFS of soft speech before the gain (specs/app.md). Taken once the microphone is
        // on: the record's 2 s buffer holds the first words meanwhile; none (its model failing): all kept
        VoiceOpening.run(
            start = { record.startRecording() },
            load = { VoiceEngine.takeVad() },
            use = { listen(record, opening, it) },
            stop = {
                try {
                    record.stop()
                } catch (e: IllegalStateException) {
                    // what was read is in already: a stop failing is no microphone failure to end the talk on
                    Timber.w(e, "voice record stop")
                } finally {
                    record.release()
                    // numbers only, nothing said: the UAT's check of the tail's timing and the level (uat/12 VO-23/24)
                    if (BuildConfig.DEBUG) opening.log()
                }
            },
            unload = VoiceEngine::offerVad,
            failed = { Timber.w(it, "voice vad") },
        )
    }

    /** One microphone opening's: the gain, where it stops, and the debug log's numbers. */
    private class Opening(bufferFrames: Int) {
        val gain = VoiceGain(VoiceEngine.SAMPLE_RATE)
        val end = VoiceCaptureEnd(VoiceEngine.SAMPLE_RATE, bufferFrames)
        val onset = VoiceOnset(VoiceEngine.SAMPLE_RATE)
        private var vadWindows = 0
        private var vadNs = 0L
        private var vadMostNs = 0L

        /** a window's VAD time, for the log: a phone's to be measured */
        fun vadTook(ns: Long) {
            vadWindows++
            vadNs += ns
            vadMostNs = maxOf(vadMostNs, ns)
        }

        // Locale.ROOT: uat/scripts/12-voice.py parses it, a decimal comma would not
        fun log() = Timber.d(
            String.format(
                Locale.ROOT,
                "voice capture: liftFrame=%s stopAt=%s read=%d envPeak=%s peakGainDb=%.1f vadWindows=%d vadMeanUs=%d vadMostUs=%d",
                end.liftFrame, end.stopAt, end.read,
                gain.envPeak?.let { String.format(Locale.ROOT, "%.1f", it) }, 20 * log10(gain.peakGain),
                vadWindows, if (vadWindows > 0) vadNs / vadWindows / NS_PER_US else 0L, vadMostNs / NS_PER_US,
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

    private suspend fun listen(record: AudioRecord, opening: Opening, vad: Vad?) {
        val buffer = FloatArray(VoiceEngine.WINDOW)
        val shorts = if (record.audioFormat == AudioFormat.ENCODING_PCM_16BIT) ShortArray(VoiceEngine.WINDOW) else null
        val timestamp = AudioTimestamp()
        // the device UAT's clips in place of what the microphone hears (VoiceClipMic); none in a release
        val clips = if (BuildConfig.DEBUG) VoiceClipMic.feed() else null
        var windows = 0
        try {
            while (currentCoroutineContext().isActive) {
                val n = clips?.read(buffer) ?: read(record, buffer, shorts)
                if (n < 0) {
                    // lifted already, in the tail: what was said is in, typed as at the tail's end; the
                    // microphone failing is told only while it was still being spoken into
                    if (liftAt == null) tell { failed(Failure.NoMicrophone) }
                    return
                }
                val over = opening.end.window(n, SystemClock.uptimeMillis(), liftAt) {
                    // the clips' frames are not the record's: counted from what is read
                    if (clips == null) stamp(record, timestamp, it) else null
                }
                if (n == 0) {
                    if (over) return
                    // nothing read (a read that does not block): not a core spun until the lift
                    delay(EMPTY_READ_WAIT_MS)
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
                // before all of it: the recognizer and the level hear the same
                opening.gain.apply(samples)
                quiet.window(buffer, n, vad?.let { speech(it, samples, opening) })
                audio.trySend(samples)
                if (++windows % LEVEL_EVERY == 0) {
                    val level = rms(samples)
                    tell { level(level) }
                }
                if (over) return
            }
        } finally {
            // ended mid-clip (a lift in the speech): nothing left playing for uat.py to wait on
            clips?.close()
        }
    }

    /** Whether [vad] is in speech after [samples]; its segments are not used. Its time counted for the debug log. */
    private fun speech(vad: Vad, samples: FloatArray, opening: Opening): Boolean {
        val at = System.nanoTime()
        vad.acceptWaveform(samples)
        if (!vad.empty()) vad.clear()
        val speech = vad.isSpeechDetected()
        opening.vadTook(System.nanoTime() - at)
        return speech
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
        private const val NS_PER_US = 1_000L
        private const val BUFFER_S = 2
        private const val EMPTY_READ_WAIT_MS = 5L
    }
}

/**
 * [block]'s result, or [fallback] where it threw a cancellation while the caller is still active: a
 * read it awaited cancelled from elsewhere, not the caller cancelled (that one goes on, as cancellations must).
 */
internal suspend fun <T> cancelledElsewhereAs(fallback: T, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    if (!currentCoroutineContext().isActive) throw e
    Timber.w(e, "cancelled elsewhere")
    fallback
}
