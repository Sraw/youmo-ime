/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

/** One thing a user types, and what they meant by it. */
data class Sample(val input: String, val expected: String, val tag: String)

object EvalSet {

    /** `input<TAB>expected<TAB>tag`; `#` starts a comment line. */
    fun parse(lines: Sequence<String>): List<Sample> = lines
        .mapIndexedNotNull { index, line ->
            if (line.isBlank() || line.startsWith("#")) return@mapIndexedNotNull null
            val fields = line.split('\t')
            require(fields.size == 3 && fields.none { it.isEmpty() }) {
                "line ${index + 1}: expected input<TAB>expected<TAB>tag, got \"$line\""
            }
            Sample(fields[0], fields[1], fields[2])
        }
        .toList()
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
        .filter { it.isNotBlank() }
        .mapIndexed { index, line ->
            val fields = line.split('\t')
            require(fields.size >= 2) { "line ${index + 1}: expected input<TAB>latencies[<TAB>candidates], got \"$line\"" }
            val latencies = if (fields[1].isEmpty()) emptyList() else fields[1].split(',').map { it.toLong() }
            RunResult(fields[0], fields.drop(2), latencies)
        }
        .toList()
}
