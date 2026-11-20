/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.DataFormatException

/** A [CodeTable] read as its header says (键码, 码长, 组词规则, the 构词 marker, 规避字符); entries decoded only when shown. */
class TableDictionary(private val table: CodeTable) {

    val keys: Set<Char> = table.header["键码"]?.takeIf { it.isNotEmpty() }?.toSet()
        ?: throw DataFormatException("no 键码")
    val maxLength: Int = table.header["码长"]?.toIntOrNull()?.takeIf { it > 0 }
        ?: throw DataFormatException("码长 \"${table.header["码长"]}\"")
    val rules = PhraseRules(table.rules)
    private val sortedKeys = keys.sorted()
    private val construct = table.header["构词"]?.singleOrNull()

    /** The table's pinyin-entry marker (五笔拼音's `@`); null where it names one but has no such entries (fcitx's wbx). */
    val pinyinMarker: Char? = table.header["拼音"]?.singleOrNull()?.takeUnless { table.prefixRange(it.toString()).isEmpty() }
    private val ignored = table.header["规避字符"].orEmpty().toSet()

    private class Reverse(val codes: Map<String, String>, val construct: Map<String, String>)

    // built on first use, as libime does; with rules that is a session's first key, maybe on two threads (CodedWords in the background)
    private val reverse by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
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

    /** [index]'s code key at [at]; a glide walks entries as a trie (TableGlide). */
    fun codeKey(index: Int, at: Int): Char = table.codeKey(index, at)

    val size: Int get() = table.size

    /** The entries [pattern] leads to, in table order: codes starting with it, or with a [wildcard] those it spells whole, as libime; a wildcard scan stops at [MAX_MATCHES]. */
    fun match(pattern: String, wildcard: Char?): IntArray {
        val cut = if (wildcard == null) -1 else pattern.indexOf(wildcard)
        if (cut < 0) {
            // all of them: cut in code order, a short code past the cut would never be ranked (二笔's i: 19k entries)
            val range = table.prefixRange(pattern)
            return IntArray((range.last - range.first + 1).coerceAtLeast(0)) { range.first + it }
        }
        val out = ArrayList<Int>()
        if (pattern.length > maxLength) return IntArray(0)
        for (range in wildcardRanges(pattern, cut)) {
            for (i in range) {
                if (out.size == MAX_MATCHES) return out.toIntArray()
                if (spells(i, pattern, wildcard!!, whole = true)) out += i
            }
        }
        return out.toIntArray()
    }

    fun firstOf(prefix: String): Int = table.prefixRange(prefix).let { if (it.isEmpty()) -1 else it.first }

    /** Whether typing on can lead anywhere: some code, or pinyin entry, starts with [pattern] (a wildcard too, unlike [match]). */
    fun hasMatch(pattern: String, wildcard: Char?): Boolean {
        val cut = if (wildcard == null) -1 else pattern.indexOf(wildcard)
        if (pinyinMarker != null && !table.prefixRange("$pinyinMarker$pattern").isEmpty()) return true
        if (cut < 0) return !table.prefixRange(pattern).isEmpty()
        if (pattern.length > maxLength) return false
        return wildcardRanges(pattern, cut).any { range -> range.any { spells(it, pattern, wildcard!!, whole = false) } }
    }

    /** The code ranges a wildcard [pattern] can be among; a leading wildcard is each key in turn, never the whole table. */
    private fun wildcardRanges(pattern: String, cut: Int): List<IntRange> {
        if (cut > 0) return listOf(table.prefixRange(pattern.substring(0, cut)))
        val next = pattern.indexOf(pattern[0], 1).let { if (it < 0) pattern.length else it }
        val rest = pattern.substring(1, next)
        return sortedKeys.map { table.prefixRange("$it$rest") }.filterNot { it.isEmpty() }
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

    /** The pinyin entries spelt [pinyin] whole, or also those it starts if [prefix], in table order. */
    fun matchPinyin(pinyin: String, prefix: Boolean): IntArray {
        val marker = pinyinMarker ?: return IntArray(0)
        val range = table.prefixRange("$marker$pinyin")
        if (range.isEmpty()) return IntArray(0)
        if (prefix) return IntArray(range.last - range.first + 1) { range.first + it }
        return range.filter { table.codeLength(it) == pinyin.length + 1 }.toIntArray()
    }

    fun isPinyin(index: Int): Boolean = pinyinMarker != null && table.codeKey(index, 0) == pinyinMarker

    fun contains(code: String, text: String): Boolean = indexOf(code, text) >= 0

    fun indexOf(code: String, text: String): Int = table.exactRange(code).firstOrNull { table.text(it) == text } ?: -1

    fun codeOf(text: String): String? = reverse.codes[text] ?: encode(text)

    private fun charCode(char: String): String? = if (construct != null) reverse.construct[char] else reverse.codes[char]

    fun encode(text: String): String? = rules.encode(text, ::charCode)

    companion object {
        // a wildcard first matches the whole table; nobody pages through more than this
        const val MAX_MATCHES = 10_000
    }
}
