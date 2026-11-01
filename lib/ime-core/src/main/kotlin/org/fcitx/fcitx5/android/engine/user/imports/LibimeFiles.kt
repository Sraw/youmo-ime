/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user.imports

import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.escapeValue

/**
 * libime's pinyin dictionaries (`.dict`) read without libime, as the text its own tools write of
 * them: those made for it (fcitx5-pinyin-zhwiki's), which the user imports.
 */
object LibimeFiles {

    private const val PINYIN_MAGIC = 0x000fc613

    // what separates a pinyin key's reading from its text
    private const val PINYIN_SEPARATOR = '!'.code.toByte()

    /** Whether [data] starts as a libime pinyin dictionary does. */
    fun isPinyinDictionary(data: ByteArray): Boolean = magic(data) == PINYIN_MAGIC

    /**
     * A pinyin dictionary (`.dict`, libime's `PinyinDictionary` saved), a line a word as its
     * `saveText` writes them: `text pin'yin cost`. The cost is written so that it reads back as
     * the same float, not always as libime prints it.
     *
     * @throws DataFormatException for a file that is not what it should be
     */
    fun pinyinDictionary(data: ByteArray): List<String> {
        val trie = LibimeTrie.read(body(data, PINYIN_MAGIC, "pinyin dictionary", compressedFrom = 2))
        val out = ArrayList<String>()
        trie.forEach { key, length, value ->
            var sep = 0
            while (sep < length && key[sep] != PINYIN_SEPARATOR) sep++
            if (sep == length) return@forEach
            val reading = spell(key, 0, sep) ?: return@forEach
            val cost = Float.fromBits(value)
            if (cost.isNaN() || cost.isInfinite()) return@forEach
            val text = String(key, sep + 1, length - sep - 1, Charsets.UTF_8)
            out += "${escapeValue(text)} $reading $cost"
        }
        return out
    }

    private fun spell(bytes: ByteArray, from: Int, to: Int): String? {
        if ((to - from) % 2 != 0) return null
        val out = StringBuilder()
        for (i in from until to step 2) {
            if (i != from) out.append('\'')
            out.append(INITIALS.getOrElse(bytes[i] - 'A'.code.toByte()) { "" })
            out.append(FINALS.getOrElse(bytes[i + 1] - 'A'.code.toByte()) { "" })
        }
        return out.toString()
    }

    private val INITIALS = listOf(
        "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "zh", "ch", "sh", "r", "z", "c", "s", "y", "w", "",
    )
    private val FINALS = listOf(
        "a", "ai", "an", "ang", "ao", "e", "ei", "en", "eng", "er", "o", "ong", "ou", "i", "ia", "ie", "iao", "iu", "ian", "in",
        "iang", "ing", "iong", "u", "ua", "uo", "uai", "ui", "uan", "un", "uang", "v", "ve", "ue", "ng", "",
    ) + ('A'..'Z').map { it.toString() }

    private fun magic(data: ByteArray): Int = if (data.size < 8) -1 else BigEndianInput(data).u32()

    /** What follows [magic] and the version, decompressed from version [compressedFrom] on. */
    private fun body(data: ByteArray, magic: Int, what: String, compressedFrom: Int): BigEndianInput {
        if (magic(data) != magic) throw DataFormatException("not a libime $what")
        val version = BigEndianInput(data, 4).u32()
        return when (version) {
            in 1 until compressedFrom -> BigEndianInput(data, 8)
            compressedFrom -> BigEndianInput(Zstd.decompress(data, 8))
            else -> throw DataFormatException("libime $what version $version")
        }
    }
}
