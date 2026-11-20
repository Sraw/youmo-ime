/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user.imports

import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.escapeValue

/** libime's pinyin `.dict` files (fcitx5-pinyin-zhwiki's) read without libime, as the text its own tools write. */
object LibimeFiles {

    private const val PINYIN_MAGIC = 0x000fc613

    private const val PINYIN_SEPARATOR = '!'.code.toByte()

    /**
     * A `.dict` as `saveText` writes it: `text pin'yin cost` a line, the cost written to read back as the same float.
     *
     * @throws DataFormatException for a malformed file
     */
    fun pinyinDictionary(data: ByteArray): List<String> = ArrayList<String>().also { lines -> forEachPinyinLine(data) { lines += it } }

    /**
     * [pinyinDictionary]'s lines streamed to [out], so no list of a whole dictionary's strings is held (zhwiki's is
     * 64.5 MiB and the import must fit a 256 MB heap).
     *
     * @throws DataFormatException for a malformed file
     */
    fun writePinyinDictionary(data: ByteArray, out: Appendable) = forEachPinyinLine(data) { out.append(it).append('\n') }

    private fun forEachPinyinLine(data: ByteArray, line: (String) -> Unit) {
        val trie = LibimeTrie.read(body(data, PINYIN_MAGIC, "pinyin dictionary", compressedFrom = 2))
        trie.forEach { key, length, value ->
            var sep = 0
            while (sep < length && key[sep] != PINYIN_SEPARATOR) sep++
            if (sep == length) return@forEach
            val reading = spell(key, 0, sep) ?: return@forEach
            val cost = Float.fromBits(value)
            if (cost.isNaN() || cost.isInfinite()) return@forEach
            val text = String(key, sep + 1, length - sep - 1, Charsets.UTF_8)
            line("${escapeValue(text)} $reading $cost")
        }
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
