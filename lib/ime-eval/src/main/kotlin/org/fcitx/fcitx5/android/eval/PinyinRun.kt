/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.host.EngineSettings
import org.fcitx.fcitx5.android.engine.lattice.Candidate
import org.fcitx.fcitx5.android.engine.lattice.CoarseWords
import org.fcitx.fcitx5.android.engine.lattice.Decoding
import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.lattice.Penalties
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.CoarseCandidates
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserScorer
import org.fcitx.fcitx5.android.engine.user.WordPack

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
    /** How many whole-input readings the decoder gives the rerankers. */
    private val sentenceCount: Int = PinyinDecoder.DEFAULT_SENTENCES,
    /** A fixed prior per layer, `name=value,...` ([LayerPrior.parse]), the [pack]'s layer among them; on top of the model. */
    layers: String? = null,
    /** A word pack listed as the user's, its words in its layer. */
    pack: WordPack? = null,
    /**
     * Makes the reranker of a sample's keys. A fresh one per sample, as a reranker keeps what it
     * read for the next input: a sample's result would hang on the one before it, and so on how the
     * samples are dealt to threads.
     */
    private val reranker: (() -> SentencePicker)? = null,
    /**
     * Makes what weighs the readings of the whole input once more, as the user pauses before
     * picking, where and for as long as [PinyinSession] lets it; not timed.
     */
    private val refiner: (() -> SentenceRefiner)? = null,
) {
    // the user model is read only when scoring, by then made
    private val prior: LayerPrior? = layers?.let { spec ->
        // as UserLexicon's: both through the user model, its words mapped to the model's as it scores them
        val extra = object : LayerPrior.PackLayers {
            override fun layerOf(word: Int) = user?.layerOf(word) ?: -1
            override fun modelWord(word: Int) = user?.modelWord(word) ?: word
        }
        LayerPrior.parse(data.layers, spec, listOfNotNull(pack?.layer), extra)
    }
    private val user: UserModel? = pack?.let { p ->
        val layer = prior?.layer(p.layer) ?: 1
        UserModel(data.dictionary, data.vocabulary, index = data.wordIndex).also { u -> p.words.forEach { u.list(it.entry, it.score, layer, data.model.vocabularySize) } }
    }
    // as the app's session has them
    private val coarse = CoarseWords.of({ data.wordIndex.find(it) })
    private val decoder = PinyinDecoder(
        data.dictionary, data.vocabulary,
        (if (user == null) WordScorer.of(data.model) else UserScorer(data.model, user)).let { if (prior == null) it else prior.scorer(it) },
        penalties, beam, user = user, wholeFirst = segmenter.typesLetters,
        coarse = coarse,
        // text kept as typed reaches the rerankers as the session's does: on the nine keys, as digits
        asTyped = { if (segmenter is T9Segmenter) T9Segmenter.asTyped(it) else it },
    )
    private val textWords = TextWords(data.model, data.wordIndex)
    // where the session lists the candidates with one, by the same code
    private val coarseCandidates = CoarseCandidates(coarse, data, user)

    fun run(samples: List<Sample>): List<RunResult> {
        // the JIT compiles the hot loops during the first rounds, for all threads alike: a few
        // samples warm it up, whatever the size of the set
        repeat(WARMUP_ROUNDS) { samples.take(WARMUP_SAMPLES).forEach { Typing().type(it.input, it.context) } }
        return samples.map { sample ->
            val typing = Typing()
            val latencies = (1..sample.input.length).map { length ->
                val started = System.nanoTime()
                typing.type(sample.input.substring(0, length), sample.context)
                (System.nanoTime() - started) / NANOS_PER_MICRO
            }
            RunResult(sample.input, typing.pause(), latencies)
        }
    }

    /**
     * Whole-input readings first, then the words the input may start with, as a candidate list
     * shows them; typed after [context], the text the app has before it, as in a session. Once
     * the user [paused], as the [refiner] has them.
     */
    fun candidates(input: String, context: String = "", paused: Boolean = false): List<String> {
        val typing = Typing()
        val shown = typing.type(input, context)
        return if (paused) typing.pause() else shown
    }

    /** The whole-input readings of [input] typed after [context], best first, each with the decoder's score. */
    fun sentences(input: String, context: String = ""): List<Pair<String, Float>> =
        decode(segmenter.segment(input), textWords.lastTwo(context)).sentences.map { it.text to it.score }

    private fun decode(graph: SyllableGraph, words: IntArray): Decoding {
        val (prev2, prev) = TextWords.previous(words)
        return decoder.decode(graph, prev2, prev, sentenceCount)
    }

    /** One sample typed, with rerankers of its own, which weigh its readings as [PinyinSession]'s do. */
    private inner class Typing {
        private val picker = reranker?.invoke()
        private val refine = refiner?.invoke()

        // of the input read last: what the rerankers read before it, the decoder's readings in its
        // order, its words, the picker's pick, and whether a pause weighs the readings again
        private var before = ""
        private var sentences: List<Candidate> = emptyList()
        private var readings: List<String> = emptyList()
        private var scores: List<Float> = emptyList()
        private var words: List<Candidate> = emptyList()
        private var picked = 0
        private var refines = false

        /** Reads [input] typed after [context], as a key typed does, and gives what is shown. */
        fun type(input: String, context: String): List<String> {
            val contextWords = textWords.lastTwo(context)
            // text that ends in no word is no context for the rerankers either, as in the session
            before = if (contextWords.isEmpty()) "" else context
            val graph = segmenter.segment(input)
            val decoding = decode(graph, contextWords)
            sentences = decoding.sentences
            readings = sentences.map { it.text }
            scores = sentences.map { it.score }
            words = decoding.words
            picked = picker?.pick(before, readings, scores) ?: 0
            refines = refine != null && readings.size > 1 && PinyinSession.endsOnSyllable(graph)
            return shown(picked)
        }

        /**
         * What is shown once the user pauses after the input read last: the [refiner]'s pick first,
         * if it makes one within the slices and budget the session gives it, else the picker's.
         */
        fun pause(): List<String> {
            val r = refine?.takeIf { refines } ?: return shown(picked)
            repeat(r.slices) {
                // null: not done yet, the next slice goes on from it
                val pick = r.refine(before, readings, scores, PinyinSession.REFINE_BUDGET) ?: return@repeat
                return shown(if (pick == SentenceRefiner.NONE) picked else pick)
            }
            return shown(picked)
        }

        private fun shown(first: Int): List<String> {
            val ordered = if (first == 0) sentences else listOf(sentences[first]) + sentences.filterIndexed { i, _ -> i != first }
            // those past the decoder's usual few are there to be weighed, not shown
            val listed = (ordered.take(PinyinDecoder.DEFAULT_SENTENCES) + words).distinctBy { it.text }
            // as the app lists them, on a page of its default size: those with a coarse word the
            // user never typed that the first page would show wait past it
            return coarseCandidates.order(listed, EngineSettings.DEFAULT_PAGE_SIZE).take(CANDIDATES).map { it.text }
        }
    }

    private companion object {
        const val WARMUP_ROUNDS = 3
        const val WARMUP_SAMPLES = 200
        const val NANOS_PER_MICRO = 1000
        const val CANDIDATES = 10
    }
}
