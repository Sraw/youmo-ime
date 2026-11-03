/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.reading

import org.fcitx.fcitx5.android.engine.phrase.CharInfo
import org.fcitx.fcitx5.android.engine.stroke.Strokes

/**
 * What a character's card shows (a handwritten candidate held, or one looked up by its parts or
 * strokes): the character, each of its readings in pinyin and zhuyin with at most two words reading it
 * so, in the order the candidates list them; its radical, strokes, parts with their readings and the
 * order of its strokes. What is not known is null (乾, which 汉语拆字字典 does not take apart, has no
 * parts) and the card leaves it out.
 */
data class CharCard(
    val text: String,
    val readings: List<Row>,
    val radical: String?,
    val strokes: Int?,
    val parts: List<Part>?,
    val strokeOrder: String?,
) {
    /** A reading: [pinyin] háng, [zhuyin] ㄏㄤˊ (empty for a syllable it does not write), [examples] 银行. */
    data class Row(val pinyin: String, val zhuyin: String, val examples: List<String>)

    /** A part: its [text] and its commonest reading in pinyin (null for one with none: 丅). */
    data class Part(val text: String, val pinyin: String?)

    /** One sentence of it for TalkBack, the labels [words] gives: 行，读音 xíng、háng，部首 彳，6 画，拆字 彳 亍. */
    fun spoken(words: Words): String = buildList {
        add(text)
        if (readings.isNotEmpty()) add(words.readings + readings.joinToString(words.between) { it.pinyin })
        radical?.let { add(words.radical + it) }
        strokes?.let { add(words.strokes(it)) }
        parts?.let { p -> add(words.parts + p.joinToString(" ") { it.text }) }
    }.joinToString(words.comma)

    /** The labels [spoken] reads, in the app's language. */
    class Words(val readings: String, val radical: String, val parts: String, val between: String, val comma: String, val strokes: (Int) -> String)

    companion object {
        /**
         * The card of [text], one Han character, [before] the text written before it (its readings'
         * order: [CandidateReadings.ordered]); null for anything else.
         */
        fun of(text: String, before: String, readings: CandidateReadings, charReadings: CharReadings, info: CharInfo, strokes: Strokes?): CharCard? {
            if (text.isEmpty() || text.codePointCount(0, text.length) != 1) return null
            val cp = text.codePointAt(0)
            if (!Character.isIdeographic(cp)) return null
            val ordered = readings.ordered(cp, before)
            val own = charReadings.of(cp)
            val rows = ordered.map { r ->
                val i = own.indexOf(r)
                Row(r.pinyin, r.zhuyin, if (i < 0) emptyList() else charReadings.examples(cp, i))
            }
            val entry = info.of(cp)
            val parts = entry?.parts?.map { p -> Part(p, readings.ordered(p.codePointAt(0)).firstOrNull()?.pinyin) }
            val order = if (text.length == 1) strokes?.ordersOf(text[0])?.firstOrNull()?.let(Strokes::shown) else null
            return CharCard(text, rows, entry?.radical, entry?.strokes?.takeIf { it > 0 } ?: order?.length, parts, order)
        }
    }
}
