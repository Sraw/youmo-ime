/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.CodeTableReader
import java.nio.ByteBuffer

object TableText {

    /**
     * Codes the phrases [reader] found listed without a code by the 组词规则, as libime does; those the rules cannot code are left out.
     *
     * @return how many were left out
     */
    fun codePhrases(reader: CodeTableReader): Int {
        if (reader.phrases.isEmpty()) return 0
        // the table so far, only to look the characters' codes up
        return codePhrases(reader, TableDictionary(CodeTable.load(ByteBuffer.wrap(reader.builder.build().toByteArray()), verify = false)))
    }

    private fun codePhrases(reader: CodeTableReader, table: TableDictionary): Int {
        var uncoded = 0
        for (phrase in reader.phrases) {
            val code = table.encode(phrase)
            if (code == null) uncoded++ else reader.builder.entry(code, phrase)
        }
        return uncoded
    }

    class Added(val fresh: Int, val others: Int, val dropped: Int)

    /**
     * Adds [words] to the table, each coded by its 组词规则 (so 五笔 types 内卷 as the rules spell it), and orders each
     * longest-length code by use ([score], log10): the commonest first, a lone candidate committing itself. The table's
     * own entries only move; an added word goes if under a tenth as common as the code's commonest other, unless
     * [fresh] (the new-word pack, boosted by [FRESH_BOOST]). 简码 stay as they were. A word goes in only where each
     * shorter code it starts with led somewhere already (else a 顶屏 key would lead on), and not under a pinyin entry's
     * spelling. A table without rules gets none.
     */
    fun addWords(reader: CodeTableReader, words: Iterable<String>, score: (String) -> Float, fresh: (String) -> Boolean): Added {
        val table = CodeTable.load(ByteBuffer.wrap(reader.builder.build().toByteArray()), verify = false)
        if (table.rules.isEmpty()) return Added(0, 0, 0)
        val dictionary = TableDictionary(table)
        val texts = HashSet<String>(table.size)
        for (i in 0 until table.size) texts += table.text(i)
        val marker = dictionary.pinyinMarker
        // ranked with no picks, an entry is after one of a code no longer and earlier in the table
        fun follows(code: String) = (1 until code.length).all { n ->
            val shorter = table.prefixRange(code.substring(0, n))
            !shorter.isEmpty() && table.code(shorter.first) <= code
        }
        fun fits(code: String) = code.length == dictionary.maxLength &&
            (marker == null || table.prefixRange("$marker$code").isEmpty()) && follows(code)
        val added = HashMap<String, MutableList<String>>()
        val new = HashSet<String>()
        for (word in words) {
            val code = word.takeIf { it.length >= 2 && it !in texts }?.let(dictionary::encode)?.takeIf(::fits) ?: continue
            texts += word
            new += word
            added.getOrPut(code) { ArrayList() } += word
        }
        var dropped = 0
        reader.builder.arrange { entries ->
            val (full, kept) = entries.partition { (code, _) -> code.length == dictionary.maxLength && (marker == null || code[0] != marker) }
            val byCode = LinkedHashMap<String, MutableList<String>>()
            for ((code, text) in full) byCode.getOrPut(code) { ArrayList() } += text
            for ((code, list) in added) byCode.getOrPut(code) { ArrayList() } += list
            kept + byCode.flatMap { (code, list) ->
                val (stay, gone) = arrange(list, score, { isWord(it) && fresh(it) }) { it in new }
                dropped += gone.size
                stay.map { code to it }
            }
        }
        val left = reader.builder.entryList.mapTo(HashSet()) { it.second }
        val freshAdded = new.count { it in left && fresh(it) }
        return Added(freshAdded, new.count { it in left } - freshAdded, dropped)
    }

    /** [texts] of one code kept, commonest first (unseen last), and those of [added] that go: see [addWords]. */
    internal fun arrange(texts: List<String>, score: (String) -> Float, fresh: (String) -> Boolean, added: (String) -> Boolean): Pair<List<String>, List<String>> {
        val others = texts.filterNot(fresh)
        val top = others.maxOfOrNull(score) ?: Float.NEGATIVE_INFINITY
        val common = others.filter { !added(it) || !isWord(it) || (score(it) > Float.NEGATIVE_INFINITY && score(it) >= top - KEEP_WITHIN) }
        // never all of them for a few never seen: the first stays
        val keep = (texts.filter(fresh) + common.ifEmpty { others.take(1) }).toSet()
        val ranked = texts.filter { it in keep }.sortedByDescending { if (fresh(it)) score(it) + FRESH_BOOST else score(it) }
        return ranked to texts.filter { it !in keep }
    }

    /** Han words of two or more characters the model scores; not a character (outside the BMP it is two chars), nor punctuation or a saying with a comma. */
    private fun isWord(text: String): Boolean {
        if (text.codePointCount(0, text.length) < 2) return false
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            if (!Character.isIdeographic(c)) return false
            i += Character.charCount(c)
        }
        return true
    }

    const val FRESH_BOOST = 1f

    const val KEEP_WITHIN = 1f
}
