/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.data.Misreadings
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.fields
import org.fcitx.fcitx5.android.engine.data.forEachNumberedLine
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import java.io.BufferedReader
import java.util.TreeMap

/** Reads libime's dictionary text with `lexicon/`'s corrections; an unknown syllable is skipped and counted. */
class PinyinDictReader(
    private val into: PinyinDataBuilder,
    private val removed: Set<String> = emptySet(),
    private val readings: Map<String, List<String>> = emptyMap(),
    private val misread: Map<String, List<String>> = emptyMap(),
) {

    var entries = 0
        private set

    /** Readings of the dictionaries left out for a word of [removed], or replaced by [readings]. */
    var corrections = 0
        private set

    /** Unknown syllable to how many readings it made us skip. */
    val unknownSyllables = TreeMap<String, Int>()

    var skipped = 0
        private set

    fun read(reader: BufferedReader, source: String) {
        reader.forEachNumberedLine(source) { line, _ ->
            val f = line.fields()
            if (f.isEmpty()) return@forEachNumberedLine
            require(f.size in 2..3) { "expected \"word pinyin [weight]\", got \"$line\"" }
            val weight = if (f.size == 3) requireNotNull(f[2].toFloatOrNull()) { "bad weight \"${f[2]}\"" } else 0f
            if (f[0] in removed || f[0] in readings || f[0] in misread) corrections++ else add(f[0], f[1], weight)
        }
    }

    /** The words of [readings], each under the readings listed: after every dictionary is read. */
    fun corrected() {
        for ((word, list) in readings) if (word !in misread && word !in removed) list.forEach { add(word, it, 0f) }
        for ((word, list) in misread) if (word !in removed) list.forEachIndexed { i, r -> add(word, r, if (i == 0) 0f else Misreadings.WEIGHT) }
    }

    /** One reading, [pinyin] with its syllables separated by `'`. */
    private fun add(word: String, pinyin: String, weight: Float) {
        val spellings = pinyin.split('\'')
        val ids = IntArray(spellings.size) { Syllables.id(spellings[it]) }
        val unknown = spellings.filterIndexed { i, _ -> ids[i] < 0 }
        if (unknown.isNotEmpty()) {
            unknown.distinct().forEach { unknownSyllables[it] = (unknownSyllables[it] ?: 0) + 1 }
            skipped++
            return
        }
        into.entry(word, ids, weight)
        entries++
    }
}
