/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.data.PinyinDictionary

/**
 * The Latin words of a dictionary, spelt with the Latin-letter syllables (iPhone as I'P'H'O'N'E,
 * the `latin` layer of lexicon/latin.words): what [PinyinSegmenter] looks for in what is typed,
 * in lower case, so `iphone` reaches iPhone. Words of one letter are left out, as every letter
 * typed would be one.
 */
class LatinWords private constructor(private val dictionary: PinyinDictionary, val size: Int) {

    /** The length of each word [input] spells from [at], two letters or more, in lower case. */
    fun forEachAt(input: String, at: Int, visit: (length: Int) -> Unit) {
        // down the dictionary's trie a letter at a time: a key press makes no string
        var node = dictionary.root
        var end = at
        while (end < input.length && input[end] in 'a'..'z') {
            node = dictionary.child(node, syllable(input[end++]))
            if (node < 0) return
            if (end - at >= 2 && dictionary.wordCount(node) > 0) visit(end - at)
        }
    }

    companion object {
        /** The letter syllable ([Syllables]' upper-case A to Z) of [c], a lower-case letter. */
        fun syllable(c: Char): Int = FIRST + (c - 'a')

        private val FIRST = Syllables.id("A")

        /** Whether [syllable] is a letter's. */
        fun isLetter(syllable: Int) = syllable in FIRST until FIRST + LETTERS

        private const val LETTERS = 26

        /** The words of [dictionary] spelt with letter syllables alone. */
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

/**
 * The user's Latin words, as [LatinWords] are the dictionary's, for [PinyinSegmenter]: English's
 * learned words (U-31 §3), in lower case, which pinyin's model lists spelt by their letters.
 * Small, and [set] anew as English learns; read on the thread that sets it.
 */
class UserLatinWords {
    private class Node {
        val next = HashMap<Char, Node>(2)
        var word = false
    }

    private var root = Node()

    /** The words, in lower case. */
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

    /** The length of each word [input] spells from [at], two letters or more, as [LatinWords.forEachAt]. */
    fun forEachAt(input: String, at: Int, visit: (length: Int) -> Unit) {
        var node = root
        var end = at
        while (end < input.length && input[end] in 'a'..'z') {
            node = node.next[input[end++]] ?: return
            if (end - at >= 2 && node.word) visit(end - at)
        }
    }
}
