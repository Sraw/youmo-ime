/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.handwriting.HandwritingDecoder
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.lattice.CoarseWords
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.lattice.TextWords
import org.fcitx.fcitx5.android.engine.lattice.WordScorer
import org.fcitx.fcitx5.android.engine.rerank.SentenceRefiner
import org.fcitx.fcitx5.android.engine.session.RefineSlices
import java.io.File
import java.util.Locale

/**
 * Characters written one at a time, read as the recognizer has them (the training sources'
 * `lmdata.py`: a row is its source, the text before it, the characters, and each one's readings
 * `c:score ...`), then read again as a sentence by [HandwritingDecoder] at each ink weight. Counted per character: right once the run is written
 * (what space then puts in), right as it is written (the last character of what shows, each time
 * one is added), and among the first ten offered for the last.
 */
internal class HandwritingRun(private val data: PinyinData) {

    class Row(val before: String, val text: String, val positions: List<InkReading>) {
        /** [text]'s characters, a position each: one past the BMP whole, as a reading has it. */
        val characters: List<String> = charactersOf(text)
    }

    private val textWords = TextWords(data.model, data.wordIndex)
    private val readings by lazy { Predictor.charReadingsOf(data.dictionary, data.vocabulary) }
    private val coarse = CoarseWords.of({ data.wordIndex.find(it) })

    class Tally(var chars: Int = 0, var final: Int = 0, var asWritten: Int = 0, var inTen: Int = 0) {
        fun add(o: Tally) {
            chars += o.chars; final += o.final; asWritten += o.asWritten; inTen += o.inTen
        }

        override fun toString() = String.format(
            Locale.ROOT, "final %6.2f%%  as written %6.2f%%  last in ten %6.2f%%  (%d characters)",
            pct(final), pct(asWritten), pct(inTen), chars,
        )

        private fun pct(n: Int) = 100.0 * n / maxOf(1, chars)
    }

    fun ink(rows: List<Row>): Tally = Tally().also { t ->
        for (r in rows) for ((i, p) in r.positions.withIndex()) {
            val right = p.texts.firstOrNull() == r.characters[i]
            t.chars++
            if (right) { t.final++; t.asWritten++ }
            if (r.characters[i] in p.texts.take(FIRST_OFFERED)) t.inTen++
        }
    }

    fun decoder(weight: Double, alternatives: Int): HandwritingDecoder = HandwritingDecoder(
        data.dictionary, data.vocabulary, data.wordIndex, WordScorer.of(data.model), { readings },
        coarse = coarse, inkWeight = weight, alternatives = alternatives,
    )

    /**
     * [pick], given the text before and a decoding, says which of its [HandwritingDecoder.Decoding.best]
     * to put first, as a sentence model does (the 性能档位): none keeps the decoder's.
     */
    fun sentences(rows: List<Row>, decoder: HandwritingDecoder, pick: ((String, HandwritingDecoder.Decoding) -> Int)? = null): Tally = Tally().also { t ->
        fun text(before: String, d: HandwritingDecoder.Decoding) = if (pick == null) d.text else d.best.getOrElse(pick(before, d)) { d.text }
        for (r in rows) {
            val (prev2, prev) = TextWords.previous(textWords.lastTwo(r.before))
            for (i in r.positions.indices) {
                val d = decoder.decode(r.positions.subList(0, i + 1), prev2, prev)
                if (text(r.before, d).endsWith(r.characters[i])) t.asWritten++
                if (d.sentences.take(FIRST_OFFERED).any { it.endsWith(r.characters[i]) }) t.inTen++
            }
            val final = charactersOf(text(r.before, decoder.decode(r.positions, prev2, prev)))
            t.chars += r.characters.size
            t.final += r.characters.indices.count { final.getOrNull(it) == r.characters[it] }
        }
    }

    companion object {
        /** How many of the last character's candidates count as offered ("last in ten"). */
        private const val FIRST_OFFERED = 10

        /**
         * The 性能档位's high, as [HandwritingSession][org.fcitx.fcitx5.android.engine.handwriting.HandwritingSession]
         * has it: [first]'s pick, unless [refiner] picks another in the slices a pause gives it.
         * By [RefineSlices], the session's own rule, rather than a copy of it to drift.
         */
        fun refined(refiner: SentenceRefiner, first: (String, HandwritingDecoder.Decoding) -> Int): (String, HandwritingDecoder.Decoding) -> Int =
            { before, d ->
                var picked = first(before, d)
                val slices = RefineSlices<HandwritingDecoder.Decoding>(refiner)
                slices.start(d, d.best.size)
                while (slices.refines) slices.slice(before, { it.best }, { it.bestScores }) { _, at -> picked = at }
                picked
            }

        fun read(path: String): List<Row> = File(path).readLines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 4) return@mapNotNull null
            val positions = f.drop(3).map { cell ->
                val pairs = cell.split(' ').filter { it.isNotEmpty() }.map { it.substringBeforeLast(':') to it.substringAfterLast(':').toDouble() }
                InkReading(pairs.map { it.first }, pairs.map { it.second }.toDoubleArray())
            }
            Row(f[1], f[2], positions).takeIf { it.characters.size == positions.size }
        }
    }
}

private fun charactersOf(text: String): List<String> = text.codePoints().toArray().map { String(Character.toChars(it)) }
