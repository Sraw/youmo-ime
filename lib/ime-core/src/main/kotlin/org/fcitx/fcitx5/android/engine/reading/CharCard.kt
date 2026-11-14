/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.fcitx.fcitx5.android.engine.phrase.CharInfo
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.stroke.StrokeShape
import org.fcitx.fcitx5.android.engine.stroke.Strokes

/**
 * What a character's card shows (a handwritten candidate held, or one looked up by its parts or
 * strokes): the character, each of its readings in pinyin and zhuyin with at most two words reading it
 * so, in the order the candidates list them; its radical, strokes, parts with their readings and the
 * order of its strokes, each by its shape (行 丿丿丨一一亅; one the mainland's table lacks, by its kind
 * only: 國 丨乛一…). What is not known is null (乾, which 汉语拆字字典 does not take apart, has no
 * parts) and the card leaves it out.
 */
data class CharCard(
    val text: String,
    val readings: List<Row>,
    val radical: String?,
    val strokes: Int?,
    val parts: List<Part>?,
    val strokeOrder: List<StrokeShape>?,
) {
    /**
     * A reading: [pinyin] háng, [zhuyin] ㄏㄤˊ (empty for a syllable it does not write, or a tone not known), [examples] 银行;
     * [inWord], the one the word the card was opened from reads it (drawn in the accent colour, first).
     */
    data class Row(val pinyin: String, val zhuyin: String, val examples: List<String>, val inWord: Boolean = false)

    /** A part: its [text] and its commonest reading in pinyin (null for one with none: 丅). */
    data class Part(val text: String, val pinyin: String?)

    /**
     * One sentence of it for TalkBack, the labels [words] gives: 行，读音 xíng、háng，部首 彳，6 画，拆字 彳 亍，
     * 笔顺 撇、撇、竖、横、横、竖钩.
     */
    fun spoken(words: Words): String = buildList {
        add(text)
        if (readings.isNotEmpty()) add(words.readings + readings.joinToString(words.between) { it.pinyin })
        radical?.let { add(words.radical + it) }
        strokes?.let { add(words.strokes(it)) }
        parts?.let { p -> add(words.parts + p.joinToString(" ") { it.text }) }
        strokeOrder?.let { o -> add(words.order + o.joinToString(words.between) { words.stroke(it) }) }
    }.joinToString(words.comma)

    /** The order as the card draws it: 丿丿丨一一亅; null for none. */
    fun orderShown(): String? = strokeOrder?.let(StrokeShape::shown)

    /** The parts with their readings after them, as the card writes them: 彳 亍（chì chù）, — for a part of none; null for none. */
    fun partsShown(): String? = parts?.let { p ->
        val read = p.map { it.pinyin ?: UNKNOWN }
        p.joinToString(" ") { it.text } + if (read.all { it == UNKNOWN }) "" else read.joinToString(" ", "（", "）")
    }

    /** The labels [spoken] reads, in the app's language, [stroke] a shape's name (竖钩). */
    class Words(
        val readings: String,
        val radical: String,
        val parts: String,
        val order: String,
        val between: String,
        val comma: String,
        val strokes: (Int) -> String,
        val stroke: (StrokeShape) -> String,
    )

    companion object {
        private const val UNKNOWN = "—"

        /**
         * The card of [text], one Han character, [before] the text written before it (its readings'
         * order: [CandidateReadings.ordered]); null for anything else. [first], the reading a candidate has it
         * in (a word card's row, a held character's): that row first, marked [Row.inWord], if the card has one
         * of its syllable and tone; else a row of it put first, with no words (a tone a word's exception gives,
         * a syllable the card lacks, a tone not known: toneless, no zhuyin), so the word's card and the
         * character's never read it apart. A word's 儿化 r has none (WordCard.readingOf): the card as it is.
         */
        fun of(
            text: String,
            before: String,
            readings: CandidateReadings,
            charReadings: CharReadings,
            info: CharInfo,
            strokes: () -> Strokes?,
            first: CardReading? = null,
        ): CharCard? {
            if (text.isEmpty() || text.codePointCount(0, text.length) != 1) return null
            val cp = text.codePointAt(0)
            if (!Character.isIdeographic(cp)) return null
            val ordered = readings.ordered(cp, before, card = true)
            // a traditional character's words: its simplified one's, which it is read as
            val from = readings.readFrom(cp)
            val own = charReadings.of(from, card = true)
            val rows = ordered.map { r ->
                val i = own.indexOf(r)
                Row(r.pinyin, r.zhuyin, if (i < 0) emptyList() else charReadings.examples(from, i))
            }.let { if (first == null) it else firstOf(it, ordered, first) }
            val entry = info.of(cp)
            val parts = entry?.parts?.map { p -> Part(p, readings.ordered(p.codePointAt(0)).firstOrNull()?.pinyin) }
            // the mainland's order (charinfo.data's, lexicon/stroke-order.tsv's); for a character it lacks,
            // the stroke table's of Unihan's count (礻 4, not its 5-stroke 示 form), else its first: the
            // five kinds only, all the table knows
            // (the table read only then: most characters have their order, the table is a pack's file)
            val order = entry?.order ?: strokes()?.let { tableOrder(text, entry?.strokes, it) }
            // counted as the order shown is: Unihan's count may be Taiwan's glyph's (范 9, the mainland's order 8)
            return CharCard(text, rows, entry?.radical, order?.size ?: entry?.strokes?.takeIf { it > 0 }, parts, order)
        }

        private fun firstOf(rows: List<Row>, ordered: List<Reading>, first: CardReading): List<Row> {
            val i = if (first.tone == null) -1 else ordered.indexOf(Reading(first.syllable, first.tone))
            if (i >= 0) return listOf(rows[i].copy(inWord = true)) + rows.filterIndexed { j, _ -> j != i }
            val tone = first.tone ?: ToneMark.NONE
            val zhuyin = if (first.tone == null) "" else Zhuyin.of(first.syllable)?.let { ToneMark.zhuyin(it, tone) }.orEmpty()
            return listOf(Row(ToneMark.pinyin(Syllables.spelling(first.syllable), tone), zhuyin, emptyList(), inWord = true)) + rows
        }

        private fun tableOrder(text: String, count: Int?, strokes: Strokes): List<StrokeShape>? {
            if (text.length != 1) return null
            val all = strokes.ordersOf(text[0])
            return (all.firstOrNull { it.length == count } ?: all.firstOrNull())?.map(StrokeShape::ofKey)
        }
    }
}

/**
 * The reading a candidate has a character in, as its word card's row or its card's first: a [syllable] (an id of
 * [Syllables]) in a [tone] (1–4, [ToneMark.NEUTRAL]), null where the tone is not known (䏝 chun).
 */
data class CardReading(val syllable: Int, val tone: Int?)
