/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.pinyin.Syllables

/** What 候选词注音 shows above or beside each character of a candidate: nothing, pinyin with tones, or zhuyin. */
enum class ReadingStyle(val key: String) {
    OFF("Off"),
    PINYIN("Pinyin"),
    ZHUYIN("Zhuyin");

    companion object {
        /** By [key], as the engine's config keeps it; anything else is [OFF]. */
        fun of(key: String?): ReadingStyle = entries.firstOrNull { it.key == key } ?: OFF
    }
}

/** A word of a candidate as its session read it: its [text], its id in pinyin data (-1 if none), its [syllables]. */
class WordReading(val text: String, val word: Int, val syllables: IntArray)

/**
 * The reading of each character of a candidate, written out ([ToneMark]) as [style] has it: one string
 * a code point, empty for a code point that is no Han character or whose tone is not known.
 *
 * A candidate whose session knows how its words were read (pinyin's: 银行 typed yinhang) goes by
 * that; any other (a table's, handwriting's, a prediction's) by its text: the longest words of the
 * model first, each read the likeliest way the dictionary has it, a character left over alone. Asked
 * for only while [style] is not [ReadingStyle.OFF].
 */
class CandidateReadings(private val data: PinyinData, private val table: ReadingTable, charReadings: () -> Map<Int, IntArray>) {

    private val predictor = Predictor(data.model, data.vocabulary, data.dictionary, charReadings = charReadings)
    private val charReadings by lazy(LazyThreadSafetyMode.NONE, charReadings)

    /** [text] written out as [style] has it, [words] how its session read it if it knows; empty if nothing of it is shown. */
    fun of(text: String, style: ReadingStyle, words: List<WordReading>? = null): List<String> {
        if (style == ReadingStyle.OFF || text.none { Character.isIdeographic(it.code) || it.isSurrogate() }) return emptyList()
        val read = words?.takeIf { w -> w.joinToString("") { it.text } == text } ?: byText(text)
        val out = ArrayList<String>(text.length)
        for (w in read) {
            val tones = table.tones(w.text, w.syllables, w.word)
            var at = 0
            var i = 0
            while (at < w.text.length) {
                val cp = w.text.codePointAt(at)
                val tone = tones?.get(i)?.toInt() ?: ToneMark.NONE
                out += if (tones == null || !Character.isIdeographic(cp) || tone == ToneMark.NONE) "" else written(w.syllables[i], tone, style)
                at += Character.charCount(cp)
                i++
            }
        }
        return if (out.all { it.isEmpty() }) emptyList() else out
    }

    private fun written(syllable: Int, tone: Int, style: ReadingStyle): String = when (style) {
        ReadingStyle.ZHUYIN -> Zhuyin.of(syllable)?.let { ToneMark.zhuyin(it, tone) }.orEmpty()
        else -> ToneMark.pinyin(Syllables.spelling(syllable), tone)
    }

    /** [text] as words of the model, longest first; a character no word starts with alone. */
    private fun byText(text: String): List<WordReading> {
        val out = ArrayList<WordReading>()
        val index = data.wordIndex
        var at = 0
        while (at < text.length) {
            var found: WordReading? = null
            var end = minOf(text.length, at + MAX_WORD)
            while (end > at && found == null) {
                // never through a surrogate pair
                if (end == text.length || !Character.isLowSurrogate(text[end])) {
                    val id = index.find(text, at, end)
                    val syllables = if (id >= 0) predictor.reading(id) else null
                    if (syllables != null && syllables.size == text.codePointCount(at, end)) found = WordReading(text.substring(at, end), id, syllables)
                }
                end--
            }
            val one = found ?: run {
                val cp = text.codePointAt(at)
                // a character only the dictionary has (rare ones have a reading or two); with none, a
                // syllable no character has a tone for, so it is left unmarked
                WordReading(text.substring(at, at + Character.charCount(cp)), -1, intArrayOf(charReadings[cp]?.firstOrNull() ?: UNREAD))
            }
            out += one
            at += one.text.length
        }
        return out
    }

    companion object {
        // the longest word looked for in a candidate's text, in chars
        private const val MAX_WORD = 8

        // a syllable no character has a tone for: the Latin letter A, which no Han character reads as
        private val UNREAD = Syllables.id("A")
    }
}
