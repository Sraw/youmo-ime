/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Penalties
import org.fcitx.fcitx5.android.engine.lattice.PinyinDecoder
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Segmenter
import org.fcitx.fcitx5.android.engine.rerank.Reranker

/**
 * Runs our own pinyin engine over an evaluation set on the host, a letter at a time as a user
 * types, timing each letter. Latencies are host JVM ones: good for comparing runs, not devices.
 */
class PinyinRun(
    data: PinyinData,
    private val segmenter: Segmenter = PinyinSegmenter(),
    penalties: Penalties = Penalties(),
    beam: Int = PinyinDecoder.DEFAULT_BEAM,
    private val reranker: Reranker? = null,
) {
    private val decoder = PinyinDecoder(data.dictionary, data.vocabulary, WordScorer.of(data.model), penalties, beam)

    fun run(samples: List<Sample>): List<RunResult> {
        // the JIT compiles the hot loops during the first rounds; time only after them
        repeat(WARMUP_ROUNDS) { samples.forEach { candidates(it.input) } }
        return samples.map { sample ->
            val latencies = (1..sample.input.length).map { length ->
                val started = System.nanoTime()
                candidates(sample.input.substring(0, length))
                (System.nanoTime() - started) / NANOS_PER_MICRO
            }
            RunResult(sample.input, candidates(sample.input), latencies)
        }
    }

    /** Whole-input readings first, then the words the input may start with, as a candidate list shows them. */
    fun candidates(input: String): List<String> {
        val decoding = decoder.decode(segmenter.segment(input))
        val sentences = decoding.sentences.map { it.text }.toMutableList()
        if (reranker != null) {
            val picked = reranker.pick("", sentences, decoding.sentences.map { it.score })
            if (picked > 0) sentences.add(0, sentences.removeAt(picked))
        }
        return (sentences + decoding.words.map { it.text }).distinct().take(CANDIDATES)
    }

    private companion object {
        const val WARMUP_ROUNDS = 3
        const val NANOS_PER_MICRO = 1000
        const val CANDIDATES = 10
    }
}
