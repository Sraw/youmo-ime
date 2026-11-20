/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.data.PinyinDictionary

/** A dictionary's Latin words (iPhone as I'P'H'O'N'E), for [PinyinSegmenter] to find in lower-case input; one-letter words left out. */
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

    companion object {
        /** [Syllables]' upper-case letter syllable for lower-case [c]. */
        fun syllable(c: Char): Int = FIRST + (c - 'a')

        private val FIRST = Syllables.id("A")

        fun isLetter(syllable: Int) = syllable in FIRST until FIRST + LETTERS

        private const val LETTERS = 26

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

/** The user's Latin words (English's learned, lower case) for [PinyinSegmenter]; small, [set] anew as English learns, read on the setting thread. */
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
}
