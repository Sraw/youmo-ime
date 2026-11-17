/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.pinyin.Touch

/**
 * One thing a user types as [input], and what they meant by it, after [context] when there is
 * any: the text before the cursor. [taps]: where across its key each of [input] was tapped, for
 * the 14 keys ([Taps]); null for none. [touches]: where each was tapped and the keys around it
 * ([TouchSim]), null each one with no place.
 */
data class Sample(val input: String, val expected: String, val tag: String, val context: String = "", val taps: List<Float>? = null, val touches: List<Touch?>? = null)

object EvalSet {

    /**
     * `input<TAB>expected<TAB>tag[<TAB>context[<TAB>touches[<TAB>places]]]`; `#` starts a comment line.
     * The touches ([line]'s, `pinyin --touch … --touched`): each key's [Touch.text] a space apart, `-`
     * one with no place; the context before them may be empty. The places ([Sample.taps]): where across
     * its key each was tapped, as the 14 and 18 keys send it, `-` none; the touches before them may be
     * all `-` (`--taps`).
     */
    fun parse(lines: Sequence<String>): List<Sample> = lines
        .mapIndexedNotNull { index, line ->
            if (line.isBlank() || line.startsWith("#")) return@mapIndexedNotNull null
            val fields = line.split('\t')
            require(fields.size in 3..PLACES + 1 && fields.withIndex().none { (i, f) -> f.isEmpty() && (i != CONTEXT || fields.size == CONTEXT + 1) }) {
                "line ${index + 1}: expected input<TAB>expected<TAB>tag[<TAB>context[<TAB>touches[<TAB>places]]], got \"$line\""
            }
            val touches = fields.getOrNull(CONTEXT + 1)?.let(::touches)?.takeUnless { t -> t.all { it == null } && fields.size > PLACES }
            require(touches == null || touches.size == fields[0].length) { "line ${index + 1}: a touch for each key of ${fields[0]}" }
            val taps = fields.getOrNull(PLACES)?.split(' ')?.map { if (it == NO_TOUCH) Float.NaN else requireNotNull(it.toFloatOrNull()) { "no place: $it" } }
            require(taps == null || taps.size == fields[0].length) { "line ${index + 1}: a place for each key of ${fields[0]}" }
            Sample(fields[0], fields[1], fields[2], fields.getOrElse(3) { "" }, taps = taps, touches = touches)
        }
        .toList()

    // [text]'s touches: each key's a space apart, `-` none; a touch that reads as none fails
    private fun touches(text: String): List<Touch?> = text.split(' ').map { t ->
        if (t == NO_TOUCH) null else requireNotNull(Touch.parse(t)) { "no touch: $t" }
    }

    /** [s] as a line [parse] reads, its touches and places too. */
    fun line(s: Sample): String = listOf(s.input, s.expected, s.tag, s.context).joinToString("\t") +
        (s.touches ?: s.taps?.map { null })?.joinToString(" ", "\t") { it?.text() ?: NO_TOUCH }.orEmpty() +
        s.taps?.joinToString(" ", "\t") { if (it.isNaN()) NO_TOUCH else it.toString() }.orEmpty()

    private const val NO_TOUCH = "-"
    private const val CONTEXT = 3
    private const val PLACES = 5
}

/**
 * What an engine did with one [Sample]: the candidates it offered once the whole input was
 * typed, best first, and how long each keystroke took.
 */
data class RunResult(val input: String, val candidates: List<String>, val keyLatenciesMicros: List<Long>)

/**
 * `input<TAB>latency,latency,...<TAB>candidate<TAB>candidate...`, one line per sample. Plain TSV
 * so that the on-device runner can write it without pulling in a JSON library. Tabs and line
 * breaks inside a candidate (a custom phrase can hold them) become spaces; such a candidate
 * never equals an expected sentence anyway.
 */
object RunResultFormat {

    private val separators = Regex("[\t\r\n]")

    fun format(result: RunResult): String =
        (listOf(result.input, result.keyLatenciesMicros.joinToString(",")) + result.candidates.map { it.replace(separators, " ") })
            .joinToString("\t")

    fun parse(lines: Sequence<String>): List<RunResult> = lines
        .mapIndexedNotNull { index, line ->
            if (line.isBlank()) return@mapIndexedNotNull null
            val fields = line.split('\t')
            require(fields.size >= 2) { "line ${index + 1}: expected input<TAB>latencies[<TAB>candidates], got \"$line\"" }
            val latencies = if (fields[1].isEmpty()) emptyList() else fields[1].split(',').map { it.toLong() }
            RunResult(fields[0], fields.drop(2), latencies)
        }
        .toList()
}
