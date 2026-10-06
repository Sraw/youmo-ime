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

    /** What [addWords] did to a table. */
    class Added(val fresh: Int, val others: Int, val dropped: Int)

    /**
     * Adds [words] to the table [reader] read (its phrases coded), each coded by its 组词规则 --
     * so a 五笔 user types 内卷 as the rules spell it -- and arranges each code of the longest
     * length by use, [score] a text's log10 probability: the commonest first, space committing it,
     * and a code left with one candidate committing that by itself (唯一自动上屏). So:
     * - a word less than a tenth as common as the commonest of the code's others (characters and
     *   words not [fresh]), or never seen, goes; the table's own too, typed character by character
     *   still, and kept where the user picked it before (TableUser). Not a [fresh] word (the
     *   new-word pack's): new, it is more used than any count of the past says, taken as
     *   [FRESH_BOOST] more;
     * - a character always stays, typed by no other code perhaps; so does an entry not all Han
     *   (—— ……, a saying with a comma), which no count is of;
     * - codes shorter than the longest, the table's 简码, stay as they were.
     * A word goes in only where each shorter code it starts with led somewhere already (else a
     * key that led nowhere and committed, 顶屏, would lead on), to its own code or one it comes
     * after (a shorter code's first candidate changes only where it was of this code), and not
     * under a pinyin entry's spelling (五笔拼音). Not a word the table has; a table without rules
     * gets none and loses none.
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
        // of the longest length: no code leads on from it
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
            // the longest codes, each with the words coded to it; the rest as they were
            val (full, kept) = entries.partition { (code, _) -> code.length == dictionary.maxLength && (marker == null || code[0] != marker) }
            val byCode = LinkedHashMap<String, MutableList<String>>()
            for ((code, text) in full) byCode.getOrPut(code) { ArrayList() } += text
            for ((code, list) in added) byCode.getOrPut(code) { ArrayList() } += list
            kept + byCode.flatMap { (code, list) ->
                val (stay, gone) = arrange(list, score) { isWord(it) && fresh(it) }
                dropped += gone.count { it !in new }
                stay.map { code to it }
            }
        }
        val left = reader.builder.entryList.mapTo(HashSet()) { it.second }
        val freshAdded = new.count { it in left && fresh(it) }
        return Added(freshAdded, new.count { it in left } - freshAdded, dropped)
    }

    /**
     * [texts] of one code kept, the commonest first (as listed where as common, never seen last),
     * and those that go: see [addWords].
     */
    internal fun arrange(texts: List<String>, score: (String) -> Float, fresh: (String) -> Boolean): Pair<List<String>, List<String>> {
        val others = texts.filterNot(fresh)
        val top = others.maxOfOrNull(score) ?: Float.NEGATIVE_INFINITY
        val common = others.filter { !isWord(it) || (score(it) > Float.NEGATIVE_INFINITY && score(it) >= top - KEEP_WITHIN) }
        // never all of them for a few never seen: the first stays
        val keep = (texts.filter(fresh) + common.ifEmpty { others.take(1) }).toSet()
        val ranked = texts.filter { it in keep }.sortedByDescending { if (fresh(it)) score(it) + FRESH_BOOST else score(it) }
        return ranked to texts.filter { it !in keep }
    }

    /**
     * Words of Han characters, two or more, the model scores; not a character (one outside the
     * BMP is two chars), nor punctuation or a saying with a comma in it (五笔's —— and ……, 吃一堑，
     * 长一智), which no model count says anything of.
     */
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

    /** log10: a fresh word counts ten times its use. */
    const val FRESH_BOOST = 1f

    /** log10: a word a tenth as common as its code's commonest goes. */
    const val KEEP_WITHIN = 1f

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
