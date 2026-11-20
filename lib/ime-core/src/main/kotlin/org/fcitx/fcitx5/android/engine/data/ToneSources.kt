/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.reading.ToneMark

/** Tone sources as text: pinyin-data (`U+597D: hǎo,hào`), phrase-pinyin-data (`爱好: ài hào`), `lexicon/tones.tsv`; an untypable syllable (ê̄) is skipped. */
object ToneSources {

    fun chars(lines: Sequence<String>, source: String, add: (Int, Int, Int) -> Unit) = lines.forEachIndexed { i, line ->
        val body = line.substringBefore('#').trim()
        if (body.isEmpty()) return@forEachIndexed
        val colon = body.indexOf(':')
        val cp = body.takeIf { it.startsWith("U+") && colon > 2 }?.substring(2, colon)?.toIntOrNull(16)
            ?: throw SourceException(source, i + 1, "expected \"U+XXXX: reading,reading\"")
        if (cp >= ToneTable.MAX_CODE_POINT) return@forEachIndexed
        body.substring(colon + 1).split(',', ' ').filter { it.isNotEmpty() }.forEach { r ->
            syllable(r)?.let { (s, t) -> add(cp, s, t) }
        }
    }

    /** A word without one syllable per character is skipped. */
    fun phrases(lines: Sequence<String>, source: String, add: (String, IntArray, ByteArray) -> Unit) = lines.forEachIndexed { i, line ->
        val body = line.substringBefore('#').trim()
        if (body.isEmpty()) return@forEachIndexed
        val colon = body.indexOf(':').takeIf { it > 0 } ?: throw SourceException(source, i + 1, "expected \"word: reading\"")
        word(body.substring(0, colon).trim(), body.substring(colon + 1), add)
    }

    /** Unlike downloaded lists, a line without one syllable per character is an error. */
    fun curated(lines: Sequence<String>, source: String, add: (String, IntArray, ByteArray) -> Unit) = lines.forEachIndexed { i, line ->
        if (line.isBlank() || line.startsWith('#')) return@forEachIndexed
        val f = line.split('\t').map { it.trim() }
        if (f.size < 2 || f[0].isEmpty()) throw SourceException(source, i + 1, "expected \"word<TAB>reading<TAB>date<TAB>note\"")
        if (!word(f[0], f[1], add)) throw SourceException(source, i + 1, "\"${f[1]}\" is not a syllable a character of ${f[0]}")
    }

    /** A reading as [Misreadings.reading] writes it (`bō rě`). */
    fun word(word: String, reading: String, add: (String, IntArray, ByteArray) -> Unit): Boolean {
        val read = reading.trim().split(' ').filter { it.isNotEmpty() }.map { syllable(it) ?: return false }
        if (read.isEmpty() || read.size != word.codePointCount(0, word.length)) return false
        add(word, IntArray(read.size) { read[it].first }, ByteArray(read.size) { read[it].second.toByte() })
        return true
    }

    private fun syllable(marked: String): Pair<Int, Int>? {
        val (spelling, tone) = ToneMark.parse(marked) ?: return null
        // parse reads lower case, so never one of the spelt-out Latin letters
        return Syllables.id(spelling).takeIf { it >= 0 }?.let { it to tone }
    }
}
