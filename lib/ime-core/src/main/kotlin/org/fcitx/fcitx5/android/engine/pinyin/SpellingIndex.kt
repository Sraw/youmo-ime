/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import java.util.SortedMap
import java.util.TreeMap

/**
 * A character trie over everything typed for a syllable: standard spellings, [Fuzzy] variants and, with
 * [typos], common slips; walking it finds every syllable a piece of input can be without allocating. With
 * [neighbours], one-letter substitutions are kept as slips ([slip]); which to read is the segmenter's call
 * (the index knows no keyboard).
 */
internal class SpellingIndex(fuzzy: Set<Fuzzy>, typos: Boolean, neighbours: Boolean = false) {

    private val childStart: IntArray
    private val childChar: CharArray
    private val childNode: IntArray
    private val matches: Array<SyllableMatches?>
    private val extensions: Array<SyllableMatches?>
    private val initials: Array<SyllableMatches?>
    private val slips: Array<SyllableMatches?>

    val root: Int get() = 0

    /** @return the node for [node]'s key followed by [c], or -1 */
    fun child(node: Int, c: Char): Int {
        for (i in childStart[node] until childStart[node + 1]) if (childChar[i] == c) return childNode[i]
        return -1
    }

    /** Null if none. */
    fun matches(node: Int): SyllableMatches? = matches[node]

    /** Syllables typing on reaches, beyond what the key spells exactly (`zhan` toward zhang); null if none. */
    fun extensions(node: Int): SyllableMatches? = extensions[node]

    /** For an initial key, the syllables it stands for alone (简拼, [SyllableMatches.COMPLETION]); `a`/`e`/`o` count, and `z`/`c`/`s` also stand for `zh`/`ch`/`sh`. Null otherwise. */
    fun initial(node: Int): SyllableMatches? = initials[node]

    /** Syllables spelt with one letter substituted ([SyllableMatches.NEIGHBOUR]); may include the key's own (lve/lue), which the segmenter drops. Never a completion. */
    fun slip(node: Int): SyllableMatches? = slips[node]

    private class Node {
        val children = TreeMap<Char, Node>()
        val matches = TreeMap<Int, Int>()
        val slips = TreeMap<Int, Int>()
    }

    init {
        val root = Node()
        val byInitial = HashMap<String, TreeMap<Int, Int>>()
        fun node(key: String) = key.fold(root) { n, c -> n.children.getOrPut(c) { Node() } }
        fun add(key: String, syllable: Int, flags: Int) = keepBest(node(key).matches, syllable, flags)
        for (id in 0 until Syllables.count) {
            val spelling = Syllables.spelling(id)
            val parts = split(spelling)
            if (parts == null) {
                add(spelling, id, 0)
                continue
            }
            val (init, fin) = parts
            val finalRules = fuzzy.filter { !it.onInitial && it.appliesAfter(init) }
            for ((i, iFlags) in variants(init, fuzzy.filter { it.onInitial })) {
                keepBest(byInitial.getOrPut(i.ifEmpty { spelling.take(1) }) { TreeMap() }, id, iFlags or SyllableMatches.COMPLETION)
                // as an initial, z also stands for zh (zg 中国), as in every mainstream input method
                if (i.length == 2 && i[1] == 'h') keepBest(byInitial.getOrPut(i.take(1)) { TreeMap() }, id, iFlags or SyllableMatches.COMPLETION)
                // with no initial ou's partner u is none (finalsAfter)
                for ((f, fFlags) in finalsAfter(i, fin, finalRules)) {
                    val flags = iFlags or fFlags
                    add(i + f, id, flags)
                    if (typos) Typo.entries.mapNotNull { it.of(i, f) }.forEach { add(it, id, flags or SyllableMatches.TYPO) }
                }
            }
        }
        if (neighbours) addSlips { key, id -> keepBest(node(key).slips, id, SyllableMatches.NEIGHBOUR) }

        // breadth-first, so each node's children are consecutive
        val order = arrayListOf(root)
        var next = 0
        while (next < order.size) order += order[next++].children.values
        val index = HashMap<Node, Int>(order.size * 2).apply { order.forEachIndexed { i, n -> put(n, i) } }
        childStart = IntArray(order.size + 1)
        childChar = CharArray(order.size - 1)
        childNode = IntArray(order.size - 1)
        var edge = 0
        order.forEachIndexed { i, n ->
            childStart[i] = edge
            n.children.forEach { (c, child) ->
                childChar[edge] = c
                childNode[edge++] = index.getValue(child)
            }
        }
        childStart[order.size] = edge
        matches = Array(order.size) { i -> order[i].matches.takeIf { it.isNotEmpty() }?.let(::toMatches) }
        // children come after their parent, so walking backwards sees every subtree complete
        val below = Array(order.size) { TreeMap<Int, Int>() }
        for (i in order.indices.reversed()) {
            order[i].matches.forEach { (s, f) -> keepBest(below[i], s, f or SyllableMatches.COMPLETION) }
            for (e in childStart[i] until childStart[i + 1]) {
                below[childNode[e]].forEach { (s, f) -> keepBest(below[i], s, f) }
            }
        }
        // only what typing on reaches: jv is a finished slip for ju, not the start of one
        extensions = Array(order.size) { i ->
            val beyond = TreeMap<Int, Int>()
            for (e in childStart[i] until childStart[i + 1]) {
                below[childNode[e]].forEach { (s, f) -> if (order[i].matches[s] != 0) keepBest(beyond, s, f) }
            }
            if (beyond.isEmpty()) null else toMatches(beyond)
        }
        initials = arrayOfNulls(order.size)
        byInitial.forEach { (key, syllables) ->
            var node = 0
            for (c in key) node = if (node < 0) -1 else child(node, c)
            if (node >= 0) initials[node] = toMatches(syllables)
        }
        slips = Array(order.size) { i -> order[i].slips.takeIf { it.isNotEmpty() }?.let(::toMatches) }
    }

    companion object {
        // a few: the current settings and recent ones
        private val made = SettingsMemo<Triple<Set<Fuzzy>, Boolean, Boolean>, SpellingIndex>(MEMO) { (f, t, n) -> SpellingIndex(f, t, n) }

        fun of(fuzzy: Set<Fuzzy>, typos: Boolean, neighbours: Boolean = false): SpellingIndex = made[Triple(fuzzy.toSet(), typos, neighbours)]

        internal val held: Int get() = made.size

        const val MEMO = 4

        fun addSlips(add: (String, Int) -> Unit) {
            for (id in 0 until Syllables.count) {
                val (init, fin) = split(Syllables.spelling(id)) ?: continue
                for ((f, _) in finals(fin, emptyList())) slips(init + f).forEach { add(it, id) }
            }
        }

        fun slips(spelling: String): List<String> = if (spelling.length < 2) {
            emptyList()
        } else {
            spelling.indices.flatMap { at ->
                ('a'..'z').filter { it != spelling[at] }.map { spelling.substring(0, at) + it + spelling.substring(at + 1) }
            }
        }

        /** Longest last, so the last one a spelling starts with is its initial. */
        val INITIALS = listOf(
            "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x",
            "r", "z", "c", "s", "y", "w", "zh", "ch", "sh",
        )

        /** Syllables that are not an initial plus a final, so no rule applies to them. */
        private val STANDALONE = setOf("m", "n", "ng", "r")

        val U_IS_V = setOf("j", "q", "x", "y")

        /** Initial (empty for a, ai, er) and final; null if not initial plus final (m, ng, Latin letters). */
        fun split(spelling: String): Pair<String, String>? {
            if (spelling in STANDALONE || spelling[0].isUpperCase()) return null
            val init = INITIALS.lastOrNull { spelling.startsWith(it) && spelling.length > it.length } ?: ""
            return init to spelling.substring(init.length)
        }

        /** [part] itself, then its partner under each enabled rule, flagged [SyllableMatches.FUZZY]. */
        fun variants(part: String, rules: List<Fuzzy>): List<Pair<String, Int>> =
            listOf(part to 0) + rules.mapNotNull { r -> r.partner(part)?.let { it to SyllableMatches.FUZZY } }

        /** Also `lue` for `lve`: both standard. */
        fun finals(fin: String, rules: List<Fuzzy>): List<Pair<String, Int>> =
            variants(fin, rules) + if (fin == "ve") listOf("ue" to 0) else emptyList()

        /** With no initial the final is the syllable, so a partner that is no syllable (ou's u) spells nothing. */
        fun finalsAfter(init: String, fin: String, rules: List<Fuzzy>): List<Pair<String, Int>> =
            finals(fin, rules).filter { (f, _) -> init.isNotEmpty() || Syllables.id(f) >= 0 }

        /** Fewer flags win: an exact spelling beats a fuzzy one beats a typo. */
        fun keepBest(into: MutableMap<Int, Int>, syllable: Int, flags: Int) {
            val old = into[syllable]
            if (old == null || flags < old) into[syllable] = flags
        }

        /** Sorted by syllable, for [SyllableMatches.indexOf]'s binary search. */
        fun toMatches(m: SortedMap<Int, Int>) =
            SyllableMatches(m.keys.toIntArray(), m.values.toIntArray())
    }
}
