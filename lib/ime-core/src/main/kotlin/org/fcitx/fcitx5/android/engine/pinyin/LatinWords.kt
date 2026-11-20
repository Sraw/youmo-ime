/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.data.PinyinDictionary
import java.util.TreeMap

/** A dictionary's Latin words (iPhone as I'P'H'O'N'E), for [PinyinSegmenter] and [T9Segmenter] to find in typed input; one-letter words left out. */
class LatinWords private constructor(private val dictionary: PinyinDictionary, val size: Int) {

    /** The length of each 2+-letter word [input] spells from [at]. */
    fun forEachAt(input: String, at: Int, visit: (length: Int) -> Unit) {
        // down the trie a letter at a time: a key press makes no string
        var node = dictionary.root
        var end = at
        while (end < input.length && input[end] in 'a'..'z') {
            node = dictionary.child(node, syllable(input[end++]))
            if (node < 0) return
            if (end - at >= 2 && dictionary.wordCount(node) > 0) visit(end - at)
        }
    }

    // the words' keys per keyboard: finding them is then a walk with no branching, as on the full keyboard
    private val byKeys = SettingsMemo<NineKeyLetters, KeyTrie>(KEYBOARDS) { KeyTrie(spellings(), it) }

    /** Builds [keys]' trie now, so the first key typed on them does not wait for it. */
    fun warmUp(keys: NineKeyLetters) {
        byKeys[keys]
    }

    internal fun keyTrieSize(keys: NineKeyLetters): Int = byKeys[keys].size

    internal fun keyTrieBuilt(keys: NineKeyLetters): Boolean = keys in byKeys

    /** As [forEachAt] for [input] typed on [keys]: a key may be any of its letters, a letter typed as itself (upper case) only that one. */
    fun forEachAtKeys(input: String, at: Int, keys: NineKeyLetters, visit: (length: Int) -> Unit) {
        val trie = byKeys[keys]
        var node = 0
        var end = at
        var fixed = false
        while (end < input.length) {
            val c = input[end]
            val key = when {
                keys.isCode(c) -> c
                c in T9Segmenter.FIXED -> keys.key(c.lowercaseChar()).also { fixed = true }
                else -> return
            }
            node = trie.child(node, key)
            if (node < 0) return
            end++
            if (end - at < 2 || !trie.isWord(node)) continue
            // the keys' trie knows no letter: one typed as itself is checked letter by letter
            if (!fixed || spells(input, at, end, keys)) visit(end - at)
        }
    }

    // whether a word is spelt by [input]'s keys from [at] to [end], each letter typed as itself kept
    private fun spells(input: String, at: Int, end: Int, keys: NineKeyLetters): Boolean {
        fun go(node: Int, i: Int): Boolean {
            if (i == end) return dictionary.wordCount(node) > 0
            val c = input[i]
            for (letter in if (c in T9Segmenter.FIXED) c.lowercase() else keys.letters(c)) {
                if (letter !in 'a'..'z') continue
                val child = dictionary.child(node, syllable(letter))
                if (child >= 0 && go(child, i + 1)) return true
            }
            return false
        }
        return go(dictionary.root, at)
    }

    // every word in lower-case letters
    private fun spellings(): List<String> {
        val out = ArrayList<String>(size)
        val letters = StringBuilder()
        fun visit(node: Int) {
            if (letters.length >= 2 && dictionary.wordCount(node) > 0) out += letters.toString()
            val first = dictionary.firstChild(node)
            for (child in first until first + dictionary.childCount(node)) {
                val s = dictionary.syllable(child)
                if (!isLetter(s)) continue
                letters.append('a' + (s - FIRST))
                visit(child)
                letters.setLength(letters.length - 1)
            }
        }
        visit(dictionary.root)
        return out
    }

    /** [words] as [keys] type them: node 0 the root, each node's children together and in key order. */
    private class KeyTrie(words: List<String>, keys: NineKeyLetters) {
        private val start: IntArray
        private val key: CharArray
        private val word: BooleanArray

        init {
            val next = arrayListOf(TreeMap<Char, Int>())
            val ends = arrayListOf(false)
            fun add(typed: String) {
                var node = 0
                for (k in typed) {
                    node = next[node].getOrPut(k) {
                        next += TreeMap()
                        ends += false
                        next.size - 1
                    }
                }
                ends[node] = true
            }
            // every word by its letters' first keys, then its other typings while the trie is under [MAX_KEY_NODES]:
            // a custom keyboard with letters on several keys types each word many ways
            for (w in words) add(String(CharArray(w.length) { keys.key(w[it]) }))
            if (('a'..'z').any { keys.keysOf(it).length > 1 }) {
                for (w in words) {
                    if (next.size >= MAX_KEY_NODES) break
                    keys.typings(w).drop(1).forEach(::add)
                }
            }
            // breadth first, so each node's children are numbered together
            val n = next.size
            start = IntArray(n + 1)
            key = CharArray(n)
            word = BooleanArray(n)
            val order = IntArray(n)
            var count = 1
            for (i in 0 until n) {
                val old = order[i]
                word[i] = ends[old]
                start[i] = count
                for ((k, child) in next[old]) {
                    key[count] = k
                    order[count++] = child
                }
            }
            start[n] = count
        }

        fun child(node: Int, k: Char): Int {
            var lo = start[node]
            var hi = start[node + 1] - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                when {
                    key[mid] < k -> lo = mid + 1
                    key[mid] > k -> hi = mid - 1
                    else -> return mid
                }
            }
            return -1
        }

        fun isWord(node: Int) = word[node]

        val size: Int get() = word.size
    }

    companion object {
        /** [Syllables]' upper-case letter syllable for lower-case [c]. */
        fun syllable(c: Char): Int = FIRST + (c - 'a')

        private val FIRST = Syllables.id("A")

        fun isLetter(syllable: Int) = syllable in FIRST until FIRST + LETTERS

        private const val LETTERS = 26

        /** Each letter a–z as its syllable alone: a [SyllableGraph.Kind.LETTER] edge's matches. */
        internal val MATCHES = Array(LETTERS) { SyllableMatches(intArrayOf(syllable('a' + it)), intArrayOf(0)) }

        // the keyboards a user switches among: nine keys, 14, 18, a custom one
        private const val KEYBOARDS = 4

        internal const val MAX_KEY_NODES = 100_000

        fun of(dictionary: PinyinDictionary): LatinWords {
            var size = 0
            fun visit(node: Int, depth: Int) {
                if (depth >= 2 && dictionary.wordCount(node) > 0) size++
                val first = dictionary.firstChild(node)
                for (child in first until first + dictionary.childCount(node)) {
                    if (isLetter(dictionary.syllable(child))) visit(child, depth + 1)
                }
            }
            visit(dictionary.root, 0)
            return LatinWords(dictionary, size)
        }
    }
}

/** The user's Latin words (English's learned, lower case) for [PinyinSegmenter] and [T9Segmenter]; small, [set] anew as English learns, read on the setting thread. */
class UserLatinWords {
    private class Node {
        val next = HashMap<Char, Node>(2)
        var word = false
    }

    private var root = Node()

    var words: Set<String> = emptySet()
        private set

    fun set(spellings: Set<String>) {
        words = spellings
        root = Node().also { r ->
            for (w in spellings) {
                var n = r
                for (c in w) n = n.next.getOrPut(c) { Node() }
                n.word = true
            }
        }
    }

    fun forEachAt(input: String, at: Int, visit: (length: Int) -> Unit) {
        var node = root
        var end = at
        while (end < input.length && input[end] in 'a'..'z') {
            node = node.next[input[end++]] ?: return
            if (end - at >= 2 && node.word) visit(end - at)
        }
    }

    /** As [forEachAt] for [input] typed on [keys], as [LatinWords.forEachAtKeys]; a length may be visited more than once. */
    fun forEachAtKeys(input: String, at: Int, keys: NineKeyLetters, visit: (length: Int) -> Unit) {
        // few words: down every letter of each key
        val root = root
        fun go(node: Node, end: Int) {
            if (end - at >= 2 && node.word) visit(end - at)
            if (end >= input.length) return
            val c = input[end]
            val letters = if (c in T9Segmenter.FIXED) c.lowercase() else if (keys.isCode(c)) keys.letters(c) else return
            for (letter in letters) node.next[letter]?.let { go(it, end + 1) }
        }
        go(root, at)
    }
}
