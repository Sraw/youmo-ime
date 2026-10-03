/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Decoding
import org.fcitx.fcitx5.android.engine.lattice.Penalties
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner

/**
 * Runs our own pinyin engine over an evaluation set on the host, a letter at a time as a user
 * types, timing each letter. Latencies are host JVM ones: good for comparing runs on the same
 * number of threads, not devices.
 */
class PinyinRun(
    data: PinyinData,
    private val segmenter: Segmenter = PinyinSegmenter(),
    penalties: Penalties = Penalties(),
    beam: Int = PinyinDecoder.DEFAULT_BEAM,
    /**
     * Makes the reranker of a sample's keys. A fresh one per sample, as a reranker keeps what it
     * read for the next input: a sample's result would hang on the one before it, and so on how the
     * samples are dealt to threads.
     */
    private val reranker: (() -> SentencePicker)? = null,
    /** Makes what weighs the readings of the whole input once more, as the user pauses before picking; not timed. */
    private val refiner: (() -> SentenceRefiner)? = null,
) {
    private val decoder = PinyinDecoder(data.dictionary, data.vocabulary, WordScorer.of(data.model), penalties, beam)
    private val textWords = TextWords(data.model, data.wordIndex)

    fun run(samples: List<Sample>): List<RunResult> {
        // the JIT compiles the hot loops during the first rounds; time only after them
        repeat(WARMUP_ROUNDS) { samples.forEach { Typing().candidates(it.input, it.context) } }
        return samples.map { sample ->
            val typing = Typing()
            val latencies = (1..sample.input.length).map { length ->
                val started = System.nanoTime()
                typing.candidates(sample.input.substring(0, length), sample.context)
                (System.nanoTime() - started) / NANOS_PER_MICRO
            }
            RunResult(sample.input, typing.candidates(sample.input, sample.context, paused = true), latencies)
        }
    }

    /**
     * Whole-input readings first, then the words the input may start with, as a candidate list
     * shows them; typed after [context], the text the app has before it, as in a session. Once
     * the user [paused], as the [refiner] has them.
     */
    fun candidates(input: String, context: String = "", paused: Boolean = false): List<String> =
        Typing().candidates(input, context, paused)

    /** The whole-input readings of [input] typed after [context], best first, each with the decoder's score. */
    fun sentences(input: String, context: String = ""): List<Pair<String, Float>> =
        decode(input, context).sentences.map { it.text to it.score }

    private fun decode(input: String, context: String): Decoding {
        val words = textWords.lastTwo(context)
        val prev = words.lastOrNull() ?: NO_WORD
        val prev2 = if (words.size == 2) words[0] else NO_WORD
        return decoder.decode(segmenter.segment(input), prev2, prev)
    }

    /** One sample typed, with rerankers of its own. */
    private inner class Typing {
        private val picker = reranker?.invoke()
        private val refine = refiner?.invoke()

        fun candidates(input: String, context: String, paused: Boolean = false): List<String> {
            val decoding = decode(input, context)
            val sentences = decoding.sentences.map { it.text }.toMutableList()
            val scores = decoding.sentences.map { it.score }
            // a refiner waiting on a server says so with null: asked again, as the session would
            val refined = refine?.takeIf { paused }?.let { r ->
                var picked: Int? = null
                while (picked == null) picked = r.refine(context, sentences, scores, Int.MAX_VALUE)
                picked
            }
            val picked = refined?.takeIf { it != SentenceRefiner.NONE } ?: picker?.pick(context, sentences, scores) ?: 0
            if (picked > 0) sentences.add(0, sentences.removeAt(picked))
            return (sentences + decoding.words.map { it.text }).distinct().take(CANDIDATES)
        }
    }

    private companion object {
        const val WARMUP_ROUNDS = 3
        const val NANOS_PER_MICRO = 1000
        const val CANDIDATES = 10
    }
}
