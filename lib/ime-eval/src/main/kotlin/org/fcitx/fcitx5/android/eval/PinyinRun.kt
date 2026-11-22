/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.lattice.Candidate
import org.fcitx.fcitx5.android.engine.lattice.CoarseWords
import org.fcitx.fcitx5.android.engine.lattice.Decoding
import org.fcitx.fcitx5.android.engine.lattice.LayerPrior
import org.fcitx.fcitx5.android.engine.lattice.Penalties
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import org.fcitx.fcitx5.android.engine.pinyin.KeyedSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.rerank.GatedPicker
import org.fcitx.fcitx5.android.engine.rerank.SentencePicker
import org.fcitx.fcitx5.android.engine.session.CoarseCandidates
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.user.DictionaryFile
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.engine.user.UserScorer
import org.fcitx.fcitx5.android.engine.user.WordPack

/** Our pinyin engine over an evaluation set on the host, a letter at a time, timed; latencies compare only between host runs on as many threads. */
class PinyinRun(
    data: PinyinData,
    private val segmenter: Segmenter = PinyinSegmenter(),
    penalties: Penalties = Penalties(),
    beam: Int = PinyinDecoder.DEFAULT_BEAM,
    /** How many whole-input readings the decoder gives the rerankers. */
    private val sentenceCount: Int = PinyinDecoder.DEFAULT_SENTENCES,
    /** A fixed prior per layer, `name=value,...` ([LayerPrior.parse]), the [pack]'s layer among them; on top of the model. */
    layers: String? = null,
    pack: WordPack? = null,
    /** A fresh reranker per sample: one keeps what it read, so a sample's result would hang on the one before and on threading. */
    private val reranker: (() -> SentencePicker)? = null,
    /** The dictionaries the user merged, compiled: their words offered as the app's user model offers them. */
    merged: DictionaryFile? = null,
    /** [merged] compiled against another base dictionary, as after an app update till the recompile. */
    mergedStale: Boolean = false,
    /** Words typed before the run ([TypedHistory]'s lines), counted as the user's: what a 双拼 key read alone reads. */
    typed: List<String>? = null,
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
    private val user: UserModel? = if (pack == null && merged == null && typed == null) {
        null
    } else {
        UserModel(data.dictionary, data.vocabulary, index = data.wordIndex, merged = merged, mergedStale = mergedStale).also { u ->
            pack?.let { p ->
                val layer = prior?.layer(p.layer) ?: 1
                p.words.forEach { u.list(it.entry, it.score, layer, data.model.vocabularySize) }
            }
            typed?.let { TypedHistory.count(u, data, it) }
        }
    }
    // as the app's session has them
    private val coarse = CoarseWords.of({ data.wordIndex.find(it) })
    private val decoder = PinyinDecoder(
        data.dictionary, data.vocabulary,
        (if (user == null) WordScorer.of(data.model) else UserScorer(data.model, user)).let { if (prior == null) it else prior.scorer(it) },
        penalties, beam, user = user, wholeFirst = segmenter.typesLetters,
        coarse = coarse,
        // text kept as typed reaches the rerankers as the session's does: on keys of letter groups, as letters
        asTyped = { (segmenter as? KeyedSegmenter)?.asTyped(it) ?: it },
        // a tone typed on zhuyin's keys weighed against the data's tones as the session weighs it
        toneAllows = (segmenter as? KeyedSegmenter)?.toneAllows,
    )
    private val textWords = TextWords(data.model, data.wordIndex)
    // where the session lists the candidates with one, by the same code
    private val coarseCandidates = CoarseCandidates(coarse, data, user)

    /** Keys of [run]'s samples a 高档 gate sent to the large model. */
    var escalations = 0L
        private set

    fun run(samples: List<Sample>): List<RunResult> {
        // the JIT compiles the hot loops during the first rounds, for all threads alike
        repeat(WARMUP_ROUNDS) { samples.take(WARMUP_SAMPLES).forEach { Typing().type(it.input, it.context) } }
        return samples.map { sample ->
            val typing = Typing()
            var shown = emptyList<String>()
            val latencies = (1..sample.input.length).map { length ->
                val started = System.nanoTime()
                shown = typing.type(sample.input.substring(0, length), sample.context, sample.taps?.subList(0, length), sample.touches?.subList(0, length))
                (System.nanoTime() - started) / NANOS_PER_MICRO
            }
            escalations += typing.escalations
            RunResult(sample.input, shown, latencies)
        }
    }

    /** Whole-input readings, then the words the input may start with, typed after [context]. */
    fun candidates(input: String, context: String = ""): List<String> = Typing().type(input, context)

    /** The whole-input readings of [input] typed after [context], best first, each with the decoder's score. */
    fun sentences(input: String, context: String = ""): List<Pair<String, Float>> =
        decode(segmenter.segment(input), textWords.lastTwo(context)).sentences.map { it.text to it.score }

    private fun decode(graph: SyllableGraph, words: IntArray): Decoding {
        val (prev2, prev) = TextWords.previous(words)
        return decoder.decode(graph, prev2, prev, sentenceCount)
    }

    /** One sample typed, with a reranker of its own, which weighs its readings as [PinyinSession]'s does. */
    private inner class Typing {
        private val picker = reranker?.invoke()

        val escalations get() = (picker as? GatedPicker)?.escalations ?: 0L

        // of the input read last: the decoder's readings and its words
        private var sentences: List<Candidate> = emptyList()
        private var words: List<Candidate> = emptyList()

        /** Reads [input] typed after [context], as a key typed does, and gives what is shown; [taps] and [touches] where each key was tapped. */
        fun type(input: String, context: String, taps: List<Float>? = null, touches: List<Touch?>? = null): List<String> {
            val contextWords = textWords.lastTwo(context)
            // text that ends in no word is no context for the reranker either, as in the session
            val before = if (contextWords.isEmpty()) "" else context
            val graph = segmenter.segment(input, taps?.toFloatArray(), touches?.toTypedArray())
            val decoding = decode(graph, contextWords)
            sentences = decoding.sentences
            words = decoding.words
            return shown(picker?.pick(before, sentences.map { it.text }, sentences.map { it.score }) ?: 0)
        }

        private fun shown(first: Int): List<String> {
            val ordered = if (first == 0) sentences else listOf(sentences[first]) + sentences.filterIndexed { i, _ -> i != first }
            // those past the decoder's usual few are there to be weighed, not shown
            val listed = (ordered.take(PinyinDecoder.DEFAULT_SENTENCES) + words).distinctBy { it.text }
            // as the app lists them: a coarse word the user never typed waits past the first page
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
