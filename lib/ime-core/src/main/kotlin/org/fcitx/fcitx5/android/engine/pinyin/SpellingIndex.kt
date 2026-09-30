/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import java.util.TreeMap

/**
 * A character trie over everything a user may type for one syllable: the standard spellings,
 * their [Fuzzy] variants and, with [typos], common slips. Walking it along the input finds
 * every syllable a piece of input can be, without allocating.
 *
 * Each node knows the syllables its own key spells ([matches]) and those of every key below it
 * ([completions], [extensions]), the latter for input that stops part-way through a syllable.
 */
internal class SpellingIndex(fuzzy: Set<Fuzzy>, typos: Boolean) {

    private val childStart: IntArray
    private val childChar: CharArray
    private val childNode: IntArray
    private val matches: Array<SyllableMatches?>
    private val completions: Array<SyllableMatches>
    private val extensions: Array<SyllableMatches?>
    private val initials: Array<SyllableMatches?>

    val root: Int get() = 0

    /** @return the node for [node]'s key followed by [c], or -1 */
    fun child(node: Int, c: Char): Int {
        for (i in childStart[node] until childStart[node + 1]) if (childChar[i] == c) return childNode[i]
        return -1
    }

    /** Syllables this node's key spells in full, or null if it spells none. */
    fun matches(node: Int): SyllableMatches? = matches[node]

    /** Syllables whose typed spellings run through this node, flagged [SyllableMatches.COMPLETION]. */
    fun completions(node: Int): SyllableMatches = completions[node]

    /**
     * Syllables that typing on from this node reaches, other than what its key spells exactly, or
     * null if none: `zhan` may be on the way to zhang, and `zhon` (a slip for zhong when
     * finished) to zhong.
     */
    fun extensions(node: Int): SyllableMatches? = extensions[node]

    /**
     * If this node's key is an initial (`b`, `zh` ...), the syllables it may stand for alone
     * (简拼), flagged [SyllableMatches.COMPLETION]; otherwise null. `a`, `e` and `o` count, for
     * the syllables they start (`ag` 爱国). Not the [completions]: those of `z` include every
     * `zh` syllable, which `z` only stands for with [Fuzzy.Z_ZH].
     */
    fun initial(node: Int): SyllableMatches? = initials[node]

    private class Node {
        val children = TreeMap<Char, Node>()
        val matches = TreeMap<Int, Int>()
    }

    init {
        val root = Node()
        val byInitial = HashMap<String, TreeMap<Int, Int>>()
        fun add(key: String, syllable: Int, flags: Int) {
            val node = key.fold(root) { n, c -> n.children.getOrPut(c) { Node() } }
            keepBest(node.matches, syllable, flags)
        }
        for (id in 0 until Syllables.count) {
            val spelling = Syllables.spelling(id)
            if (spelling in STANDALONE || spelling[0].isUpperCase()) {
                add(spelling, id, 0)
                continue
            }
            val init = INITIALS.lastOrNull { spelling.startsWith(it) && spelling.length > it.length } ?: ""
            val fin = spelling.substring(init.length)
            val finalRules = fuzzy.filter { !it.onInitial && it.appliesAfter(init) }
            for ((i, iFlags) in variants(init, fuzzy.filter { it.onInitial })) {
                keepBest(byInitial.getOrPut(i.ifEmpty { spelling.take(1) }) { TreeMap() }, id, iFlags or SyllableMatches.COMPLETION)
                for ((f, fFlags) in finals(fin, finalRules)) {
                    val flags = iFlags or fFlags
                    add(i + f, id, flags)
                    if (typos) typosOf(i, f).forEach { add(it, id, flags or SyllableMatches.TYPO) }
                }
            }
        }

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
        completions = Array(order.size) { toMatches(below[it]) }
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
    }

    companion object {
        /** Longest last, so the last one a spelling starts with is its initial. */
        private val INITIALS = listOf(
            "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x",
            "r", "z", "c", "s", "y", "w", "zh", "ch", "sh",
        )

        /** Syllables that are not an initial plus a final, so no rule applies to them. */
        private val STANDALONE = setOf("m", "n", "ng", "r")

        /** [part] itself, then its partner under each enabled rule, flagged [SyllableMatches.FUZZY]. */
        private fun variants(part: String, rules: List<Fuzzy>): List<Pair<String, Int>> =
            listOf(part to 0) + rules.mapNotNull { r -> r.partner(part)?.let { it to SyllableMatches.FUZZY } }

        /** As [variants], and lüe is written `lue` as often as `lve`: both are standard. */
        private fun finals(fin: String, rules: List<Fuzzy>): List<Pair<String, Int>> =
            variants(fin, rules) + if (fin == "ve") listOf("ue" to 0) else emptyList()

        private fun typosOf(init: String, fin: String): List<String> = listOfNotNull(
            // gn for ng: zhagn, xign
            if (fin.endsWith("ng")) init + fin.dropLast(2) + "gn" else null,
            // a dropped g after o: zhon, xion
            if (fin.endsWith("ong")) init + fin.dropLast(1) else null,
            // v for the ü these initials spell as u: jv, xve, qvan
            if (init in U_IS_V && fin.startsWith("u")) init + "v" + fin.substring(1) else null,
        )

        private val U_IS_V = setOf("j", "q", "x", "y")

        /** Fewer flags win: an exact spelling beats a fuzzy one beats a typo. */
        private fun keepBest(into: MutableMap<Int, Int>, syllable: Int, flags: Int) {
            val old = into[syllable]
            if (old == null || flags < old) into[syllable] = flags
        }

        private fun toMatches(m: Map<Int, Int>) =
            SyllableMatches(m.keys.toIntArray(), m.values.toIntArray())
    }
}
