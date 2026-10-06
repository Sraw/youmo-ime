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
     * its 组词规则 -- so a 五笔 user types 内卷 as the rules spell it -- before the table's own
     * entries of its code, new words being what is wanted: what had the code, or the code one
     * shorter, alone no longer commits itself (唯一自动上屏), and where it was first for a shorter
     * code too, the new word is first there. What else the table's shorter codes do stays as it was:
     * - each shorter code it starts with led somewhere already: else a key that led nowhere and
     *   committed (顶屏) would lead on;
     * - and to its own code or one it comes after: else the first candidate of the shorter code,
     *   which space commits, would be another;
     * - nor is it a pinyin entry's spelling (五笔拼音), which a code of its own would be put before.
     * Not a word the table has; a table without rules gets none.
     *
     * @return how many were added
     */
    fun addWords(reader: CodeTableReader, words: Iterable<String>): Int {
        val table = CodeTable.load(ByteBuffer.wrap(reader.builder.build().toByteArray()), verify = false)
        if (table.rules.isEmpty()) return 0
        val dictionary = TableDictionary(table)
        val texts = HashSet<String>(table.size)
        for (i in 0 until table.size) texts += table.text(i)
        var added = 0
        val marker = dictionary.pinyinMarker
        // ranked with no picks, an entry is after one of a code no longer and earlier in the table
        fun follows(code: String) = (1 until code.length).all { n ->
            val shorter = table.prefixRange(code.substring(0, n))
            !shorter.isEmpty() && table.code(shorter.first) <= code
        }
        // of the longest length: no code leads on from it
        fun fits(code: String) = code.length == dictionary.maxLength &&
            (marker == null || table.prefixRange("$marker$code").isEmpty()) && follows(code)
        for (word in words) {
            val code = word.takeIf { it.length >= 2 && it !in texts }?.let(dictionary::encode)?.takeIf(::fits) ?: continue
            texts += word
            reader.builder.lead(code, word)
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
