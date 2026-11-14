/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.fcitx.fcitx5.android.engine.phrase.CharInfo
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import java.text.Normalizer

/**
 * What a word's card shows (a held candidate of two to [MAX_CHARS] Han characters): the word, its reading in
 * pinyin and in zhuyin, one string a code point as the candidates' 候选词注音 writes them (empty for one that is
 * no Han character, or has no reading: CandidateReadings), and a row for each Han character: its reading,
 * radical and strokes, a tap away from its own card (Engines.charCardOf of the row's [Row.reading]).
 */
data class WordCard(
    val text: String,
    val pinyin: List<String>,
    val zhuyin: List<String>,
    val rows: List<Row>,
) {
    /** A Han character of it: [text], its [pinyin] and [zhuyin] in the word, [radical], [strokes]; [reading], what its card puts first. */
    data class Row(val text: String, val pinyin: String, val zhuyin: String, val radical: String?, val strokes: Int?, val reading: CardReading?) {
        /** One sentence of it for TalkBack: 银，yín，部首 钅，11 画. */
        fun spoken(words: CharCard.Words): String = listOfNotNull(
            text,
            pinyin.ifEmpty { null },
            radical?.let { words.radical + it },
            strokes?.let(words.strokes),
        ).joinToString(words.comma)
    }

    /**
     * The word and its reading for TalkBack: 银行，读音 yín háng. In pinyin whatever the line's style, as a
     * character's card speaks its readings ([CharCard.spoken]): the card shows both, and TalkBack speaks pinyin
     * as syllables, zhuyin a symbol at a time.
     */
    fun spoken(words: CharCard.Words): String =
        listOfNotNull(text, line(ReadingStyle.PINYIN).ifEmpty { null }?.let { words.readings + it }).joinToString(words.comma)

    /** The whole word's reading as the card heads it and its compact line writes it ([line]). */
    fun line(style: ReadingStyle): String = line(text, if (style == ReadingStyle.ZHUYIN) zhuyin else pinyin)

    companion object {
        /** A candidate of more Han characters than this has its reading alone, no card (the user, 2026-11-14, Q5). */
        const val MAX_CHARS = 8

        /** A [CandidateCard.Line] reads about so many Han characters of its candidate, then … (Engines.card). */
        const val LINE_CHARS = 64

        /** [text] up to its [count]th Han character (what is not Han before it kept). */
        fun firstHan(text: String, count: Int): String {
            var han = 0
            var at = 0
            while (at < text.length && han < count) {
                val cp = text.codePointAt(at)
                if (Character.isIdeographic(cp)) han++
                at += Character.charCount(cp)
            }
            return text.substring(0, at)
        }

        /** How many Han characters [text] has (letters, digits, punctuation not counted). */
        fun hanCount(text: String): Int = text.codePoints().filter(Character::isIdeographic).count().toInt()

        /**
         * [text] written by [readings] (one a code point): each Han character's reading apart by a space, what is
         * not Han as it is, a run of it at a time (卡拉OK kǎ lā OK, 2026年 2026 nián); a reading not known left out.
         */
        fun line(text: String, readings: List<String>): String {
            val out = ArrayList<String>()
            val run = StringBuilder()
            var i = 0
            var at = 0
            while (at < text.length) {
                val cp = text.codePointAt(at)
                if (Character.isIdeographic(cp)) {
                    if (run.isNotBlank()) out += run.trim().toString()
                    run.clear()
                    readings.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { out += it }
                } else {
                    run.appendCodePoint(cp)
                }
                at += Character.charCount(cp)
                i++
            }
            if (run.isNotBlank()) out += run.trim().toString()
            // nothing of it read: no reading, not the text again
            return if (readings.none { it.isNotEmpty() }) "" else out.joinToString(" ")
        }

        /**
         * The card of [text], read [pinyin] and [zhuyin] (one a code point of [read], the text it was read as:
         * [text] where they are as many code points), each Han character's radical and strokes [info]'s (its
         * shown form's: 銀 of 銀行), counted as its card counts them: the mainland's order, else Unihan's count.
         */
        fun of(text: String, pinyin: List<String>, zhuyin: List<String>, info: CharInfo): WordCard {
            val rows = ArrayList<Row>()
            var i = 0
            var at = 0
            while (at < text.length) {
                val cp = text.codePointAt(at)
                if (Character.isIdeographic(cp)) {
                    val entry = info.of(cp)
                    val p = pinyin.getOrElse(i) { "" }
                    val z = zhuyin.getOrElse(i) { "" }
                    rows += Row(String(Character.toChars(cp)), p, z, entry?.radical, entry?.order?.size ?: entry?.strokes?.takeIf { it > 0 }, readingOf(p, z))
                }
                at += Character.charCount(cp)
                i++
            }
            return WordCard(text, pinyin, zhuyin, rows)
        }

        /**
         * The reading a character's [pinyin] and [zhuyin] in a candidate say (as CandidateReadings writes them):
         * its syllable and tone; the tone null where pinyin has no mark and zhuyin no ˙ (not known: 䏝 chun); null
         * for none, or 儿化's r, whose card is 儿's own.
         */
        fun readingOf(pinyin: String, zhuyin: String): CardReading? {
            val (spelling, tone) = ToneMark.parse(pinyin) ?: return null
            val syllable = Syllables.id(spelling)
            if (syllable < 0 || spelling == ERHUA) return null
            // a tone mark, not ü's diaeresis (lü toneless: not known, as 䏝 chun)
            val marked = Normalizer.normalize(pinyin, Normalizer.Form.NFD).any { it in TONE_MARKS }
            return CardReading(syllable, if (marked) tone else if (zhuyin.startsWith(NEUTRAL)) ToneMark.NEUTRAL else null)
        }

        private const val ERHUA = "r"
        private const val NEUTRAL = '˙'
        // the combining macron, acute, caron and grave: tones 1 to 4
        private const val TONE_MARKS = "\u0304\u0301\u030C\u0300"
    }
}
