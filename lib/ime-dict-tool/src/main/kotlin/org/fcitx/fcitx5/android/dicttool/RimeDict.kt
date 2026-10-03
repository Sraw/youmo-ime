/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.data.forEachNumberedLine
import java.io.BufferedReader
import java.math.BigDecimal
import java.math.MathContext
import java.text.Normalizer
import kotlin.math.log10

/**
 * Rime's dictionaries (`*.dict.yaml`: a YAML header, `...`, then `word<TAB>pin yin<TAB>count`
 * with toned syllables), as [PinyinDictReader] takes libime's: each word's readings weighted
 * log10((count + 1) / (its likeliest reading's count + 1)), so the likeliest is 0. Counts of the
 * same reading in several files add up. Only words of Han characters are taken: Rime's own
 * entries for Latin letters and symbols have no pinyin to type them by here.
 *
 * Read all the files first, then [entries]: a word's weights depend on every file it is in.
 */
class RimeDict {
    // word to reading (syllables joined by ') to count, in the order first seen
    private val counts = LinkedHashMap<String, LinkedHashMap<String, Long>>()

    fun read(reader: BufferedReader, source: String) {
        var body = false
        reader.forEachNumberedLine(source) { line, _ ->
            if (!body) {
                body = line == "..."
                return@forEachNumberedLine
            }
            if (line.isEmpty() || line.startsWith('#')) return@forEachNumberedLine
            val f = line.split('\t')
            if (f.size < 2 || !isHan(f[0])) return@forEachNumberedLine
            val reading = f[1].split(' ').filter { it.isNotEmpty() }.joinToString("'") { toneless(it) }
            val count = f.getOrNull(2)?.takeIf { s -> s.isNotEmpty() && s.all { it in '0'..'9' } }?.toLong() ?: 0L
            val readings = counts.getOrPut(f[0]) { LinkedHashMap() }
            readings[reading] = (readings[reading] ?: 0L) + count
        }
    }

    val words: Int get() = counts.size

    /** Each reading as `word`, `pin'yin`, weight. */
    fun entries(): Sequence<Triple<String, String, Float>> = counts.asSequence().flatMap { (word, readings) ->
        val top = readings.values.max()
        readings.asSequence().map { (reading, count) -> Triple(word, reading, weight(count, top)) }
    }

    companion object {
        // to six significant digits, as the conversion that was measured wrote them (dev/wanxiang)
        private fun weight(count: Long, top: Long): Float =
            BigDecimal(log10((count + 1).toDouble() / (top + 1))).round(MathContext(6)).toFloat()

        fun isHan(word: String) = word.isNotEmpty() && word.codePoints().allMatch { c ->
            c in 0x3400..0x9FFF || c in 0x20000..0x3134F || c in 0xF900..0xFAFF
        }

        /** `lǜ` to `lv`: tones dropped, ü written v, as the syllables are. */
        fun toneless(syllable: String): String {
            val decomposed = Normalizer.normalize(syllable, Normalizer.Form.NFD)
            val out = StringBuilder(decomposed.length)
            for (i in decomposed.indices) {
                val c = decomposed[i]
                when {
                    c == '̈' -> if (out.isNotEmpty() && out.last() == 'u') out.setCharAt(out.length - 1, 'v')
                    Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                    else -> out.append(c.lowercaseChar())
                }
            }
            return out.toString()
        }
    }
}
