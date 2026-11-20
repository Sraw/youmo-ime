/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import java.nio.ByteBuffer

/** A compiled code table (五笔, 仓颉, ...): entries sorted by code, ties in source order as users expect. */
class CodeTable private constructor(file: DataFile) {

    val header: Map<String, String>
    val rules: Map<String, String>
    private val codes = StringTable(file.bitPacked(CODE_OFFSETS), file.section(CODE_CHARS))
    private val texts = StringTable(file.bitPacked(TEXT_OFFSETS), file.section(TEXT_CHARS))

    init {
        val meta = file.meta(META)
        header = meta.filterKeys { it.startsWith(HEADER) }.mapKeys { it.key.removePrefix(HEADER) }
        rules = meta.filterKeys { it.startsWith(RULE) }.mapKeys { it.key.removePrefix(RULE) }
        if (codes.size != texts.size) throw DataFormatException("${codes.size} codes for ${texts.size} texts")
    }

    val size: Int get() = codes.size

    fun code(index: Int): String = codes[index]
    fun text(index: Int): String = texts[index]

    /** Without making a string. */
    fun textLength(index: Int): Int = texts.length(index)

    fun textChar(index: Int, at: Int): Char = texts.char(index, at)

    fun codeLength(index: Int): Int = codes.length(index)

    fun codeKey(index: Int, at: Int): Char = codes.char(index, at)

    fun prefixRange(prefix: String): IntRange {
        val from = lowerBound(prefix)
        var lo = from
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (codes.startsWith(mid, prefix)) lo = mid + 1 else hi = mid
        }
        return from until lo
    }

    fun exactRange(code: String): IntRange {
        val range = prefixRange(code)
        var end = range.first
        while (end <= range.last && codes.length(end) == code.length) end++
        return range.first until end
    }

    private fun lowerBound(key: String): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (codes.compare(mid, key) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    class Builder {
        private val header = LinkedHashMap<String, String>()
        private val rules = LinkedHashMap<String, String>()
        private val entries = ArrayList<Pair<String, String>>()

        fun header(key: String, value: String) = apply { header[key] = value }
        fun rule(key: String, value: String) = apply { rules[key] = value }
        fun entry(code: String, text: String) = apply {
            require(code.isNotEmpty() && text.isNotEmpty()) { "empty code or text: \"$code\" \"$text\"" }
            entries += code to text
        }

        val entryList: List<Pair<String, String>> get() = entries

        fun arrange(arrange: (List<Pair<String, String>>) -> List<Pair<String, String>>) = apply {
            val next = arrange(entries.toList())
            entries.clear()
            next.forEach { (code, text) -> entry(code, text) }
        }

        fun build(): DataFile.Writer {
            val sorted = entries.sortedBy { it.first }
            return DataFile.Writer(DataFile.KIND_TABLE, VERSION)
                .addMeta(META, header.mapKeys { HEADER + it.key } + rules.mapKeys { RULE + it.key })
                .also { StringTable.write(it, CODE_OFFSETS, CODE_CHARS, sorted.map { e -> e.first }) }
                .also { StringTable.write(it, TEXT_OFFSETS, TEXT_CHARS, sorted.map { e -> e.second }) }
        }
    }

    companion object {
        /** Bump on any layout change. */
        const val VERSION = 1

        private const val META = 1
        private const val CODE_OFFSETS = 2
        private const val CODE_CHARS = 3
        private const val TEXT_OFFSETS = 4
        private const val TEXT_CHARS = 5
        private const val HEADER = "header."
        private const val RULE = "rule."

        /** @throws DataFormatException if [buffer] is not a code table this build can read */
        fun load(buffer: ByteBuffer, verify: Boolean = true): CodeTable =
            CodeTable(DataFile.open(buffer, DataFile.KIND_TABLE, VERSION, verify))
    }
}
