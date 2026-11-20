/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import java.text.Normalizer

/** Words often misread (般若 bō rě, not ban ruo) from `lexicon/misreadings.tsv`: the dictionary has both readings, misreadings lower, so the candidate shows the right one. */
class Misreadings(val word: String, val reading: String, val misread: List<String>) {

    val typed: String get() = toneless(reading)

    companion object {
        const val META_PREFIX = "misread."

        fun meta(entry: Misreadings) = entry.reading + "\t" + entry.misread.joinToString(",")

        fun fromMeta(word: String, value: String): Misreadings? {
            val (reading, misread) = value.split('\t').takeIf { it.size == 2 } ?: return null
            return Misreadings(word, reading, misread.split(','))
        }

        const val WEIGHT = -1f

        /** @throws SourceException on a malformed line, or readings without one syllable per character */
        fun parse(lines: Sequence<String>, source: String): List<Misreadings> = lines.mapIndexedNotNull { i, line ->
            if (line.isBlank() || line.startsWith('#')) return@mapIndexedNotNull null
            val f = line.split('\t').map { it.trim() }
            fun fail(why: String): Nothing = throw SourceException(source, i + 1, why)
            if (f.size < 3 || f.any { it.isEmpty() }) fail("expected \"word<TAB>reading<TAB>misreadings\"")
            val entry = Misreadings(f[0], f[1], f[2].split(','))
            val readings = listOf(entry.typed) + entry.misread
            // by code point: past U+FFFF is two chars but one syllable
            val characters = f[0].codePointCount(0, f[0].length)
            if (readings.any { it.split('\'').size != characters }) fail("not a syllable a character: $line")
            if (entry.typed in entry.misread) fail("a misreading is the reading: $line")
            entry
        }.toList()

        fun toneless(reading: String): String =
            Normalizer.normalize(reading.trim(), Normalizer.Form.NFD)
                // ü as NFD leaves it, written out: an editor saving this file as NFC cannot change it
                .replace("u\u0308", "v")
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                .split(' ').filter { it.isNotEmpty() }.joinToString("'")
    }
}
