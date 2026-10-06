/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import java.nio.ByteBuffer

/**
 * A compiled code table (五笔, 仓颉, ...): entries sorted by code, entries sharing a code kept in
 * the order the source listed them, which is the order users expect to see them in. Looking up
 * a partial code is a binary search for the range of codes it prefixes.
 *
 * [header] and [rules] are the table's own settings (键码, 码长, 组词规则 ...), kept verbatim for
 * the engine to interpret.
 */
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

    /** The length of [index]'s code, and its key [at]: without making a string of it. */
    fun codeLength(index: Int): Int = codes.length(index)

    fun codeKey(index: Int, at: Int): Char = codes.char(index, at)

    /** The entries whose code starts with [prefix], as an index range (empty if none). */
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

    /** Entries whose code is exactly [code]. */
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
        private val leading = ArrayList<Pair<String, String>>()

        fun header(key: String, value: String) = apply { header[key] = value }
        fun rule(key: String, value: String) = apply { rules[key] = value }
        fun entry(code: String, text: String) = apply {
            require(code.isNotEmpty() && text.isNotEmpty()) { "empty code or text: \"$code\" \"$text\"" }
            entries += code to text
        }

        /** [entry], but before every [entry] of [code]: after those led before it. */
        fun lead(code: String, text: String) = apply {
            require(code.isNotEmpty() && text.isNotEmpty()) { "empty code or text: \"$code\" \"$text\"" }
            leading += code to text
        }

        fun build(): DataFile.Writer {
            // stable: entries sharing a code keep their source order
            val sorted = (leading + entries).sortedBy { it.first }
            return DataFile.Writer(DataFile.KIND_TABLE, VERSION)
                .addMeta(META, header.mapKeys { HEADER + it.key } + rules.mapKeys { RULE + it.key })
                .also { StringTable.write(it, CODE_OFFSETS, CODE_CHARS, sorted.map { e -> e.first }) }
                .also { StringTable.write(it, TEXT_OFFSETS, TEXT_CHARS, sorted.map { e -> e.second }) }
        }
    }

    companion object {
        /** Layout of the sections below; bump on any change to it. */
        const val VERSION = 1

        private const val META = 1
        private const val CODE_OFFSETS = 2
        private const val CODE_CHARS = 3
        private const val TEXT_OFFSETS = 4
        private const val TEXT_CHARS = 5
        private const val HEADER = "header."
        private const val RULE = "rule."

        /**
         * [verify]: check every section's checksum first (see [DataFile.open]).
         * @throws DataFormatException if [buffer] is not a code table this build can read
         */
        fun load(buffer: ByteBuffer, verify: Boolean = true): CodeTable =
            CodeTable(DataFile.open(buffer, DataFile.KIND_TABLE, VERSION, verify))
    }
}
