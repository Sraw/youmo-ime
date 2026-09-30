/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.libime

import org.fcitx.fcitx5.android.engine.data.DataFormatException
import org.fcitx.fcitx5.android.engine.data.escapeValue

/**
 * libime's binary files read without libime, as the text its own tools write of them: what the
 * user learned or imported under libime, and dictionaries made for it (fcitx5-pinyin-zhwiki's).
 *
 * @throws DataFormatException from each reader, for a file that is not what it should be
 */
object LibimeFiles {

    private const val PINYIN_MAGIC = 0x000fc613
    private const val HISTORY_MAGIC = 0x000fc315
    private const val TABLE_MAGIC = 0x000fcabe

    // what separates a pinyin key's reading from its text, a history word from its code, and a
    // table key's code from its text
    private const val PINYIN_SEPARATOR = '!'.code.toByte()
    private const val HISTORY_SEPARATOR = '\u0002'
    private const val TABLE_SEPARATOR = 1.toByte()

    // HistoryBigram's pools, newest first
    private val POOL_SIZES = intArrayOf(128, 8192, 65536)

    /** Whether [data] starts as a libime pinyin dictionary does. */
    fun isPinyinDictionary(data: ByteArray): Boolean = magic(data) == PINYIN_MAGIC

    /**
     * A pinyin dictionary (`.dict`, libime's `PinyinDictionary` saved), a line a word as its
     * `saveText` writes them: `text pin'yin cost`. The cost is written so that it reads back as
     * the same float, not always as libime prints it.
     */
    fun pinyinDictionary(data: ByteArray): List<String> {
        val trie = LibimeTrie.read(body(data, PINYIN_MAGIC, "pinyin dictionary", compressedFrom = 2))
        val out = ArrayList<String>()
        trie.forEach { key, length, value ->
            val sep = (0 until length).firstOrNull { key[it] == PINYIN_SEPARATOR } ?: return@forEach
            val reading = spell(key, 0, sep) ?: return@forEach
            val cost = Float.fromBits(value)
            if (cost.isNaN() || cost.isInfinite()) return@forEach
            val text = String(key, sep + 1, length - sep - 1, Charsets.UTF_8)
            out += "${escapeValue(text)} $reading $cost"
        }
        return out
    }

    /**
     * libime's pinyin history (`user.history`), as `HistoryBigram::dump` writes it: a sentence a
     * line, newest first, words apart by spaces, each followed by a tab and its code when any
     * word of the sentence has one. A code is libime's encoding of the reading: see [spell].
     */
    fun history(data: ByteArray): List<String> {
        if (magic(data) != HISTORY_MAGIC) throw DataFormatException("not a libime history")
        val version = BigEndianInput(data, 4).u32()
        val (input, pools) = when (version) {
            1 -> BigEndianInput(data, 8) to 2
            2 -> BigEndianInput(data, 8) to 3
            3, 4 -> BigEndianInput(Zstd.decompress(data, 8)) to 3
            else -> throw DataFormatException("libime history version $version")
        }
        val out = ArrayList<String>()
        for (pool in 0 until pools) {
            val sentences = ArrayList<List<Pair<String, String>>>()
            repeat(count(input, 4)) {
                val words = List(count(input, 4)) {
                    val word = input.string()
                    val sep = word.indexOf(HISTORY_SEPARATOR)
                    if (sep < 0) word to "" else word.substring(0, sep) to word.substring(sep + 1)
                }
                // as libime loads them: an empty sentence, or one with a NUL in it, is dropped
                if (words.isNotEmpty() && words.none { '\u0000' in it.first }) sentences += words
            }
            // oldest first in the file; a pool keeps its newest
            for (sentence in sentences.asReversed().take(POOL_SIZES[pool])) out += dumpLine(sentence)
        }
        return out
    }

    private fun dumpLine(sentence: List<Pair<String, String>>): String {
        val coded = sentence.any { it.second.isNotEmpty() }
        return sentence.joinToString(" ") { (word, code) ->
            if (coded) "${escapeValue(word)}\t${escapeValue(code)}" else escapeValue(word)
        }
    }

    /** Whether [data] starts as a libime table does (its `.main.dict`, not a user's `.user.dict`). */
    fun isTable(data: ByteArray): Boolean = magic(data) == TABLE_MAGIC

    /**
     * A code table (libime's `TableBasedDictionary` saved), as its `saveText` writes it: the
     * header in libime's English, its rules, then its entries in the table's own order.
     */
    fun table(data: ByteArray): String {
        val input = body(data, TABLE_MAGIC, "table", compressedFrom = 2)
        val pinyinKey = input.u32()
        val promptKey = input.u32()
        val phraseKey = input.u32()
        val codeLength = input.u32()
        val keys = List(count(input, 4)) { input.u32() }
        val ignored = List(count(input, 4)) { input.u32() }
        val rules = List(count(input, 9)) { rule(input) }
        val phrases = LibimeTrie.read(input)
        LibimeTrie.read(input) // each character's code, to look up
        val construct = if (rules.isNotEmpty()) LibimeTrie.read(input).also { LibimeTrie.read(input) } else null
        val prompts = if (promptKey != 0) LibimeTrie.read(input) else null

        val out = StringBuilder()
        out.append("KeyCode=").append(codePoints(keys)).append('\n')
        out.append("Length=").append(codeLength).append('\n')
        if (ignored.isNotEmpty()) out.append("InvalidChar=").append(codePoints(ignored)).append('\n')
        if (pinyinKey != 0) out.append("Pinyin=").append(codePoints(listOf(pinyinKey))).append('\n')
        if (promptKey != 0) out.append("Prompt=").append(codePoints(listOf(promptKey))).append('\n')
        if (phraseKey != 0) out.append("ConstructPhrase=").append(codePoints(listOf(phraseKey))).append('\n')
        if (rules.isNotEmpty()) {
            out.append("[Rule]\n")
            rules.forEach { out.append(it).append('\n') }
        }
        out.append("[Data]\n")
        // prompts and 构词 codes are keyed text first
        if (prompts != null) textFirst(prompts, codePoints(listOf(promptKey)), out)
        if (construct != null && phraseKey != 0) textFirst(construct, codePoints(listOf(phraseKey)), out)
        val entries = ArrayList<Triple<String, String, Int>>()
        phrases.forEach { key, length, value ->
            val sep = (0 until length).firstOrNull { key[it] == TABLE_SEPARATOR } ?: return@forEach
            entries += Triple(String(key, 0, sep, Charsets.UTF_8), String(key, sep + 1, length - sep - 1, Charsets.UTF_8), value)
        }
        // the value is the order they were added in, compared unsigned as libime does
        entries.sortWith { a, b -> Integer.compare(a.third xor Int.MIN_VALUE, b.third xor Int.MIN_VALUE) }
        for ((code, text) in entries) out.append(code).append(' ').append(escapeValue(text)).append('\n')
        return out.toString()
    }

    private fun textFirst(trie: LibimeTrie, marker: String, out: StringBuilder) {
        trie.forEach { key, length, _ ->
            val sep = (0 until length).firstOrNull { key[it] == TABLE_SEPARATOR } ?: return@forEach
            out.append(marker).append(String(key, sep + 1, length - sep - 1, Charsets.UTF_8)).append(' ')
                .append(escapeValue(String(key, 0, sep, Charsets.UTF_8))).append('\n')
        }
    }

    /** A rule as libime writes one: `e2=p11+p12+p21+p22`. */
    private fun rule(input: BigEndianInput): String {
        val longer = when (input.u32()) {
            0 -> true
            1 -> false
            else -> throw DataFormatException("libime table rule flag")
        }
        val length = input.u8()
        val parts = List(count(input, 6)) {
            val fromBack = when (input.u32()) {
                0 -> false
                1 -> true
                else -> throw DataFormatException("libime table rule entry flag")
            }
            val character = input.u8()
            val raw = input.u8()
            // an index from the end is kept as 0x80 on: -1 for the last, written z
            val index = if (raw < 0x80) '0' + raw else 'z' - (raw - 0x80)
            "${if (fromBack) 'n' else 'p'}${'0' + character}$index"
        }
        return "${if (longer) 'a' else 'e'}$length=${parts.joinToString("+")}"
    }

    /**
     * A reading libime encoded (two bytes a syllable, as its history keeps codes), spelled as
     * its `decodeFullPinyin` does (`ni'hao`, ü as v); null for an odd length. A byte that is no
     * initial or final spells nothing, as in libime.
     */
    fun spell(code: String): String? {
        if (code.any { it.code > 0xFF }) return null
        return spell(ByteArray(code.length) { code[it].code.toByte() }, 0, code.length)
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

    /** A count ahead of what it counts, checked against what is left: each takes [bytesEach] at least. */
    private fun count(input: BigEndianInput, bytesEach: Int): Int {
        val n = input.u32()
        if (n < 0 || n > input.remaining / bytesEach) throw DataFormatException("libime count $n past the end")
        return n
    }

    private fun codePoints(list: List<Int>): String = buildString {
        list.forEach {
            if (!Character.isValidCodePoint(it)) throw DataFormatException("libime character $it")
            appendCodePoint(it)
        }
    }
}
