/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.fcitx.fcitx5.android.engine.phrase.CharInfo
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import java.text.Normalizer

/** A word's card (two to [MAX_CHARS] Han characters): its reading per code point in pinyin and zhuyin, and a row per Han character opening that character's card. */
data class WordCard(
    val text: String,
    val pinyin: List<String>,
    val zhuyin: List<String>,
    val rows: List<Row>,
) {
    /** [reading]: what its card puts first. */
    data class Row(val text: String, val pinyin: String, val zhuyin: String, val radical: String?, val strokes: Int?, val reading: CardReading?) {
        /** One TalkBack sentence: 银，yín，部首 钅，11 画. */
        fun spoken(words: CharCard.Words): String = listOfNotNull(
            text,
            pinyin.ifEmpty { null },
            radical?.let { words.radical + it },
            strokes?.let(words.strokes),
        ).joinToString(words.comma)
    }

    /** For TalkBack: 银行，读音 yín háng; always pinyin, since TalkBack reads zhuyin a symbol at a time. */
    fun spoken(words: CharCard.Words): String =
        listOfNotNull(text, line(ReadingStyle.PINYIN).ifEmpty { null }?.let { words.readings + it }).joinToString(words.comma)

    fun line(style: ReadingStyle): String = line(text, if (style == ReadingStyle.ZHUYIN) zhuyin else pinyin)

    companion object {
        /** Longer candidates get a reading line alone, no card. */
        const val MAX_CHARS = 8

        /** A [CandidateCard.Line] reads about this many Han characters, then … */
        const val LINE_CHARS = 64

        /** [text] up to its [count]th Han character. */
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

        fun hanCount(text: String): Int = text.codePoints().filter(Character::isIdeographic).count().toInt()

        /** Each Han character's reading space-separated, non-Han runs as they are (卡拉OK kǎ lā OK); unknown readings left out. */
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
            // nothing read: no line, not the text again
            return if (readings.none { it.isNotEmpty() }) "" else out.joinToString(" ")
        }

        /** [pinyin] and [zhuyin] are per code point of [read] (what [text] was read as); radical and strokes from [info] for the shown form, counted as the char card counts them. */
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

        /** A character's syllable and tone from its candidate pinyin and zhuyin; tone null where neither marks it (䏝 chun); null for none, or 儿化's r. */
        fun readingOf(pinyin: String, zhuyin: String): CardReading? {
            val (spelling, tone) = ToneMark.parse(pinyin) ?: return null
            val syllable = Syllables.id(spelling)
            if (syllable < 0 || spelling == ERHUA) return null
            // a tone mark, not ü's diaeresis
            val marked = Normalizer.normalize(pinyin, Normalizer.Form.NFD).any { it in TONE_MARKS }
            return CardReading(syllable, if (marked) tone else if (zhuyin.startsWith(NEUTRAL)) ToneMark.NEUTRAL else null)
        }

        private const val ERHUA = "r"
        private const val NEUTRAL = '˙'
        private const val TONE_MARKS = "\u0304\u0301\u030C\u0300"
    }
}
