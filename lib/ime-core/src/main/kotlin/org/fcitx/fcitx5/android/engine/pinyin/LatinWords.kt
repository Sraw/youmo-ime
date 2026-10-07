/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.data.PinyinDictionary

/**
 * The Latin words of a dictionary, spelt with the Latin-letter syllables (iPhone as I'P'H'O'N'E,
 * the `latin` layer of lexicon/latin.words), as lower-case keys: what [PinyinSegmenter] looks for
 * in what is typed, so `iphone` reaches iPhone. Words of one letter are left out, as every letter
 * typed would be one.
 */
class LatinWords private constructor(private val keys: HashSet<String>, private val longest: Int) {

    val size: Int get() = keys.size

    /** The length of each word [input] spells from [at], two letters or more, in lower case. */
    fun forEachAt(input: String, at: Int, visit: (length: Int) -> Unit) {
        if (input[at] !in 'a'..'z') return
        var length = 2
        while (at + length <= input.length && length <= longest && input[at + length - 1] in 'a'..'z') {
            if (input.substring(at, at + length) in keys) visit(length)
            length++
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
            val keys = HashSet<String>()
            var longest = 0
            val path = StringBuilder()
            fun visit(node: Int) {
                if (path.length >= 2 && dictionary.wordCount(node) > 0) {
                    keys += path.toString()
                    longest = maxOf(longest, path.length)
                }
                val first = dictionary.firstChild(node)
                for (child in first until first + dictionary.childCount(node)) {
                    val s = dictionary.syllable(child)
                    if (!isLetter(s)) continue
                    path.append('a' + (s - FIRST))
                    visit(child)
                    path.setLength(path.length - 1)
                }
            }
            visit(dictionary.root)
            return LatinWords(keys, longest)
        }
    }
}
