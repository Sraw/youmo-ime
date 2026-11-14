/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import java.nio.ByteBuffer

/**
 * Which group a Han character is in, for 生僻字屏蔽 (dev/design/061-rare-char-block.md §3.1): the
 * 通用规范汉字表's three levels (Unihan's kTGH, Unicode License v3), the traditional characters
 * whose simplified one is in the table (OpenCC's TSCharacters, Apache-2.0), both listed in
 * `engine/charlevels.data` (ime-dict-tool's `charlevels`, [CharLevelsBuilder]); the rest by fixed
 * ranges of code points. Never by the runtime's `Character.UnicodeScript`: Android 10's Unicode is
 * older, and the same choice would hold back other characters on another phone.
 */
class CharLevels private constructor(private val chars: BitPacked, private val levels: BitPacked) {

    /**
     * The groups in order: [L1] and [L2], the table's first two levels, are never held back; the
     * grid's are [GRID]. [OUT]: the basic block, 扩展 A and the compatibility ideographs not listed;
     * [EXT]: planes 2 and 3 (扩展 B on, their compatibility supplement too) not listed.
     */
    enum class Level {
        L1, L2, L3, TRAD, OUT, EXT;

        companion object {
            /** The groups the grid shows and a user may hold back. */
            val GRID = listOf(L3, TRAD, OUT, EXT)

            /** The group named [name] in the user's file; null for one this build does not know. */
            fun named(name: String): Level? = entries.firstOrNull { it.name == name }
        }
    }

    init {
        ensureFormat(levels.size == chars.size) { "character levels disagree with their characters" }
        levels.checkBelow("character levels", Level.OUT.ordinal)
    }

    private fun listed(codePoint: Int): Int {
        val i = chars.lowerBound(0, chars.size, codePoint)
        return if (i < chars.size && chars[i] == codePoint) i else -1
    }

    /**
     * [codePoint]'s group: listed first (a table character beyond the basic block is the table's,
     * 三级字 among 扩展 B), then by its range; null for one this is not about (〇, the radicals,
     * strokes, tags, variation selectors, private use, anything not Han).
     */
    fun of(codePoint: Int): Level? {
        val i = listed(codePoint)
        if (i >= 0) return Level.entries[levels[i]]
        return rangeOf(codePoint)
    }

    /** How many characters are listed (the table's 8,105 and the traditional ones). */
    val size: Int get() = chars.size

    /** The [index]th listed character, by code point, and its group: for a walk of them all. */
    fun codePointAt(index: Int): Int = chars[index]
    fun levelAt(index: Int): Level = Level.entries[levels[index]]

    internal object Section {
        const val CHARS = 1
        const val LEVELS = 2
    }

    companion object {
        /** Layout of the sections; bump on any change to it. */
        const val VERSION = 1

        /** The ranges [OUT][Level.OUT] covers: 扩展 A, the basic block, the compatibility ideographs. */
        val OUT_RANGES = listOf(0x3400..0x4DBF, 0x4E00..0x9FFF, 0xF900..0xFAFF)

        /** The range [EXT][Level.EXT] covers: planes 2 and 3, 扩展 B to J and the compatibility supplement. */
        val EXT_RANGE = 0x20000..0x3FFFF

        /** The group of a character not listed: [Level.OUT], [Level.EXT] or none. */
        fun rangeOf(codePoint: Int): Level? = when {
            OUT_RANGES.any { codePoint in it } -> Level.OUT
            codePoint in EXT_RANGE -> Level.EXT
            else -> null
        }

        fun load(buffer: ByteBuffer, verify: Boolean = true): CharLevels {
            val file = DataFile.open(buffer, DataFile.KIND_CHAR_LEVELS, VERSION, verify)
            return CharLevels(file.bitPacked(Section.CHARS), file.bitPacked(Section.LEVELS))
        }
    }
}

/**
 * Builds `engine/charlevels.data` ([CharLevels]): [tgh], Unihan_OtherMappings.txt's kTGH lines
 * (`U+4E00<TAB>kTGH<TAB>2013:1`), then [traditional], OpenCC's TSCharacters.txt (`<traditional><TAB><simplified> ...`).
 */
class CharLevelsBuilder {
    private val rank = HashMap<Int, Int>()
    private val traditional = HashSet<Int>()

    fun tgh(lines: Sequence<String>, source: String) = apply {
        lines.forEachIndexed { n, line ->
            if (line.startsWith("#") || line.isBlank()) return@forEachIndexed
            val f = line.split('\t')
            if (f.size < 3 || f[1] != "kTGH") return@forEachIndexed
            val cp = f[0].takeIf { it.startsWith("U+") }?.substring(2)?.toIntOrNull(HEX)
            val r = f[2].substringAfter(':', "").toIntOrNull()
            if (cp == null || r == null || !f[2].startsWith("2013:")) throw SourceException(source, n + 1, "not a kTGH line: $line")
            if (rank.put(cp, r) != null) throw SourceException(source, n + 1, "U+${f[0]} twice")
        }
    }

    /** The traditional characters of TSCharacters.txt one of whose simplified ones is in the table; call after [tgh]. */
    fun traditional(lines: Sequence<String>) = apply {
        for (line in lines) {
            val f = line.split('\t')
            if (line.startsWith("#") || f.size < 2 || f[0].codePointCount(0, f[0].length) != 1) continue
            val cp = f[0].codePointAt(0)
            val simplified = f[1].split(' ').filter { it.codePointCount(0, it.length) == 1 }.map { it.codePointAt(0) }
            if (cp !in rank && simplified.any { it in rank }) traditional += cp
        }
    }

    /** The file; a table that is not the 8,105 characters ranked 1 to 8,105 once each fails. */
    fun build(): DataFile.Writer {
        val ranks = rank.values.sorted()
        if (ranks.size != TABLE || ranks != (1..TABLE).toList()) {
            throw IllegalStateException("kTGH: ${ranks.size} characters, not the table's $TABLE ranked 1 to $TABLE once each")
        }
        val all = (rank.keys + traditional).sorted()
        return DataFile.Writer(DataFile.KIND_CHAR_LEVELS, CharLevels.VERSION)
            .add(CharLevels.Section.CHARS, BitPacked.encode(all.size) { all[it] })
            .add(CharLevels.Section.LEVELS, BitPacked.encode(all.size) { levelOf(all[it]).ordinal })
    }

    private fun levelOf(cp: Int): CharLevels.Level {
        val r = rank[cp] ?: return CharLevels.Level.TRAD
        return when {
            r <= LEVEL_1 -> CharLevels.Level.L1
            r <= LEVEL_2 -> CharLevels.Level.L2
            else -> CharLevels.Level.L3
        }
    }

    companion object {
        private const val HEX = 16

        /** The table's characters, and where its first two levels end. */
        const val TABLE = 8105
        const val LEVEL_1 = 3500
        const val LEVEL_2 = 6500
    }
}
