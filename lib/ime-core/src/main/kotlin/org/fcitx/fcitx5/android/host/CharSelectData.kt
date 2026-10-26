/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import java.math.BigInteger

/**
 * The names [CharSelectData.name] makes up for ranges the data does not list
 * (`charselectdata.cpp:189-245`); the app passes its translations, `%X` is the code point in hex.
 */
data class CharNames(
    val cjkIdeograph: String = "CJK UNIFIED IDEOGRAPH-%X",
    val hangulSyllable: String = "HANGUL SYLLABLE %s",
    val nonPrivateHighSurrogate: String = "<Non Private Use High Surrogate>",
    val privateHighSurrogate: String = "<Private Use High Surrogate>",
    val lowSurrogate: String = "<Low Surrogate>",
    val privateUse: String = "<Private Use>",
    val tangutIdeograph: String = "TANGUT IDEOGRAPH-%X",
    val plane15PrivateUse: String = "<Plane 15 Private Use>",
    val plane16PrivateUse: String = "<Plane 16 Private Use>",
    val notAssigned: String = "<not assigned>",
)

/**
 * fcitx's `CharSelectData` (`modules/unicode/charselectdata.cpp`, from KDE's kcharselect), over the
 * same `charselectdata` file (design Q4): character names, and the search the Unicode temp mode runs.
 *
 * The file is little-endian: at 4/8 the name table (8-byte records: code point, offset of the
 * name, which starts one byte in), at 12/16 the details (29-byte records: aliases, notes,
 * approximate equivalents, equivalents and "see also"), at 36 to the end Unihan (32-byte records:
 * seven string offsets). The index (`createIndex`, `:480-588`) maps every whitespace-separated word
 * of all of these to the code points it describes, the words sorted ignoring ASCII case.
 *
 * The caller loads [data] lazily, as fcitx does at the first trigger (`unicodetempmode.cpp:143-152`).
 * Names are read from it directly; the search's index (some 10 MB of words) is built at the first
 * [find] or [prepare], once, from any thread.
 */
class CharSelectData(private val data: ByteArray, private val names: CharNames = CharNames()) {

    private class Index(val words: Array<String>, val chars: Array<IntArray>)

    // synchronized: prepare() on a background thread, a find() meanwhile waits for it
    private val index by lazy {
        val map = HashMap<String, IntList>()
        createIndex(map)
        val sorted = map.entries.sortedWith { a, b -> CASE_INSENSITIVE.compare(a.key, b.key) }
        Index(Array(sorted.size) { sorted[it].key }, Array(sorted.size) { sorted[it].value.toArray() })
    }

    /** The search's index built now, so that the first [find] does not wait for it. */
    fun prepare() {
        index
    }

    private class IntList {
        var items = IntArray(2)
        var size = 0

        fun add(v: Int) {
            if (size == items.size) items = items.copyOf(size * 2)
            items[size++] = v
        }

        fun toArray() = items.copyOf(size)
    }

    private fun u32(at: Int): Int =
        (data[at].toInt() and 0xff) or
            ((data[at + 1].toInt() and 0xff) shl 8) or
            ((data[at + 2].toInt() and 0xff) shl 16) or
            ((data[at + 3].toInt() and 0xff) shl 24)

    private fun u8(at: Int) = data[at].toInt() and 0xff

    private fun cStringEnd(at: Int): Int {
        var e = at
        while (data[e] != 0.toByte()) e++
        return e
    }

    private fun cString(at: Int) = String(data, at, cStringEnd(at) - at, Charsets.UTF_8)

    /** `appendToIndex` (`:469-478`): each whitespace-separated word of the C string at [at]. */
    private fun appendToIndex(index: HashMap<String, IntList>, unicode: Int, at: Int) {
        appendWords(index, unicode, cString(at))
    }

    private fun appendWords(index: HashMap<String, IntList>, unicode: Int, text: String) {
        for (w in splitWhitespace(text)) index.getOrPut(w) { IntList() }.add(unicode)
    }

    @Suppress("MagicNumber", "NestedBlockDepth")
    private fun createIndex(index: HashMap<String, IntList>) {
        val nameBegin = u32(4)
        val nameEnd = u32(8)
        for (pos in 0 until (nameEnd - nameBegin) / 8) {
            val r = nameBegin + pos * 8
            appendToIndex(index, u32(r), u32(r + 4) + 1)
        }
        val detailsBegin = u32(12)
        val detailsEnd = u32(16)
        for (pos in 0 until (detailsEnd - detailsBegin) / 29) {
            val r = detailsBegin + pos * 29
            // fcitx reads the record's code point as 32 bits here (`:513-514`) and as 16 elsewhere
            val unicode = u32(r)
            // aliases, notes, approximate equivalents, equivalents: (count byte, offset) pairs
            for ((countAt, offsetAt) in DETAIL_LISTS) {
                var offset = u32(r + offsetAt)
                repeat(u8(r + countAt)) {
                    appendToIndex(index, unicode, offset)
                    offset = cStringEnd(offset) + 1
                }
            }
            // "see also": fcitx advances the equivalents' offset instead of this one (`:567-571`), so
            // every entry indexes the first code point, as four hex digits
            val seeAlsoCount = u8(r + 28)
            val seeAlsoOffset = u32(r + 24)
            repeat(seeAlsoCount) {
                val c = (data[seeAlsoOffset].toInt() and 0xff) or ((data[seeAlsoOffset + 1].toInt() and 0xff) shl 8)
                appendWords(index, unicode, "%04x".format(c))
            }
        }
        val unihanBegin = u32(36)
        for (pos in 0 until (data.size - unihanBegin) / 32) {
            val r = unihanBegin + pos * 32
            val unicode = u32(r)
            for (j in 0 until 7) {
                val offset = u32(r + 4 + j * 4)
                if (offset != 0) appendToIndex(index, unicode, offset)
            }
        }
    }

    /** `name` (`:183-267`). */
    @Suppress("MagicNumber", "CyclomaticComplexMethod", "ComplexCondition")
    fun name(unicode: Int): String {
        val result = when {
            unicode in 0x3400..0x4DBF || unicode in 0x4E00..0x9FFF || unicode in 0x20000..0x2A6DF ||
                unicode in 0x2A700..0x2B739 || unicode in 0x2B740..0x2B81D || unicode in 0x2B820..0x2CEA1 ||
                unicode in 0x2CEB0..0x2EBE0 || unicode in 0x2EBF0..0x2EE5D || unicode in 0x30000..0x3134A ||
                unicode in 0x31350..0x323AF -> names.cjkIdeograph.format(unicode)
            unicode in 0xAC00..0xD7AF -> hangul(unicode)
            unicode in 0xD800..0xDB7F -> names.nonPrivateHighSurrogate
            unicode in 0xDB80..0xDBFF -> names.privateHighSurrogate
            unicode in 0xDC00..0xDFFF -> names.lowSurrogate
            unicode in 0xE000..0xF8FF -> names.privateUse
            unicode in 0x17000..0x187F7 || unicode in 0x18D00..0x18D08 -> names.tangutIdeograph.format(unicode)
            unicode in 0xF0000..0xFFFFD -> names.plane15PrivateUse
            unicode in 0x100000..0x10FFFD -> names.plane16PrivateUse
            else -> listedName(unicode)
        }
        return result.ifEmpty { names.notAssigned }
    }

    /** UAX #15's syllable name (`:200-221`); past the last syllable, none. */
    @Suppress("MagicNumber")
    private fun hangul(unicode: Int): String {
        val s = unicode - 0xAC00
        if (s >= 19 * 21 * 28) return ""
        val l = s / (21 * 28)
        val v = s % (21 * 28) / 28
        val t = s % 28
        return names.hangulSyllable.format(JAMO_L[l] + JAMO_V[v] + JAMO_T[t])
    }

    /** The binary search of the name table (`:229-251`), unsigned as the code points are. */
    @Suppress("MagicNumber")
    private fun listedName(unicode: Int): String {
        val begin = u32(4)
        var min = 0
        var max = (u32(8) - begin) / 8 - 1
        while (max >= min) {
            val mid = (min + max) / 2
            val at = u32(begin + mid * 8)
            val cmp = Integer.compareUnsigned(unicode, at)
            when {
                cmp > 0 -> min = mid + 1
                cmp < 0 -> max = mid - 1
                else -> return cString(u32(begin + mid * 8 + 4) + 1)
            }
        }
        return ""
    }

    /**
     * `find` (`:313-383`): what the Unicode search lists for [needle], in order.
     *
     * The needle's whitespace runs become one space and it is split into words; a needle of one
     * byte is searched as its code, `U+` and four lowercase hex digits. Each word that is a number
     * (`0x`/`u+`/`U+` and at least four hex digits: that, and the same digits read as decimal; else
     * the word as decimal, as `std::stoul` reads it) puts that code point first, in word order,
     * repeats kept. Then the code points every word is a case-insensitive prefix of a word of,
     * ascending, without those already listed. The values are fcitx's `uint32_t`s (bits in an
     * [Int]): a number may be no character at all, which the caller drops.
     */
    @Suppress("MagicNumber", "ReturnCount")
    fun find(needle: String): IntArray {
        val simplified = simplify(needle)
        var searchStrings = splitWhitespace(simplified)
        if (simplified.toByteArray().size == 1) {
            searchStrings = listOf("U+%04x".format(simplified[0].code))
        }
        if (searchStrings.isEmpty()) return IntArray(0)
        val numbers = ArrayList<Int>()
        for (s in searchStrings) {
            if (isHexString(s)) {
                val sub = s.substring(2)
                stoul(sub, 16)?.let { numbers.add(it) }
                stoul(sub, 10)?.let { numbers.add(it) }
            } else {
                stoul(s, 10)?.let { numbers.add(it) }
            }
        }
        var result: java.util.TreeSet<Int>? = null
        for (s in searchStrings) {
            val part = matchingChars(s)
            result = if (result == null || result.isEmpty()) part else result.apply { retainAll(part) }
            if (result.isEmpty()) break
        }
        val rest = result.orEmpty().toMutableSet().apply { removeAll(numbers.toSet()) }
        return (numbers + rest).toIntArray()
    }

    /** `matchingChars` (`:385-404`): every word starting with [s], ignoring ASCII case. */
    private fun matchingChars(s: String): java.util.TreeSet<Int> {
        val result = java.util.TreeSet<Int>(Integer::compareUnsigned)
        var lo = 0
        val words = index.words
        val chars = index.chars
        var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (CASE_INSENSITIVE.compare(words[mid], s) < 0) lo = mid + 1 else hi = mid
        }
        while (lo < words.size && startsWithIgnoringAsciiCase(words[lo], s)) {
            chars[lo].forEach { result.add(it) }
            lo++
        }
        return result
    }

    companion object {
        /** (count, offset) of aliases, notes, approximate equivalents and equivalents in a details record. */
        private val DETAIL_LISTS = listOf(8 to 4, 13 to 9, 18 to 14, 23 to 19)

        private val JAMO_L = arrayOf(
            "G", "GG", "N", "D", "DD", "R", "M", "B", "BB", "S", "SS", "", "J", "JJ", "C", "K", "T", "P", "H",
        )
        private val JAMO_V = arrayOf(
            "A", "AE", "YA", "YAE", "EO", "E", "YEO", "YE", "O", "WA", "WAE",
            "OE", "YO", "U", "WEO", "WE", "WI", "YU", "EU", "YI", "I",
        )
        private val JAMO_T = arrayOf(
            "", "G", "GG", "GS", "N", "NJ", "NH", "D", "L", "LG", "LM", "LB", "LS", "LT",
            "LP", "LH", "M", "B", "BS", "S", "SS", "NG", "J", "C", "K", "T", "P", "H",
        )

        private fun isWhitespace(c: Char) = c == ' ' || c in '\t'..'\r'

        private fun foldAscii(c: Int) = if (c in 'A'.code..'Z'.code) c + ('a' - 'A') else c

        /** `strcasecmp` on UTF-8: bytes with ASCII folded, which orders as code points do. */
        private val CASE_INSENSITIVE = Comparator<String> { a, b ->
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = foldAscii(a.codePointAt(i))
                val cb = foldAscii(b.codePointAt(j))
                if (ca != cb) return@Comparator ca.compareTo(cb)
                i += Character.charCount(a.codePointAt(i))
                j += Character.charCount(b.codePointAt(j))
            }
            (a.length - i).compareTo(b.length - j)
        }

        /** `strncasecmp(s, word, strlen(s)) == 0`. */
        private fun startsWithIgnoringAsciiCase(word: String, s: String): Boolean =
            word.length >= s.length && (0 until s.length).all { foldAscii(word[it].code) == foldAscii(s[it].code) }

        /** `stringutils::split(s, FCITX_WHITESPACE)`: words, empty ones skipped. */
        private fun splitWhitespace(s: String): List<String> {
            val out = ArrayList<String>()
            var start = -1
            for (i in s.indices) {
                if (isWhitespace(s[i])) {
                    if (start >= 0) out.add(s.substring(start, i))
                    start = -1
                } else if (start < 0) {
                    start = i
                }
            }
            if (start >= 0) out.add(s.substring(start))
            return out
        }

        /** `Simplified` (`:269-292`): each run of whitespace becomes one space; nothing trimmed. */
        private fun simplify(s: String): String = buildString {
            var lastIsSpace = false
            for (c in s) {
                if (isWhitespace(c)) {
                    if (!lastIsSpace) append(' ')
                    lastIsSpace = true
                } else {
                    append(c)
                    lastIsSpace = false
                }
            }
        }

        /** `IsHexString` (`:294-311`): `0x`, `0X`, `u+` or `U+`, then hex digits, six characters at least. */
        @Suppress("MagicNumber")
        private fun isHexString(s: String): Boolean {
            if (s.length < 6) return false
            val p = s.substring(0, 2)
            if (p !in HEX_PREFIXES) return false
            return s.substring(2).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }

        private val HEX_PREFIXES = setOf("0x", "0X", "u+", "U+")

        private val ULONG_RANGE = BigInteger.ONE.shiftLeft(64)

        /**
         * `std::stoul(s, &end, base)` with `end == s.size()`, cast to `uint32_t`, for a word with no
         * whitespace: an optional sign (a minus wraps around, `strtoul`), for base 16 an optional `0x`,
         * then at least one digit and nothing else; null where fcitx catches the exception (no
         * digits, or past `unsigned long`'s 64 bits) or `end` falls short.
         */
        @Suppress("MagicNumber", "ReturnCount")
        private fun stoul(s: String, base: Int): Int? {
            var i = 0
            var negative = false
            if (i < s.length && (s[i] == '+' || s[i] == '-')) {
                negative = s[i] == '-'
                i++
            }
            val hexPrefix = i + 2 < s.length && s.startsWith("0x", i, ignoreCase = true)
            if (base == 16 && hexPrefix && Character.digit(s[i + 2], 16) >= 0) {
                i += 2
            }
            val digits = s.substring(i)
            if (digits.isEmpty() || digits.any { c -> c.code >= 0x80 || Character.digit(c, base) < 0 }) return null
            var value = BigInteger(digits, base)
            if (value >= ULONG_RANGE) return null
            if (negative) value = ULONG_RANGE.subtract(value).mod(ULONG_RANGE)
            return value.toInt()
        }
    }
}
