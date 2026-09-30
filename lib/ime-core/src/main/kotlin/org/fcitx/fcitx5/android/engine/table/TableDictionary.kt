/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.DataFormatException

/**
 * A [CodeTable] read the way its header says: which keys are codes (键码), how long a code gets
 * (码长), how phrases are coded (组词规则), which entries only say how a character builds
 * phrases (the 构词 marker), and which codes are not to build phrases from (规避字符).
 *
 * Entries are found by index and decoded only when shown: a key's range may hold thousands.
 */
class TableDictionary(private val table: CodeTable) {

    val keys: Set<Char> = table.header["键码"]?.takeIf { it.isNotEmpty() }?.toSet()
        ?: throw DataFormatException("no 键码")
    val maxLength: Int = table.header["码长"]?.toIntOrNull()?.takeIf { it > 0 }
        ?: throw DataFormatException("码长 \"${table.header["码长"]}\"")
    val rules = PhraseRules(table.rules)
    private val construct = table.header["构词"]?.singleOrNull()
    private val ignored = table.header["规避字符"].orEmpty().toSet()

    /** Each character's longest code, and its 构词 code if the table gives one. */
    private class Reverse(val codes: Map<String, String>, val construct: Map<String, String>)

    // Built on first use, as only phrases and pinyin lookups need it: a pass over the whole table,
    // keeping characters alone (a phrase's code comes from the rules), as libime does.
    private val reverse by lazy(LazyThreadSafetyMode.NONE) {
        val codes = HashMap<String, String>()
        val constructCodes = HashMap<String, String>()
        for (i in 0 until table.size) {
            val first = table.codeKey(i, 0)
            val (map, from) = when {
                first == construct -> constructCodes to 1
                first in keys && first !in ignored -> codes to 0
                else -> continue
            }
            val text = table.text(i)
            val known = map[text]
            val longer = known == null || known.length < table.codeLength(i) - from
            if (longer && text.codePointCount(0, text.length) == 1) map[text] = table.code(i).substring(from)
        }
        Reverse(codes, constructCodes)
    }

    fun code(index: Int): String = table.code(index)

    fun text(index: Int): String = table.text(index)

    fun codeLength(index: Int): Int = table.codeLength(index)

    /**
     * The entries [pattern] leads to, in table order: those whose code starts with it, or with
     * [wildcard] in it (standing for any one key), those whose code it spells whole, as libime
     * matches. A wildcard scan stops at [MAX_MATCHES].
     */
    fun match(pattern: String, wildcard: Char?): IntArray {
        val cut = if (wildcard == null) -1 else pattern.indexOf(wildcard)
        if (cut < 0) {
            // all of them: cut in code order, a short code past the cut would never be ranked
            // (二笔's i leads to 19k entries, its two-key codes among the last)
            val range = table.prefixRange(pattern)
            return IntArray((range.last - range.first + 1).coerceAtLeast(0)) { range.first + it }
        }
        val out = ArrayList<Int>()
        for (i in table.prefixRange(pattern.substring(0, cut))) {
            if (out.size == MAX_MATCHES) break
            if (spells(i, pattern, wildcard!!, whole = true)) out += i
        }
        return out.toIntArray()
    }

    /**
     * Whether some code starts with [pattern], [wildcard] standing for any key: whether typing
     * on can lead anywhere (a wildcard code, unlike [match], by its start too, as in libime).
     */
    fun hasMatch(pattern: String, wildcard: Char?): Boolean {
        val cut = if (wildcard == null) -1 else pattern.indexOf(wildcard)
        if (cut < 0) return !table.prefixRange(pattern).isEmpty()
        return table.prefixRange(pattern.substring(0, cut)).any { spells(it, pattern, wildcard!!, whole = false) }
    }

    private fun spells(i: Int, pattern: String, wildcard: Char, whole: Boolean): Boolean {
        val length = table.codeLength(i)
        if (if (whole) length != pattern.length else length < pattern.length) return false
        for (at in pattern.indices) {
            val key = table.codeKey(i, at)
            val p = pattern[at]
            if (if (p == wildcard) key !in keys else key != p) return false
        }
        return true
    }

    fun contains(code: String, text: String): Boolean = table.exactRange(code).any { table.text(it) == text }

    /** [text]'s full code: a character's own, a phrase's put together by the rules; null if neither. */
    fun codeOf(text: String): String? = reverse.codes[text] ?: encode(text)

    /** The code a character builds phrases with: its 构词 code where the table gives those. */
    private fun charCode(char: String): String? = if (construct != null) reverse.construct[char] else reverse.codes[char]

    /** The code [text] would have as a new phrase, by the rules alone. */
    fun encode(text: String): String? = rules.encode(text, ::charCode)

    companion object {
        // a wildcard first matches the whole table; nobody pages through more than this
        const val MAX_MATCHES = 10_000
    }
}
