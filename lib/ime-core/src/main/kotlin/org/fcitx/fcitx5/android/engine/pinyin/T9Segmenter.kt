/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.pinyin.SpellingIndex.Companion.keepBest
import org.fcitx.fcitx5.android.engine.pinyin.SpellingIndex.Companion.toMatches
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Companion.SEPARATOR
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import java.util.TreeMap

/**
 * Cuts pinyin typed on a phone's nine keys (九键) into a [SyllableGraph]: each of 2 to 9 stands
 * for its letters (`2` abc … `9` wxyz, ü as v on `8`), so `64426` is ni'hao, mi'gao and the rest,
 * all of them in the graph for the decoder to weigh. The separator is `'`: the session takes
 * the keyboard's 1 for it.
 *
 * From each digit the graph gets:
 * - every syllable the digits there type, of any length, with their [Fuzzy] partners;
 * - at the end of the input or before a separator, every syllable the digits may still become
 *   ([Kind.PARTIAL], or [Kind.EXTENDED] where they are a syllable of two letters or more too: `64`
 *   on the way to nian, but not `6`, 哦, on the way to men);
 * - with [abbreviations], the initials the digits type (简拼, `94` zh), where no longer syllable
 *   starts or another digit follows; without, only where nothing else is read but a syllable of
 *   one letter (`25` is 北京 too, not only 啊 then j …).
 *
 * A syllable the user chose for the first digits not yet chosen ([lock]) is in the input as its
 * letters and a separator: read as that syllable alone, or as an initial for a letter (`h`).
 */
class T9Segmenter(fuzzy: Set<Fuzzy> = emptySet(), private val abbreviations: Boolean = true) : Segmenter {

    // digits typing a syllable whole, the start of one, and an initial
    private val whole = HashMap<String, SyllableMatches>()
    private val starts = HashMap<String, SyllableMatches>()
    private val initials = HashMap<String, SyllableMatches>()

    // a chosen letter, as an initial: its syllables
    private val byInitial = HashMap<String, SyllableMatches>()

    // digits typing a syllable of an initial and a final, or a final alone, of two letters or more:
    // 啊, 呃, 哦, 呣, 嗯 and 儿 alone would keep 简拼 off every 2, 3, 6 and 7, and make each syllable
    // those digits start EXTENDED at the end
    private val regular = HashSet<String>()

    init {
        val wholeIds = HashMap<String, TreeMap<Int, Int>>()
        val startIds = HashMap<String, TreeMap<Int, Int>>()
        val initialIds = HashMap<String, TreeMap<Int, Int>>()
        val letterIds = HashMap<String, TreeMap<Int, Int>>()
        val initialRules = fuzzy.filter { it.onInitial }
        for (id in 0 until Syllables.count) {
            val spelling = Syllables.spelling(id)
            if (spelling[0].isUpperCase()) continue
            val parts = SpellingIndex.split(spelling)
            val spellings = if (parts == null) {
                listOf(spelling to 0)
            } else {
                val (init, fin) = parts
                val finalRules = fuzzy.filter { !it.onInitial && it.appliesAfter(init) }
                if (init.isNotEmpty()) keepBest(letterIds.getOrPut(init) { TreeMap() }, id, SyllableMatches.COMPLETION)
                SpellingIndex.variants(init, initialRules).flatMap { (i, iFlags) ->
                    if (i.isNotEmpty()) keepBest(initialIds.getOrPut(digits(i)) { TreeMap() }, id, iFlags or SyllableMatches.COMPLETION)
                    // with no initial the final is the syllable: ou's partner u is none, as in 双拼
                    SpellingIndex.finals(fin, finalRules).mapNotNull { (f, fFlags) ->
                        if (i.isEmpty() && Syllables.id(f) < 0) null else i + f to (iFlags or fFlags)
                    }
                }
            }
            for ((s, flags) in spellings) {
                val keys = digits(s)
                if (parts != null && s.length > 1) regular += keys
                keepBest(wholeIds.getOrPut(keys) { TreeMap() }, id, flags)
                for (n in 1 until keys.length) keepBest(startIds.getOrPut(keys.substring(0, n)) { TreeMap() }, id, flags or SyllableMatches.COMPLETION)
            }
        }
        wholeIds.forEach { (k, m) -> whole[k] = toMatches(m) }
        startIds.forEach { (k, m) -> starts[k] = toMatches(m) }
        initialIds.forEach { (k, m) -> initials[k] = toMatches(m) }
        letterIds.forEach { (k, m) -> byInitial[k] = toMatches(m) }
    }

    // the letters of a syllable taken are the session's to put in, not typed
    override fun reads(c: Char) = c in '2'..'9'

    override val typesLetters: Boolean get() = false

    override fun segment(input: String): SyllableGraph = GraphEdges.build(input) { at, edges ->
        if (input[at] in 'a'..'z') chosen(input, at, edges) else walk(input, at, edges)
    }

    /** The syllable, or initial, chosen at [at]: its letters up to the separator. */
    private fun chosen(input: String, at: Int, edges: GraphEdges) {
        var end = at
        while (end < input.length && input[end] in 'a'..'z') end++
        val text = input.substring(at, end)
        val id = Syllables.id(text)
        when {
            // a letter is taken as the initial it is offered as: m for ma, mi …, not 呣
            text.length == 1 && text in byInitial -> edges.add(at, 1, Kind.INITIAL, byInitial.getValue(text))
            id >= 0 -> edges.add(at, end - at, Kind.SYLLABLE, SyllableMatches(intArrayOf(id), intArrayOf(0)))
        }
    }

    private fun walk(input: String, at: Int, edges: GraphEdges) {
        var longest = 0
        var length = 0
        val initialEdges = ArrayList<Pair<Int, SyllableMatches>>()
        while (at + length < input.length && input[at + length] in '2'..'9' && length < MAX_SYLLABLE) {
            length++
            val keys = input.substring(at, at + length)
            val full = whole[keys]
            if (full != null) {
                edges.add(at, length, Kind.SYLLABLE, full)
                if (keys in regular) longest = length
            }
            val next = at + length
            if (next == input.length || input[next] == SEPARATOR) {
                starts[keys]?.let { edges.add(at, length, if (keys in regular) Kind.EXTENDED else Kind.PARTIAL, it) }
            }
            initials[keys]?.let { initialEdges += length to it }
        }
        for ((n, m) in initialEdges) {
            val last = at + n == input.length || input[at + n] == SEPARATOR
            // at the end the start of a syllable is read already, as PARTIAL
            if (last) continue
            if (longest == 0 || abbreviations && longest <= n) edges.add(at, n, Kind.INITIAL, m)
        }
    }

    companion object {
        private const val MAX_SYLLABLE = 6

        /** The key typing [c] on the nine keys: '2' for a, b, c; ü is v, on '8'. */
        fun digit(c: Char): Char = when (c) {
            in 'a'..'c' -> '2'
            in 'd'..'f' -> '3'
            in 'g'..'i' -> '4'
            in 'j'..'l' -> '5'
            in 'm'..'o' -> '6'
            in 'p'..'s' -> '7'
            in 't'..'v' -> '8'
            in 'w'..'z' -> '9'
            else -> c
        }

        /** The letters [digit] types, ü as v. */
        fun letters(digit: Char): String = KEYS.getOrElse(digit - '2') { "" }

        private val KEYS = listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz")

        /** [spelling] as typed on the nine keys. */
        fun digits(spelling: String): String = buildString(spelling.length) { spelling.forEach { append(digit(it)) } }
    }
}
