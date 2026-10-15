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

    class Row(val before: String, val text: String, val positions: List<InkReading>)

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
            val right = p.texts.firstOrNull() == r.text[i].toString()
            t.chars++
            if (right) { t.final++; t.asWritten++ }
            if (r.text[i].toString() in p.texts.take(TEN)) t.inTen++
        }
    }

    fun decoder(weight: Double, alternatives: Int): HandwritingDecoder = HandwritingDecoder(
        data.dictionary, data.vocabulary, data.wordIndex, WordScorer.of(data.model), { readings },
        coarse = coarse, inkWeight = weight, alternatives = alternatives,
    )

    fun sentences(rows: List<Row>, decoder: HandwritingDecoder): Tally = Tally().also { t ->
        for (r in rows) {
            val (prev2, prev) = TextWords.previous(textWords.lastTwo(r.before))
            for (i in r.positions.indices) {
                val d = decoder.decode(r.positions.subList(0, i + 1), prev2, prev)
                if (d.text.lastOrNull() == r.text[i]) t.asWritten++
                if (d.sentences.take(TEN).any { it.last() == r.text[i] }) t.inTen++
            }
            val final = decoder.decode(r.positions, prev2, prev).text
            t.chars += r.text.length
            t.final += r.text.indices.count { final.getOrNull(it) == r.text[it] }
        }
    }

    companion object {
        private const val TEN = 10

        fun read(path: String): List<Row> = File(path).readLines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 4) return@mapNotNull null
            val text = f[2]
            val positions = f.drop(3).map { cell ->
                val pairs = cell.split(' ').filter { it.isNotEmpty() }.map { it.substringBeforeLast(':') to it.substringAfterLast(':').toDouble() }
                InkReading(pairs.map { it.first }, pairs.map { it.second }.toDoubleArray())
            }
            if (positions.size != text.length) null else Row(f[1], text, positions)
        }
    }
}
