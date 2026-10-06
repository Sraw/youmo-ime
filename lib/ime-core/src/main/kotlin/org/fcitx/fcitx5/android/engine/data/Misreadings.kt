/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import java.text.Normalizer

/**
 * Words often read wrongly (般若 read ban ruo, not bō rě): `lexicon/misreadings.tsv`, a line
 * `word<TAB>reading with tones<TAB>misreading[,misreading]`, misreadings as `pin'yin`. The
 * dictionary has each under both (the misreadings lower), so either types it, and the readings
 * go into pinyin data ([META_PREFIX]): typed by another, a candidate shows its reading.
 */
class Misreadings(val word: String, val reading: String, val misread: List<String>) {

    /** [reading] as typed: `bo're`. */
    val typed: String get() = toneless(reading)

    companion object {
        /** A pinyin data meta key: the word after it; the value [meta]. */
        const val META_PREFIX = "misread."

        /** The reading, a tab, then the misreadings, `,` between them: what [fromMeta] reads back. */
        fun meta(entry: Misreadings) = entry.reading + "\t" + entry.misread.joinToString(",")

        fun fromMeta(word: String, value: String): Misreadings? {
            val (reading, misread) = value.split('\t').takeIf { it.size == 2 } ?: return null
            return Misreadings(word, reading, misread.split(','))
        }

        /** Under a misreading, a word is this much (log10) less likely than under its reading. */
        const val WEIGHT = -1f

        /**
         * @throws SourceException on a line that is not one, or whose readings have not a
         * syllable a character
         */
        fun parse(lines: Sequence<String>, source: String): List<Misreadings> = lines.mapIndexedNotNull { i, line ->
            if (line.isBlank() || line.startsWith('#')) return@mapIndexedNotNull null
            val f = line.split('\t').map { it.trim() }
            fun fail(why: String): Nothing = throw SourceException(source, i + 1, why)
            if (f.size < 3 || f.any { it.isEmpty() }) fail("expected \"word<TAB>reading<TAB>misreadings\"")
            val entry = Misreadings(f[0], f[1], f[2].split(','))
            val readings = listOf(entry.typed) + entry.misread
            if (readings.any { it.split('\'').size != f[0].length }) fail("not a syllable a character: $line")
            if (entry.typed in entry.misread) fail("a misreading is the reading: $line")
            entry
        }.toList()

        /** `bō rě` as typed, `bo're`; ü as v. */
        fun toneless(reading: String): String =
            Normalizer.normalize(reading.trim(), Normalizer.Form.NFD)
                .replace("ü", "v")
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                .split(' ').filter { it.isNotEmpty() }.joinToString("'")
    }
}
