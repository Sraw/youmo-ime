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
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.withTimeoutOrNull
import org.fcitx.fcitx5.android.daemon.FcitxConnection

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
    private val letGo = Runnable {
        synchronized(lock) {
            if (users == 0) {
                recognizer?.release()
                recognizer = null
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

    /** enough for the longest stretch the VAD lets through, its pause and lead-in */
    fun history() = AudioHistory(SAMPLE_RATE * 22)

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
     * graph of hotwords again, the pack's 78 thousand too: a tenth of a second or more, so made
     * before the stretch it is for.
     */
    fun stream(r: OfflineRecognizer, words: VoiceHotwords.Words, block: Boolean = false): OfflineStream = when {
        block && words.blocked.isNotEmpty() -> r.createStream(words.hotwords, words.blocked)
        words.hotwords.isNotEmpty() -> r.createStream(words.hotwords)
        else -> r.createStream()
    }

    /** A stretch of speech to the recognizer's result, in [stream], which it releases. */
    fun result(r: OfflineRecognizer, stream: OfflineStream, samples: FloatArray): OfflineRecognizerResult {
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            r.decode(stream)
            r.getResult(stream)
        } finally {
            stream.release()
        }
    }

    /** What the pinyin engine knows of Chinese, asked of it on its thread: for [recognize]. */
    interface Language {
        /** where each of [texts] splits into words (Engines.wordBoundaries), none for some if it cannot say */
        suspend fun boundaries(texts: List<String>): Map<String, IntArray>

        /** how likely each of [texts] is (Engines.logProbs), null if it cannot say */
        suspend fun logProbs(texts: List<String>): FloatArray?
    }

    /**
     * A stretch of speech to text, in [stream] (made by [stream] for [words], not blocking),
     * which it releases. The best of the recognizer's hypotheses by [language]'s model too
     * ([VoiceRerank]); of them, that first, the first with none of the words the user blocked standing as a
     * word in it ([VoiceBlocking]); if each has one, the stretch searched again with them blocked,
     * and of that search's hypotheses again the first with none, its best if each still has one.
     */
    suspend fun recognize(
        r: OfflineRecognizer,
        stream: OfflineStream,
        samples: FloatArray,
        words: VoiceHotwords.Words,
        language: Language,
    ): String {
        val result = result(r, stream, samples)
        val heard = VoiceRerank.distinct(
            result.nbest.indices.map { VoiceRerank.Hypothesis(result.nbest[it], result.nbestScores.getOrElse(it) { 0f }) }
                .ifEmpty { listOf(VoiceRerank.Hypothesis(result.text, 0f)) },
        )
        // the best first, the rest as the recognizer has them
        val candidates = VoiceRerank.candidates(heard)
        val best = if (candidates.size < 2) heard[0] else {
            VoiceRerank.best(candidates, language.logProbs(candidates.map { VoiceRerank.bare(it.text) }))
        }
        val texts = listOf(best.text) + heard.filter { it !== best }.map { it.text }
        val blocked = words.blockedWords
        if (blocked.isEmpty()) return texts[0]
        val suspects = VoiceBlocking.suspects(texts, blocked)
        if (suspects.isEmpty()) return texts[0]
        VoiceBlocking.pick(texts, blocked, language.boundaries(suspects))?.let { return texts[it] }
        val again = result(r, VoiceEngine.stream(r, words, block = true), samples)
        // the search blocks a word as the model spells it: written in other tokens it can still win
        val retried = again.nbest.toList().ifEmpty { listOf(again.text) }
        val still = VoiceBlocking.suspects(retried, blocked)
        if (still.isEmpty()) return again.text
        return VoiceBlocking.pick(retried, blocked, language.boundaries(still))?.let { retried[it] } ?: again.text
    }

    /** the words the user added and made (VoiceHotwords.ofUser), read again for each listener */
    suspend fun userHotwords(fcitx: FcitxConnection): VoiceHotwords.Words =
        withTimeoutOrNull(USER_WORDS_MS) { VoiceHotwords.ofUser(fcitx.runOnReady { userWords() }) } ?: VoiceHotwords.Words.NONE

    // the engine still starting: voice input without the user's words, not a wait
    private const val USER_WORDS_MS = 2000L

    /**
     * The pinyin engine's [Language], each answer in time or none: no boundaries (VoiceBlocking
     * takes a text as having the word), no scores (the recognizer's order).
     */
    fun language(fcitx: FcitxConnection) = object : Language {
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
                maxSpeechDuration = 20f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    private fun config() = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = "$DIR/encoder.onnx",
                decoder = "$DIR/decoder.onnx",
                joiner = "$DIR/joiner.onnx",
            ),
            tokens = "$DIR/tokens.txt",
            numThreads = THREADS,
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
