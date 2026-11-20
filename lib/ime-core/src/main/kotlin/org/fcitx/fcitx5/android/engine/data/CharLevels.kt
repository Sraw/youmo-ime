/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.data

import java.nio.ByteBuffer

/** A Han character's 生僻字屏蔽 group: listed ones from `engine/charlevels.data`, the rest by fixed ranges. Not `Character.UnicodeScript`: Android 10's Unicode is older. */
class CharLevels private constructor(private val chars: BitPacked, private val levels: BitPacked) {

    /** [L1] and [L2] are never held back. [OUT]: unlisted basic block, 扩展 A and compatibility; [EXT]: unlisted planes 2 and 3. */
    enum class Level {
        L1, L2, L3, TRAD, OUT, EXT;

        companion object {
            val GRID = listOf(L3, TRAD, OUT, EXT)

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

    /** Listed first, then by range; null for non-Han (〇, radicals, strokes, variation selectors, private use). */
    fun of(codePoint: Int): Level? {
        val i = listed(codePoint)
        if (i >= 0) return Level.entries[levels[i]]
        return rangeOf(codePoint)
    }

    val size: Int get() = chars.size

    fun codePointAt(index: Int): Int = chars[index]
    fun levelAt(index: Int): Level = Level.entries[levels[index]]

    internal object Section {
        const val CHARS = 1
        const val LEVELS = 2
    }

    companion object {
        /** Bump on any layout change. */
        const val VERSION = 1

        val OUT_RANGES = listOf(0x3400..0x4DBF, 0x4E00..0x9FFF, 0xF900..0xFAFF)

        val EXT_RANGE = 0x20000..0x3FFFF

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

/** Builds `engine/charlevels.data` from Unihan's kTGH lines ([tgh]), then OpenCC's TSCharacters.txt ([traditional]). */
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
            if (rank.put(cp, r) != null) throw SourceException(source, n + 1, "${f[0]} twice")
        }
    }

    fun traditional(lines: Sequence<String>) = apply {
        for (line in lines) {
            val f = line.split('\t')
            if (line.startsWith("#") || f.size < 2 || f[0].codePointCount(0, f[0].length) != 1) continue
            val cp = f[0].codePointAt(0)
            val simplified = f[1].split(' ').filter { it.codePointCount(0, it.length) == 1 }.map { it.codePointAt(0) }
            if (cp !in rank && simplified.any { it in rank }) traditional += cp
        }
    }

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

        const val TABLE = 8105
        const val LEVEL_1 = 3500
        const val LEVEL_2 = 6500
    }
}
