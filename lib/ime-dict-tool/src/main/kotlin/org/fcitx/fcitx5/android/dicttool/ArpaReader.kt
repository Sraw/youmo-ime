/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.data.SourceException
import org.fcitx.fcitx5.android.engine.data.fields
import org.fcitx.fcitx5.android.engine.data.forEachNumberedLine
import org.fcitx.fcitx5.android.engine.data.trimSeparators
import java.io.BufferedReader

/**
 * Streams an ARPA backoff language model, up to trigrams (what the engine scores with).
 * Checks the structure as it goes: the n-gram counts must match the `\data\` header, and a
 * missing backoff means 0, as ARPA specifies.
 */
object ArpaReader {

    const val MAX_ORDER = 3

    fun interface Sink {
        /** [words] has one to [MAX_ORDER] entries; [backoff] is 0 for the highest order. */
        fun ngram(words: List<String>, prob: Float, backoff: Float)
    }

    /** @return how many n-grams each order had, unigrams first */
    fun read(reader: BufferedReader, source: String, sink: Sink): List<Int> {
        val declared = ArrayList<Int>()
        val seen = IntArray(MAX_ORDER + 1)
        // -1 before \data\ (a preamble some toolkits write, skipped), 0 inside it, n inside \n-grams:
        var order = -1
        var ended = false
        reader.forEachNumberedLine(source) { raw, _ ->
            val line = raw.trimSeparators()
            if (line.isEmpty() || ended) return@forEachNumberedLine
            when {
                line == "\\data\\" -> order = 0
                line == "\\end\\" -> ended = true
                line.startsWith("\\") && line.endsWith("-grams:") -> {
                    order = requireNotNull(line.substring(1, line.length - "-grams:".length).toIntOrNull()) { "bad section $line" }
                    require(order in 1..declared.size) { "section $line is not in the \\data\\ header" }
                }
                order == 0 -> declared += count(line, declared.size + 1)
                order > 0 -> {
                    ngram(line, order, isHighest = order == declared.size, sink)
                    seen[order]++
                }
                else -> {}
            }
        }
        val problem = when {
            !ended -> "no \\end\\: the file is truncated"
            else -> declared.indices.firstOrNull { seen[it + 1] != declared[it] }
                ?.let { "the header declares ${declared[it]} ${it + 1}-grams, the file has ${seen[it + 1]}" }
        }
        if (problem != null) throw SourceException(source, 0, problem)
        return declared
    }

    private fun count(line: String, order: Int): Int {
        val match = requireNotNull(Regex("ngram (\\d+)\\s*=\\s*(\\d+)").matchEntire(line)) { "bad header line \"$line\"" }
        require(match.groupValues[1].toInt() == order) { "header lists order ${match.groupValues[1]} where $order was expected" }
        require(order <= MAX_ORDER) { "a $order-gram model; only up to $MAX_ORDER-grams are supported" }
        return match.groupValues[2].toInt()
    }

    private fun ngram(line: String, order: Int, isHighest: Boolean, sink: Sink) {
        val f = line.fields()
        val hasBackoff = f.size == order + 2
        require(f.size == order + 1 || (hasBackoff && !isHighest)) { "expected a $order-gram, got \"$line\"" }
        val prob = requireNotNull(f[0].toFloatOrNull()) { "bad probability \"${f[0]}\"" }
        val backoff = if (hasBackoff) requireNotNull(f[order + 1].toFloatOrNull()) { "bad backoff \"${f[order + 1]}\"" } else 0f
        sink.ngram(f.subList(1, order + 1), prob, backoff)
    }
}
