/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** `punc.mb.<lang>`: a key (one character) then one or two results per line; the user's file replaces the built-in whole. */
object PunctuationTable {

    data class Entry(val key: String, val mapping: String, val altMapping: String = "")

    /** As `PunctuationProfile::load`: trimmed, split at FCITX_WHITESPACE; kept with 2–3 pieces, one valid UTF-8, a one-character key. `#` is a key; duplicates kept in order. */
    fun parse(bytes: ByteArray): List<Entry> {
        val out = ArrayList<Entry>()
        var start = 0
        while (start <= bytes.size) {
            var end = start
            while (end < bytes.size && bytes[end] != '\n'.code.toByte()) end++
            line(bytes, start, end)?.let(out::add)
            start = end + 1
        }
        return out
    }

    private fun line(bytes: ByteArray, from: Int, to: Int): Entry? {
        val tokens = ArrayList<ByteArray>(3)
        var i = from
        while (i < to) {
            while (i < to && isSpace(bytes[i])) i++
            val s = i
            while (i < to && !isSpace(bytes[i])) i++
            if (i > s) tokens += bytes.copyOfRange(s, i)
        }
        if (tokens.size != 2 && tokens.size != 3) return null
        val decoded = tokens.map(::decode)
        if (decoded.all { it == null }) return null
        val key = decoded[0] ?: return null
        if (key.codePointCount(0, key.length) != 1) return null
        // a piece that is not UTF-8 beside one that is: fcitx keeps its bytes; replaced here
        fun text(i: Int) = decoded[i] ?: tokens[i].decodeToString()
        return Entry(key, text(1), if (tokens.size > 2) text(2) else "")
    }

    /** As `PunctuationProfile::set`: no key, no first result, or a multi-character key left out. */
    fun accepted(entries: List<Entry>): List<Entry> = entries.filter {
        it.key.isNotEmpty() && it.mapping.isNotEmpty() && it.key.codePointCount(0, it.key.length) == 1
    }

    /** As `PunctuationProfile::save`: `key result[ alt]`. */
    fun write(entries: List<Entry>): ByteArray = buildString {
        for (e in entries) {
            append(e.key).append(' ').append(e.mapping)
            if (e.altMapping.isNotEmpty()) append(' ').append(e.altMapping)
            append('\n')
        }
    }.encodeToByteArray()

    private fun isSpace(b: Byte) = b == 0x20.toByte() || b in 0x09.toByte()..0x0D.toByte()

    private fun decode(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
