/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import java.util.TreeMap

/**
 * A character trie over everything a user may type for one syllable: the standard spellings,
 * their [Fuzzy] variants and, with [typos], common slips. Walking it along the input finds
 * every syllable a piece of input can be, without allocating. With [neighbours], a standard
 * spelling with one letter slipped onto a key next to it ([KeyNeighbours]) reads as the syllable
 * too, where the slip spells nothing else: `hap` for hao, but not `gao` for hao.
 *
 * Each node knows the syllables its own key spells ([matches]) and those of every key below it
 * ([completions], [extensions]), the latter for input that stops part-way through a syllable.
 */
internal class SpellingIndex(fuzzy: Set<Fuzzy>, typos: Boolean, neighbours: Boolean = false) {

    private val childStart: IntArray
    private val childChar: CharArray
    private val childNode: IntArray
    private val matches: Array<SyllableMatches?>
    private val completions: Array<SyllableMatches>
    private val extensions: Array<SyllableMatches?>
    private val initials: Array<SyllableMatches?>
    private val slips: Array<SyllableMatches?>

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
     * the syllables they start (`ag` 爱国), and `z`, `c` and `s` stand for `zh`, `ch` and `sh`
     * syllables too (`zg` 中国), fuzzy or not.
     */
    fun initial(node: Int): SyllableMatches? = initials[node]

    /**
     * Syllables this node's key spells with one letter slipped onto a neighbouring key, flagged
     * [SyllableMatches.NEIGHBOUR]; null if none. Only where the key spells nothing itself, and
     * never as a completion: a slip is read only once it is typed in full.
     */
    fun slip(node: Int): SyllableMatches? = slips[node]

    private class Node {
        val children = TreeMap<Char, Node>()
        val matches = TreeMap<Int, Int>()
        val slips = TreeMap<Int, Int>()
    }

    init {
        val root = Node()
        val byInitial = HashMap<String, TreeMap<Int, Int>>()
        val spelt = HashSet<String>()
        fun node(key: String) = key.fold(root) { n, c -> n.children.getOrPut(c) { Node() } }
        fun add(key: String, syllable: Int, flags: Int) {
            keepBest(node(key).matches, syllable, flags)
            spelt += key
        }
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
                // as an initial, z also stands for zh (zg 中国, sm 什么) in every mainstream input method
                if (i.length == 2 && i[1] == 'h') keepBest(byInitial.getOrPut(i.take(1)) { TreeMap() }, id, iFlags or SyllableMatches.COMPLETION)
                for ((f, fFlags) in finals(fin, finalRules)) {
                    // with no initial the final is the syllable: ou's partner u is none, so u stays as typed
                    if (i.isEmpty() && Syllables.id(f) < 0) continue
                    val flags = iFlags or fFlags
                    add(i + f, id, flags)
                    if (typos) Typo.entries.mapNotNull { it.of(i, f) }.forEach { add(it, id, flags or SyllableMatches.TYPO) }
                }
            }
        }
        if (neighbours) addSlips(spelt) { key, id -> keepBest(node(key).slips, id, SyllableMatches.NEIGHBOUR) }

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
        slips = Array(order.size) { i -> order[i].slips.takeIf { it.isNotEmpty() }?.let(::toMatches) }
    }

    companion object {
        /** Each standard spelling with one letter slipped, as [add] is to keep it, unless [spelt] already. */
        fun addSlips(spelt: Set<String>, add: (String, Int) -> Unit) {
            for (id in 0 until Syllables.count) {
                val (init, fin) = split(Syllables.spelling(id)) ?: continue
                for ((f, _) in finals(fin, emptyList())) {
                    slips(init + f).filter { it !in spelt }.forEach { add(it, id) }
                }
            }
        }

        /** [spelling] with each of its letters in turn slipped onto each key next to it. */
        fun slips(spelling: String): List<String> = if (spelling.length < 2) {
            emptyList()
        } else {
            spelling.indices.flatMap { at ->
                KeyNeighbours.of(spelling[at]).map { spelling.substring(0, at) + it + spelling.substring(at + 1) }
            }
        }

        /** Longest last, so the last one a spelling starts with is its initial. */
        val INITIALS = listOf(
            "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x",
            "r", "z", "c", "s", "y", "w", "zh", "ch", "sh",
        )

        /** Syllables that are not an initial plus a final, so no rule applies to them. */
        private val STANDALONE = setOf("m", "n", "ng", "r")

        /**
         * The initial and final of [spelling], the initial empty for a, ai, er ...; null for a
         * syllable that is no initial plus a final (m, ng, the Latin letters).
         */
        fun split(spelling: String): Pair<String, String>? {
            if (spelling in STANDALONE || spelling[0].isUpperCase()) return null
            val init = INITIALS.lastOrNull { spelling.startsWith(it) && spelling.length > it.length } ?: ""
            return init to spelling.substring(init.length)
        }

        /** [part] itself, then its partner under each enabled rule, flagged [SyllableMatches.FUZZY]. */
        fun variants(part: String, rules: List<Fuzzy>): List<Pair<String, Int>> =
            listOf(part to 0) + rules.mapNotNull { r -> r.partner(part)?.let { it to SyllableMatches.FUZZY } }

        /** As [variants], and lüe is written `lue` as often as `lve`: both are standard. */
        fun finals(fin: String, rules: List<Fuzzy>): List<Pair<String, Int>> =
            variants(fin, rules) + if (fin == "ve") listOf("ue" to 0) else emptyList()

        /** Fewer flags win: an exact spelling beats a fuzzy one beats a typo. */
        fun keepBest(into: MutableMap<Int, Int>, syllable: Int, flags: Int) {
            val old = into[syllable]
            if (old == null || flags < old) into[syllable] = flags
        }

        fun toMatches(m: Map<Int, Int>) =
            SyllableMatches(m.keys.toIntArray(), m.values.toIntArray())
    }
}
