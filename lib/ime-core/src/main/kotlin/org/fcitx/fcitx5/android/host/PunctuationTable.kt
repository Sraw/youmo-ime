/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * A punctuation map file, `punc.mb.<lang>`, as the punctuation addon reads and writes it
 * (`ca/punctuation/punctuation.cpp:75-173`): a line per key, the key (one character) then one or
 * two results, split at whitespace. The user's file, when there is one, replaces the built-in table
 * whole (`:410-430`); the editor saves the whole table.
 */
object PunctuationTable {

    data class Entry(val key: String, val mapping: String, val altMapping: String = "")

    /**
     * [bytes] as `PunctuationProfile::load` reads them (`:107-128`): lines trimmed, cut at
     * `FCITX_WHITESPACE` with empty pieces skipped; a line kept with two or three pieces of which at
     * least one is valid UTF-8 (fcitx's `any_of`) and a key of one valid character. `#` is a key
     * like any other. Duplicates are kept, in order (the first is the one typed).
     */
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

    /**
     * [entries] as the editor's table is taken (`PunctuationProfile::set`, `:130-152`): an entry with
     * no key or no first result, or a key not one character, left out.
     */
    fun accepted(entries: List<Entry>): List<Entry> = entries.filter {
        it.key.isNotEmpty() && it.mapping.isNotEmpty() && it.key.codePointCount(0, it.key.length) == 1
    }

    /** [entries] as `PunctuationProfile::save` writes them (`:154-173`): `key result[ alt]`, a line each. */
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
