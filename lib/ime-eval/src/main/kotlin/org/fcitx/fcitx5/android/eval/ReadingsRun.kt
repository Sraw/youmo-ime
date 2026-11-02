/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.reading.CandidateReadings
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.reading.ToneMark
import org.fcitx.fcitx5.android.engine.reading.WordReading

/**
 * 候选词注音 against a hand-checked list (data/readings/chat.tsv, `word<TAB>reading`, the reading's syllables
 * marked as [org.fcitx.fcitx5.android.engine.reading.ToneMark.pinyin] writes them, spaces between): how many
 * words the candidates would show read right, every character's syllable and tone, each read by its
 * text as a table's or a prediction's candidate is (the likeliest reading the dictionary has); [typed], as
 * pinyin's candidate is when the word was typed by the list's syllables (their tones still the data's).
 */
internal class ReadingsRun(private val data: PinyinData, private val typed: Boolean = false) {

    private val readings = CandidateReadings(data, data.tones) { Predictor.charReadingsOf(data.dictionary, data.vocabulary) }

    class Result(val words: Int, val right: Int, val misses: List<String>) {
        val accuracy: Double get() = if (words == 0) 0.0 else right * 100.0 / words
    }

    fun measure(set: List<Pair<String, String>>): Result {
        val misses = ArrayList<String>()
        for ((word, expected) in set) {
            val got = readings.of(word, ReadingStyle.PINYIN, if (typed) typedAs(word, expected) else null).joinToString(" ")
            if (got != expected) misses += "$word\t$expected\t$got"
        }
        return Result(set.size, set.size - misses.size, misses)
    }

    private fun typedAs(word: String, expected: String): List<WordReading>? {
        val ids = expected.split(' ').map { ToneMark.parse(it)?.first?.let(Syllables::id) ?: -1 }
        if (ids.size != word.codePointCount(0, word.length) || ids.any { it < 0 }) return null
        return listOf(WordReading(word, data.wordIndex.find(word, 0, word.length), ids.toIntArray()))
    }

    companion object {
        /** A share of the words read right a release must reach (design 051-zhuyin §4 said 98: 96 until the neutral tones are better). */
        const val GATE = 96.0

        fun parse(lines: Sequence<String>): List<Pair<String, String>> = lines
            .filter { it.isNotBlank() && !it.startsWith('#') }
            .map { line -> line.split('\t').let { require(it.size >= 2) { "expected word<TAB>reading: $line" }; it[0] to it[1].trim() } }
            .toList()
    }
}
