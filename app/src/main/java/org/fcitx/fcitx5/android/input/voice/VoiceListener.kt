/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
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
import kotlinx.coroutines.cancel
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
 * One talk: reads the microphone and hands it to the streaming recognizer on its own thread; segments
 * close by its endpoint rules ([VoiceEngine.choose]) and the text is polished as it streams
 * ([VoiceLive]). Tells [events] on the main thread until [close]d. Used from the main thread.
 */
class VoiceListener(
    private val events: Events,
    private val hotwords: suspend () -> VoiceHotwords.Words,
    private val language: VoiceChoice.Language,
    // null: other apps' playback goes on (语音输入时暂停播放 off)
    audio: AudioManager? = null,
) : VoiceSession.Listening {

    enum class Failure { NoModel, NoMicrophone, Recognition }

    interface Events {
        fun loaded()
        fun failed(why: Failure)
        fun ready()

        fun silent()

        fun heard(heard: VoiceLive.Heard)

        fun level(level: Float)

        /** another app took the audio focus while recording: a call ringing, an alarm */
        fun interrupted()
    }

    // a native error is the session's to show, not the keyboard's to crash on; microphone and model
    // failures are told where they happen
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        Timber.w(e, "voice")
        tell { failed(Failure.Recognition) }
    })

    // the stream, the hotwords and VoiceLive are this thread's alone. Shared by every listener: the last
    // talk's tail still decoding does not overlap the next one's stream
    private val decoder = VoiceEngine.decoder

    private val main = Handler(Looper.getMainLooper())

    // read before the models are in too: a hold may start talking at once, nothing of it is lost
    private val audio = Channel<FloatArray>(Channel.UNLIMITED)

    private val ready = CompletableDeferred<Pair<VoiceEngine.Models, VoiceHotwords.Words>>()

    // before the clean-up, once all of it is decoded; true: the raw text, the polish having failed
    private val said = CompletableDeferred<Pair<String, Boolean>?>()

    private class Restart(val to: Int, val upTo: VoiceLive.Mark?)

    @Volatile
    private var restarting: Restart? = null

    @Volatile
    private var liveUtterance = 0

    @Volatile
    override var utterance = 0
        private set

    // asked again before the recognizer took the last: none of the new utterance showed, so the
    // last one's mark still says what went
    @Synchronized
    override fun restart(to: Int, upTo: VoiceLive.Mark?) {
        val last = restarting
        restarting = Restart(to, upTo ?: last?.takeIf { it.to > liveUtterance }?.upTo)
    }

    @Synchronized
    private fun restartIn(live: VoiceLive) {
        restarting?.let { live.restart(it.to, it.upTo) }
        liveUtterance = live.utterance
    }

    @Volatile
    override var polishFailure: VoiceSession.PolishFailure? = null
        private set

    private var acquired = false
    private var decoding: Job? = null
    private var capture: Job? = null
    // [close] stops it so a read blocked in the HAL returns (VoiceCaptureEnd), not left recording
    @Volatile
    private var recording: AudioRecord? = null
    @Volatile
    private var closed = false
    @Volatile
    private var liftAt: Long? = null

    private val focus = audio?.let { VoiceFocus(it) { tell { interrupted() } } }

    private val quiet = VoiceQuiet()
    @Volatile
    private var startedAt = 0L
    @Volatile
    private var readyAt = 0L
    @Volatile
    private var shownAt = 0L
    @Volatile
    private var capturedAt = 0L

    private fun tell(event: Events.() -> Unit) {
        main.post { if (!closed) events.event() }
    }

    private inline fun <T> orFallback(tag: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Timber.w(e, tag)
        fallback
    }

    override fun load() {
        // an Error too, not only an Exception: on this scope it would end the process
        scope.launch(Dispatchers.IO) { VoiceEngine.prepareVad { Timber.w(it, "voice vad") } }
        scope.launch {
            // asked while the models load: the engine may be busy a while. An Error is caught in here: thrown out
            // of the async it would cancel this launch and be told as recognizing's failure
            val user = async { caught { userHotwords() } }
            val loaded = try {
                VoiceEngine.acquire()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                // an Error too (its library not loading, memory running out), or the pack gone
                Timber.w(e, "voice model")
                user.cancel()
                ready.cancel()
                VoiceEngine.letGoVadIfIdle()
                tell { failed(Failure.NoModel) }
                return@launch
            }
            val words = try {
                user.await()
            } catch (e: CancellationException) {
                ready.cancel()
                main.post { VoiceEngine.release() }
                throw e
            }.getOrElse { e ->
                // an Error past the acquire (userHotwords falls back on exceptions only): let go, the decoder not left
                // waiting; told as the models' failure
                Timber.w(e, "voice hotwords")
                ready.cancel()
                main.post { VoiceEngine.release() }
                tell { failed(Failure.NoModel) }
                return@launch
            }
            main.post {
                if (closed) {
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

    private val safeLanguage = object : VoiceChoice.Language {
        override suspend fun boundaries(texts: List<String>): Map<String, IntArray> =
            orFallback("voice word boundaries", emptyMap()) { language.boundaries(texts) }

        override suspend fun logProbs(texts: List<String>): FloatArray? =
            orFallback("voice log probs", null) { language.logProbs(texts) }

        override suspend fun homophonesAllowed(spans: List<VoiceHomophones.Span>): BooleanArray? =
            orFallback("voice homophones", null) { language.homophonesAllowed(spans) }
    }

    private suspend fun <T> caught(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
        Result.failure(e)
    }

    // a read cancelled from elsewhere (VoiceEngine's shared read, the host's scope) is not this listener closing
    private suspend fun userHotwords() = cancelledElsewhereAs(VoiceHotwords.Words.NONE) {
        orFallback("voice hotwords", VoiceHotwords.Words.NONE) { hotwords() }
    }

    override fun start() {
        if (closed || capture != null) return
        startedAt = SystemClock.uptimeMillis()
        // closed on completion, not in a finally: a launch cancelled before it ran never runs its finally, and
        // the decoder would wait on the microphone for good
        capture = scope.launch { record() }.also { it.invokeOnCompletion {
            capturedAt = SystemClock.uptimeMillis()
            audio.close()
        } }
    }

    /**
     * Closes the microphone a tail after [stoppedAt] (uptime ms), keeping the last word's sound still on
     * its way in; [done] once all is recognized (null: nothing came back). Not if closed meanwhile.
     */
    override fun finish(stoppedAt: Long, done: (said: String?, raw: Boolean) -> Unit) {
        liftAt = stoppedAt
        scope.launch {
            val text = runCatching { said.await() }.getOrNull()
            main.post { if (!closed) done(text?.first, text?.second == true) }
        }
    }

    override fun cut() {
        capture?.cancel()
        if (capture == null) audio.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        cut()
        // from here, not only on the capture thread: its own stop comes after a read that may not return.
        // Also before the models are in: the microphone is open from the press
        recording?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                Timber.w(e, "voice record stop on close")
            }
        }
        focus?.giveBack()
        if (!acquired) return
        acquired = false
        val last = decoding
        scope.launch {
            last?.join()
            main.post { VoiceEngine.release() }
            scope.cancel()
        }
    }

    private suspend fun decode() {
        val (models, words) = try {
            ready.await()
        } catch (e: CancellationException) {
            said.cancel(e)
            return
        }
        // all of it in the try: a throw before the stream left [said] unfinished and [finish] waiting for good
        var stream: OnlineStream? = null
        try {
            val recognizer = models.recognizer
            val live = VoiceLive(models.polisher?.stream())
            if (models.polisher == null) polishFailure = VoiceSession.PolishFailure.LOAD
            // the user's words put in after the polish, for what shows and what is typed alike: no word changes at the end
            val homophones = words.homophones.hold()
            val homophoneLexicon = VoiceHomophones.Lexicon { safeLanguage.homophonesAllowed(it) }
            suspend fun polished(step: () -> VoiceLive.Heard) {
                restartIn(live)
                val heard = orFallback<VoiceLive.Heard?>("voice polish", null) { step() }
                    ?: VoiceLive.Heard(live.raw, 0, fallback = true, utterance = live.utterance, upTo = live.mark()).also { polishFailure = VoiceSession.PolishFailure.RUN }
                show(homophones.replace(heard, homophoneLexicon))
            }
            val made = VoiceEngine.stream(recognizer, words)
            stream = made
            val segment = VoiceSegments(
                Recognition(recognizer, made),
                VoiceEngine.SAMPLE_RATE,
                choose = { result -> orFallback("voice choice", result.text) { VoiceEngine.choose(result, words, safeLanguage) } },
                live = { heard -> polished { live.heard(heard) } },
                closed = { text -> polished { live.closed(text) } },
                quiet,
            )
            var acceptMax = 0L
            for (samples in audio) {
                if (closed) return
                val t = if (BuildConfig.DEBUG) SystemClock.uptimeMillis() else 0L
                segment.accept(samples)
                if (BuildConfig.DEBUG) acceptMax = maxOf(acceptMax, SystemClock.uptimeMillis() - t)
            }
            if (closed) return
            val drained = SystemClock.uptimeMillis()
            segment.finish(FloatArray(VoiceEngine.TAIL_PADDING))
            val tailDecoded = SystemClock.uptimeMillis()
            // numbers only: UAT measures the lift against the speech's end
            if (BuildConfig.DEBUG) Timber.d("voice segments: speechStart=%s segments=%d", segment.speechStart, segment.kept)
            restartIn(live)
            utterance = live.utterance
            val polished = orFallback<String?>("voice polish", null) { live.finished() }
            // a live step's failure only showed raw text for a moment: the whole is polished now, so 语音整理 applied
            if (polished == null) {
                polishFailure = VoiceSession.PolishFailure.RUN
            } else if (polishFailure == VoiceSession.PolishFailure.RUN) {
                polishFailure = null
            }
            val polishedAt = SystemClock.uptimeMillis()
            val result = if (polished != null) homophones.replace(polished, homophoneLexicon) to false else homophones.replace(live.raw, homophoneLexicon) to true
            if (BuildConfig.DEBUG) logTiming(drained, tailDecoded, polishedAt, acceptMax)
            said.complete(result)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            Timber.w(e, "voice decode")
            tell { failed(Failure.Recognition) }
        } finally {
            stream?.release()
            said.cancel()
        }
    }

    private fun logTiming(drained: Long, tailDecoded: Long, polishedAt: Long, acceptMax: Long) {
        val end = SystemClock.uptimeMillis()
        val stop = liftAt
        val captured = capturedAt.takeIf { it > 0 }
        Timber.d(
            "voice timing: firstShownFromReadyMs=%d stopToClosedMs=%d closedToDrainedMs=%d tailDecodeMs=%d polishMs=%d homophonesMs=%d stopToSaidMs=%d acceptMaxMs=%d",
            if (shownAt > 0 && readyAt > 0) shownAt - readyAt else -1, if (stop != null && captured != null) captured - stop else -1,
            captured?.let { drained - it } ?: -1, tailDecoded - drained, polishedAt - tailDecoded, end - polishedAt,
            stop?.let { end - it } ?: -1, acceptMax,
        )
    }

    internal class Recognition(private val recognizer: OnlineRecognizer, private val stream: OnlineStream) : VoiceRecognition<OnlineRecognizerResult> {
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

    private fun show(shown: VoiceLive.Heard) {
        if (BuildConfig.DEBUG && shownAt == 0L && shown.text.isNotEmpty()) shownAt = SystemClock.uptimeMillis()
        tell { heard(shown) }
    }

    // a start or read failing is the microphone's failure, not recognizing's; once lifted, what was said is
    // in and typed
    private suspend fun record() {
        try {
            captureAudio()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "voice microphone")
            if (liftAt == null) tell { failed(Failure.NoMicrophone) }
        }
    }

    private suspend fun captureAudio() {
        val record = open()
        if (record == null) {
            tell { failed(Failure.NoMicrophone) }
            return
        }
        recording = record
        val opening = Opening(record.bufferSizeInFrames)
        // VAD on the gained samples: silero missed soft speech at -50 to -60 dBFS before the gain. The 2 s buffer
        // holds the first words meanwhile; no model: all kept
        VoiceOpening.run(
            start = {
                focus?.take()
                record.startRecording()
            },
            load = { VoiceEngine.takeVad() },
            use = { listen(record, opening, it) },
            stop = {
                try {
                    record.stop()
                } catch (e: IllegalStateException) {
                    Timber.w(e, "voice record stop")
                } finally {
                    focus?.giveBack()
                    recording = null
                    record.release()
                    // numbers only: UAT checks the tail's timing and the level
                    if (BuildConfig.DEBUG) opening.log()
                }
            },
            unload = VoiceEngine::offerVad,
            failed = { Timber.w(it, "voice vad") },
        )
    }

    private class Opening(bufferFrames: Int) {
        val gain = VoiceGain(VoiceEngine.SAMPLE_RATE)
        val end = VoiceCaptureEnd(VoiceEngine.SAMPLE_RATE, bufferFrames)
        val onset = VoiceOnset(VoiceEngine.SAMPLE_RATE)
        private var vadWindows = 0
        private var vadNs = 0L
        private var vadMostNs = 0L

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

    private fun open(): AudioRecord? {
        for ((encoding, bytes) in ENCODINGS) {
            val min = AudioRecord.getMinBufferSize(VoiceEngine.SAMPLE_RATE, CHANNEL, encoding)
            // two seconds' room, should the reading fall behind; a HAL refusing that still records with its least
            for (size in listOf(maxOf(min, VoiceEngine.SAMPLE_RATE * bytes * BUFFER_S), min).distinct()) {
                if (size <= 0) continue
                val record = try {
                    record(encoding, size)
                } catch (e: SecurityException) {
                    Timber.w(e, "microphone")
                    return null
                }
                if (record != null) return record
            }
        }
        return null
    }

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
        // the device UAT's clips in place of the microphone (VoiceClipMic); none in a release
        val clips = if (BuildConfig.DEBUG) VoiceClipMic.feed() else null
        var windows = 0
        // a detector failing on a window is dropped from there (all kept), logged; not told as the microphone's failure
        var detector = vad
        try {
            while (currentCoroutineContext().isActive) {
                val n = clips?.read(buffer) ?: read(record, buffer, shorts)
                if (n < 0) {
                    // lifted already, in the tail: what was said is typed; a failure is told only while still spoken into
                    if (liftAt == null) tell { failed(Failure.NoMicrophone) }
                    return
                }
                val over = opening.end.window(n, SystemClock.uptimeMillis(), liftAt) {
                    if (clips == null) stamp(record, timestamp, it) else null
                }
                if (n == 0) {
                    if (over) return
                    delay(EMPTY_READ_WAIT_MS)
                    continue
                }
                val onset = opening.onset.window(buffer, n)
                if (opening.onset.silent()) tell { silent() }
                if (onset) {
                    tell { ready() }
                    if (BuildConfig.DEBUG) {
                        readyAt = SystemClock.uptimeMillis()
                        Timber.d("voice ready: fromStartMs=%d", readyAt - startedAt)
                    }
                }
                val samples = buffer.copyOf(n)
                opening.gain.apply(samples)
                val speech = detector?.let {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        speech(it, samples, opening)
                    } catch (e: Exception) {
                        Timber.w(e, "voice vad")
                        detector = null
                        null
                    }
                }
                quiet.window(buffer, n, speech)
                audio.trySend(samples)
                if (++windows % LEVEL_EVERY == 0) {
                    val level = rms(samples)
                    tell { level(level) }
                }
                if (over) return
            }
        } finally {
            clips?.close()
        }
    }

    private fun speech(vad: Vad, samples: FloatArray, opening: Opening): Boolean {
        val at = System.nanoTime()
        vad.acceptWaveform(samples)
        if (!vad.empty()) vad.clear()
        val speech = vad.isSpeechDetected()
        opening.vadTook(System.nanoTime() - at)
        return speech
    }

    /**
     * From the record's timestamp (CLOCK_MONOTONIC, as uptime), so the tail counts from the finger, not
     * from the capture path's latency; without one, from what is read.
     */
    private fun stamp(record: AudioRecord, timestamp: AudioTimestamp, at: Long): VoiceCaptureEnd.Stamp? {
        if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) return null
        val frame = VoiceCaptureEnd.frameAt(timestamp.framePosition, timestamp.nanoTime, at * NS_PER_MS, VoiceEngine.SAMPLE_RATE)
        return frame?.let { VoiceCaptureEnd.Stamp(it, timestamp.framePosition) }
    }

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
        // float as the VAD and recognizer take it, where the capture path has it; else 16-bit, which every device records in
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

internal suspend fun <T> cancelledElsewhereAs(fallback: T, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    if (!currentCoroutineContext().isActive) throw e
    Timber.w(e, "cancelled elsewhere")
    fallback
}
