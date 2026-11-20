/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.phrase

/**
 * Characters by the pinyin of their parts (拆字, from kfcd/chaizi, CC BY 3.0): `niuniuniu` 犇. Full pinyin
 * offers them after [KEY], like 搜狗's U mode. File lines: `<letters><TAB><character>...`, commonest first.
 */
class Chaizi private constructor(private val chars: Map<String, List<String>>) {

    fun of(letters: String): List<String> = chars[letters].orEmpty()

    /** Repeats a character spelt more than one way. */
    fun all(): Sequence<String> = chars.values.asSequence().flatten()

    val size get() = chars.size

    companion object {
        val EMPTY = Chaizi(emptyMap())

        /** No pinyin starts with it. */
        const val KEY = 'u'

        const val MAX = 8

        /** Lines that do not parse, and `#` comments, are skipped. */
        fun read(text: CharSequence): Chaizi {
            val chars = HashMap<String, List<String>>()
            for (line in text.lineSequence()) {
                val letters = line.substringBefore('\t', "")
                if (letters.isEmpty() || !letters.all { it in 'a'..'z' }) continue
                val found = line.substringAfter('\t').split(' ').filter { it.isNotEmpty() }
                if (found.isNotEmpty()) chars[letters] = found.take(MAX)
            }
            return Chaizi(chars)
        }

        fun format(chars: Map<String, List<String>>, header: String = ""): String = buildString {
            for (line in header.lines()) if (line.isNotEmpty()) append("# ").append(line).append('\n')
            for ((letters, found) in chars.toSortedMap()) {
                if (found.isEmpty()) continue
                append(letters).append('\t').append(found.take(MAX).joinToString(" ")).append('\n')
            }
        }
    }
}
