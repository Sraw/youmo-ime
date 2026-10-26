/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.IntBuffer

/**
 * One OpenCC dictionary: sorted keys (by UTF-16 unit, `String.compareTo`), each with its default
 * value (`DictEntry::GetDefault`, the first of a line's values) — all a conversion reads.
 */
class OpenCCDict internal constructor(
    private val keyOffsets: IntBuffer,
    private val keyChars: CharBuffer,
    private val valueOffsets: IntBuffer,
    private val valueChars: CharBuffer,
    /** The longest key, in UTF-16 units. */
    val maxKeyLength: Int,
) {
    val size: Int get() = keyOffsets.limit() - 1

    /**
     * The longest key that [text] has at [start] (not past [end]), its index, or -1: what
     * `MarisaDict::MatchPrefix` gives (`common_prefix_search`'s last match, OpenCC 1.2.0
     * `src/MarisaDict.cpp:63-77`). One pass along [text]: the keys sharing its first units narrowed
     * a unit at a time, stopping where none is left, rather than a search per length.
     */
    fun matchPrefix(text: String, start: Int, end: Int): Int {
        // keys sorted as [compare] orders them: those sharing a prefix are a run, the one that is
        // just the prefix first, then by the next unit
        var low = 0
        var high = size
        var best = -1
        for (length in 1..minOf(maxKeyLength, end - start)) {
            val c = text[start + length - 1]
            low = bound(low, high, length - 1) { it >= c }
            high = bound(low, high, length - 1) { it > c }
            if (low >= high) break
            if (keyLength(low) == length) best = low
        }
        return best
    }

    // the first key in [low, high) longer than [at] whose unit at [at] passes [past]; shorter ones
    // (no unit there) come first in the run
    private inline fun bound(low: Int, high: Int, at: Int, past: (Char) -> Boolean): Int {
        var lo = low
        var hi = high
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keyLength(mid) > at && past(keyChars[keyOffsets[mid] + at])) hi = mid else lo = mid + 1
        }
        return lo
    }

    fun keyLength(index: Int): Int = keyOffsets[index + 1] - keyOffsets[index]

    fun value(index: Int): String {
        val from = valueOffsets[index]
        return text(valueChars, from, valueOffsets[index + 1] - from)
    }

    private fun text(chars: CharBuffer, from: Int, length: Int): String {
        val out = CharArray(length)
        for (j in 0 until length) out[j] = chars[from + j]
        return String(out)
    }
}

/**
 * OpenCC 1.2.0's `s2tw.json` in Kotlin, the conversion chttrans runs (`ca/chttrans/chttrans-opencc.cpp:29-36`
 * prefers s2tw). As 1.2.0's `Converter::Convert` (`src/Converter.cpp`):
 *
 * 1. `MaxMatchSegmentation` on STPhrases (`src/MaxMatchSegmentation.cpp`): at each place the
 *    longest key is a segment of its own; characters no key starts at gather into one segment
 *    between them. The text ends at the first NUL, as the C string it is there.
 * 2. Each step of the chain converts **each segment on its own** (`src/Conversion.cpp`), so no
 *    match crosses a segment's end: at each place the longest key's default value, else the
 *    character as it is. Step one is the group STPhrases, STCharacters: the **first** dictionary
 *    with any match, its longest (`src/DictGroup.cpp` `MatchPrefix`), not the longest of the two.
 *    Step two is TWVariants.
 *
 * A character is a code point; a lone surrogate is one character, as its CESU-8 form is to OpenCC.
 * The data is frozen at 1.2.0 (Q8); [OpenCCData] is its compiled form.
 */
class ChineseConverter(
    private val phrases: OpenCCDict,
    private val characters: OpenCCDict,
    private val variants: OpenCCDict,
) {
    // the chain's two steps, made once: this runs on every candidate, preedit and aux shown
    private val group = listOf(phrases, characters)
    private val chain2 = listOf(variants)

    fun convert(text: String): String {
        val nul = text.indexOf('\u0000')
        val end = if (nul < 0) text.length else nul
        val out = StringBuilder(end)
        for (segment in segment(text, end)) {
            out.append(convert(convert(segment, group), chain2))
        }
        return out.toString()
    }

    private fun segment(text: String, end: Int): List<String> {
        val segments = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < end) {
            val m = phrases.matchPrefix(text, i, end)
            if (m < 0) {
                i += charLength(text, i, end)
            } else {
                if (i > start) segments.add(text.substring(start, i))
                val length = phrases.keyLength(m)
                segments.add(text.substring(i, i + length))
                i += length
                start = i
            }
        }
        if (i > start) segments.add(text.substring(start, i))
        return segments
    }

    private fun convert(segment: String, dicts: List<OpenCCDict>): String {
        val out = StringBuilder(segment.length)
        var i = 0
        while (i < segment.length) {
            var matched = false
            for (dict in dicts) {
                val m = dict.matchPrefix(segment, i, segment.length)
                if (m >= 0) {
                    out.append(dict.value(m))
                    i += dict.keyLength(m)
                    matched = true
                    break
                }
            }
            if (!matched) {
                val n = charLength(segment, i, segment.length)
                out.append(segment, i, i + n)
                i += n
            }
        }
        return out.toString()
    }

    private fun charLength(text: String, i: Int, end: Int) =
        if (Character.isHighSurrogate(text[i]) && i + 1 < end && Character.isLowSurrogate(text[i + 1])) 2 else 1

    companion object {
        /** The dictionaries of s2tw, in [OpenCCData]'s order. */
        val S2TW = listOf("STPhrases", "STCharacters", "TWVariants")

        fun s2tw(dicts: List<OpenCCDict>): ChineseConverter {
            require(dicts.size == S2TW.size)
            return ChineseConverter(dicts[0], dicts[1], dicts[2])
        }
    }
}

/**
 * `engine/opencc-s2tw.data`, what ime-dict-tool's `opencc` compiles from OpenCC 1.2.0's dictionary
 * text: read straight out of a mapped buffer, nothing copied to the heap but the values converted.
 *
 * Big-endian: the magic `OCS2TW01`, the number of dictionaries, then each: its key count n, its
 * longest key, its key and value lengths in chars, n+1 key offsets, n+1 value offsets, the keys'
 * chars, the values' chars (UTF-16).
 */
object OpenCCData {
    private val MAGIC = "OCS2TW01".toByteArray(Charsets.US_ASCII)

    /** One dictionary's text (`key\tvalue[ value...]`, `#` lines and lines with no tab skipped, as 1.2.0's `Lexicon`). */
    fun parse(text: String): List<Pair<String, String>> = text.lineSequence()
        .filter { it.isNotEmpty() && !it.startsWith("#") && '\t' in it }
        .map { line -> line.substringBefore('\t') to line.substringAfter('\t').trim().substringBefore(' ') }
        .toList()

    fun write(dicts: List<List<Pair<String, String>>>): ByteArray {
        val sorted = dicts.map { d ->
            d.sortedBy { it.first }.also { s ->
                require(s.zipWithNext().none { (a, b) -> a.first == b.first }) { "a key twice" }
            }
        }
        val size = MAGIC.size + 4 + sorted.sumOf { d ->
            16 + 8 * (d.size + 1) + 2 * d.sumOf { it.first.length + it.second.length }
        }
        val b = ByteBuffer.allocate(size)
        b.put(MAGIC).putInt(sorted.size)
        for (d in sorted) {
            b.putInt(d.size).putInt(d.maxOfOrNull { it.first.length } ?: 0)
            b.putInt(d.sumOf { it.first.length }).putInt(d.sumOf { it.second.length })
            var off = 0
            b.putInt(0)
            d.forEach { off += it.first.length; b.putInt(off) }
            off = 0
            b.putInt(0)
            d.forEach { off += it.second.length; b.putInt(off) }
            d.forEach { e -> e.first.forEach { b.putChar(it) } }
            d.forEach { e -> e.second.forEach { b.putChar(it) } }
        }
        check(!b.hasRemaining())
        return b.array()
    }

    fun read(buffer: ByteBuffer): List<OpenCCDict> {
        val b = buffer.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also { b.get(it) }
        require(magic.contentEquals(MAGIC)) { "not an OpenCC data file" }
        return List(b.int) {
            val n = b.int
            val maxKey = b.int
            val keyChars = b.int
            val valueChars = b.int
            val keyOffsets = slice(b, 4 * (n + 1)).asIntBuffer()
            val valueOffsets = slice(b, 4 * (n + 1)).asIntBuffer()
            val keys = slice(b, 2 * keyChars).asCharBuffer()
            val values = slice(b, 2 * valueChars).asCharBuffer()
            OpenCCDict(keyOffsets, keys, valueOffsets, values, maxKey)
        }
    }

    private fun slice(b: ByteBuffer, bytes: Int): ByteBuffer {
        val s = b.slice().order(java.nio.ByteOrder.BIG_ENDIAN)
        // through Buffer: ByteBuffer's own limit/position overloads are Java 9's, not Android 29's
        (s as java.nio.Buffer).limit(bytes)
        (b as java.nio.Buffer).position(b.position() + bytes)
        return s
    }
}
