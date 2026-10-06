/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.CodeTableReader
import org.fcitx.fcitx5.android.engine.data.SourceException
import java.io.BufferedReader
import java.nio.ByteBuffer

/** A code table's text made into what [CodeTable.load] reads. */
object TableText {

    /**
     * Codes the phrases [reader] found listed without a code by the table's 组词规则, as libime
     * does, adding them to its entries. Those the rules cannot code (a character the table has no
     * code for, a length no rule covers) are left out.
     *
     * @return how many were left out
     */
    fun codePhrases(reader: CodeTableReader): Int {
        if (reader.phrases.isEmpty()) return 0
        // the table so far, only to look the characters' codes up
        val table = TableDictionary(CodeTable.load(ByteBuffer.wrap(reader.builder.build().toByteArray()), verify = false))
        var uncoded = 0
        for (phrase in reader.phrases) {
            val code = table.encode(phrase)
            if (code == null) uncoded++ else reader.builder.entry(code, phrase)
        }
        return uncoded
    }

    /**
     * Adds [words], the best first, to the table [reader] read (its phrases coded), each coded by
     * its 组词规则 -- so a 五笔 user types 内卷 as the rules spell it -- where that leaves what
     * the table's codes do as it was:
     * - not a word it has, nor one under a code it uses or leads on to: a second entry under a
     *   code would stop the first committing itself (五笔's 唯一自动上屏);
     * - each shorter code it starts with led somewhere already, to more than one text, and to
     *   something it comes after: else a key that led nowhere and committed (顶屏) would lead on,
     *   a lone candidate would no longer commit itself, or the first candidate of the shorter
     *   code, which space commits, would be another;
     * - nor a pinyin entry's spelling (五笔拼音), which a code of its own would be put before.
     * A table without rules gets none.
     *
     * @return how many were added
     */
    fun addWords(reader: CodeTableReader, words: Iterable<String>): Int {
        val table = CodeTable.load(ByteBuffer.wrap(reader.builder.build().toByteArray()), verify = false)
        if (table.rules.isEmpty()) return 0
        val dictionary = TableDictionary(table)
        val texts = HashSet<String>(table.size)
        for (i in 0 until table.size) texts += table.text(i)
        // codes added, all of the longest length: none leads on to another
        val taken = HashSet<String>()
        var added = 0
        val marker = dictionary.pinyinMarker
        // ranked with no picks, an entry is after one of a code no longer and earlier in the table;
        // and a shorter code with one text only commits it by itself, which a second would stop
        fun follows(code: String) = (1 until code.length).all { n ->
            val shorter = table.prefixRange(code.substring(0, n))
            !shorter.isEmpty() && table.code(shorter.first) < code &&
                shorter.any { table.text(it) != table.text(shorter.first) }
        }
        fun free(code: String) = code.length == dictionary.maxLength && code !in taken && table.prefixRange(code).isEmpty() &&
            (marker == null || table.prefixRange("$marker$code").isEmpty()) && follows(code)
        for (word in words) {
            val code = word.takeIf { it.length >= 2 && it !in texts }?.let(dictionary::encode)?.takeIf(::free) ?: continue
            taken += code
            texts += word
            reader.builder.entry(code, word)
            added++
        }
        return added
    }

    /**
     * Reads [text] through as the engine will when its input method is first used, so that a
     * table the user imports that cannot be typed with fails to import rather than to type.
     *
     * @throws SourceException where it cannot be read, or if it lists nothing to type
     */
    fun check(text: BufferedReader, source: String) {
        val reader = CodeTableReader()
        reader.read(text, source)
        if (reader.entries == 0 && reader.phrases.isEmpty()) throw SourceException(source, 0, "nothing to type")
    }
}
