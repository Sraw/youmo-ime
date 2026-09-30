/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import java.io.BufferedReader
import java.util.TreeMap

/**
 * Reads libime's dictionary text, one reading per line: `word pinyin [weight]`, fields separated
 * by any whitespace (the files mix tabs and spaces), syllables by `'`. The weight is a log10
 * adjustment for that reading (see [PinyinDataBuilder.entry]), 0 when absent.
 *
 * A reading with a syllable [Syllables] does not know is skipped and counted rather than fatal,
 * so a new dictionary release with one odd entry still builds; [unknownSyllables] says what to
 * add.
 */
class PinyinDictReader(private val into: PinyinDataBuilder) {

    var entries = 0
        private set

    /** Unknown syllable to how many readings it made us skip. */
    val unknownSyllables = TreeMap<String, Int>()

    val skipped: Int get() = unknownSyllables.values.sum()

    fun read(reader: BufferedReader, source: String) {
        reader.forEachNumberedLine(source) { line, _ ->
            val f = line.fields()
            if (f.isEmpty()) return@forEachNumberedLine
            require(f.size in 2..3) { "expected \"word pinyin [weight]\", got \"$line\"" }
            val weight = if (f.size == 3) requireNotNull(f[2].toFloatOrNull()) { "bad weight \"${f[2]}\"" } else 0f
            val spellings = f[1].split('\'')
            val ids = IntArray(spellings.size) { Syllables.id(spellings[it]) }
            val unknown = spellings.filterIndexed { i, _ -> ids[i] < 0 }
            if (unknown.isNotEmpty()) {
                unknown.forEach { unknownSyllables[it] = (unknownSyllables[it] ?: 0) + 1 }
                return@forEachNumberedLine
            }
            into.entry(f[0], ids, weight)
            entries++
        }
    }
}
