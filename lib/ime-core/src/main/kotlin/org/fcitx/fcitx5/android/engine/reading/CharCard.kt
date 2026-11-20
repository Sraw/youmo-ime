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
 * A character's card: its readings in pinyin and zhuyin with up to two example words each, in candidate
 * order; radical, strokes, parts with readings, and stroke order by shape (one the mainland's table lacks,
 * by kind only: 國 丨乛一…). Unknown fields are null and left out.
 */
data class CharCard(
    val text: String,
    val readings: List<Row>,
    val radical: String?,
    val strokes: Int?,
    val parts: List<Part>?,
    val strokeOrder: List<StrokeShape>?,
) {
    /** [zhuyin] is empty for an unwritable syllable or unknown tone; [inWord]: the reading in the word the card was opened from, drawn first in accent colour. */
    data class Row(val pinyin: String, val zhuyin: String, val examples: List<String>, val inWord: Boolean = false)

    /** [pinyin] is the commonest reading, null for none (丅). */
    data class Part(val text: String, val pinyin: String?)

    /** One TalkBack sentence: 行，读音 xíng、háng，部首 彳，6 画，拆字 彳 亍，笔顺 撇、撇、竖、横、横、竖钩. */
    fun spoken(words: Words): String = buildList {
        add(text)
        if (readings.isNotEmpty()) add(words.readings + readings.joinToString(words.between) { it.pinyin })
        radical?.let { add(words.radical + it) }
        strokes?.let { add(words.strokes(it)) }
        parts?.let { p -> add(words.parts + p.joinToString(" ") { it.text }) }
        strokeOrder?.let { o -> add(words.order + o.joinToString(words.between) { words.stroke(it) }) }
    }.joinToString(words.comma)

    /** 丿丿丨一一亅; null for none. */
    fun orderShown(): String? = strokeOrder?.let(StrokeShape::shown)

    /** 彳 亍（chì chù）, — for a part without reading; null for none. */
    fun partsShown(): String? = parts?.let { p ->
        val read = p.map { it.pinyin ?: UNKNOWN }
        p.joinToString(" ") { it.text } + if (read.all { it == UNKNOWN }) "" else read.joinToString(" ", "（", "）")
    }

    /** The app-language labels [spoken] reads, [stroke] a shape's name (竖钩). */
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
         * The card of [text], one Han character, readings ordered after [before]; null otherwise. [first], the reading
         * a candidate has it in, comes first marked [Row.inWord]; if the card lacks it, a wordless row of it is put
         * first, so the word's card and the character's never disagree. A word's 儿化 r has none.
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
            // a traditional character's words are its simplified one's
            val from = readings.readFrom(cp)
            val own = charReadings.of(from, card = true)
            val rows = ordered.map { r ->
                val i = own.indexOf(r)
                Row(r.pinyin, r.zhuyin, if (i < 0) emptyList() else charReadings.examples(from, i))
            }.let { if (first == null) it else firstOf(it, ordered, first) }
            val entry = info.of(cp)
            val parts = entry?.parts?.map { p -> Part(p, readings.ordered(p.codePointAt(0)).firstOrNull()?.pinyin) }
            // the mainland's order; for a character it lacks, the stroke table's (only the five kinds) matching Unihan's
            // count (礻 4, not 示's 5), else its first. The table is a pack file, read only then
            val order = entry?.order ?: strokes()?.let { tableOrder(text, entry?.strokes, it) }
            // counted as the order shown: Unihan's count may be Taiwan's glyph's (范 9, mainland order 8)
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

/** A candidate's reading of a character, for its word-card row or card's first row; [tone] null where unknown (䏝 chun). */
data class CardReading(val syllable: Int, val tone: Int?)
