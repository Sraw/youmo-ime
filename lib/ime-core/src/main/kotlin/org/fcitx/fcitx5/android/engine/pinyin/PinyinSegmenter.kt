/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Companion.SEPARATOR
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind

/**
 * Cuts full pinyin into a [SyllableGraph]. Unlike libime, which takes the longest syllable and
 * patches ambiguity with special cases, this keeps every cut and leaves the choice to the
 * language model; the graph stays small because edges only go forward and a syllable is at most
 * six letters.
 *
 * From each position the graph gets an edge for:
 * - every syllable the text there spells, of any length (`xian` and `xi`);
 * - with `neighbours`, every syllable it spells with a letter slipped onto the key next to it
 *   (`hap` for hao), where it spells nothing as typed; not in input of consonants alone, which
 *   is 简拼 (`wxhn` is no slip for wxun);
 * - the initial alone (简拼) where no longer syllable starts (`n` in `nh`), and also where one
 *   does if a consonant follows (`n` in `ng` for 那个, `ng` being a syllable too), but not if a
 *   vowel does (`n` in `nihao`). `a`, `e` and `o` stand for the syllables they start only
 *   where no longer syllable starts (`ag` 爱国, but not `an` 爱你). Where `zh` stands alone
 *   and no vowel follows, so does `z` (`zhrm` for 中华人民; not in `zho`);
 * - syllables the text there may still become: at the end of the input (`zho` for zhong, `zhan`
 *   for zhang), or before a separator if the text is no syllable yet (`zho'`; `xi'` is closed);
 * - failing all of those, one character as typed (not where a slip starts: `uan` is yan, not
 *   `u` then an; the character alone would cost more than the slip).
 *
 * Build one per settings change; [segment] is then cheap enough for every key press.
 *
 * Upper-case letters are the Latin-letter syllables (`A` in A股), so a caller wanting pinyin
 * from input that auto-capitalisation touched must lower-case it first.
 *
 * With [latin], where the input spells one of its words (`iphone`, `app`) each of its letters
 * also gets an edge as its letter syllable ([Kind.LETTER]): the dictionary has the word so
 * spelt, and the model weighs it against the pinyin the same letters read as. With hundreds of
 * two-letter words (OK, PC) that is most letters of most input, every position one to read
 * words from: measured, the first candidates stay as they were and a key costs ~0.2 ms more.
 */
class PinyinSegmenter(
    fuzzy: Set<Fuzzy> = emptySet(),
    typos: Boolean = true,
    neighbours: Boolean = false,
    private val latin: LatinWords? = null,
) : Segmenter {

    private val index = SpellingIndex(fuzzy, typos, neighbours)

    override fun segment(input: String): SyllableGraph {
        // read as slips, 简拼 turns into anything: 我喜欢你 into 网讯
        val slips = input.any { it in VOWELS }
        val letters = latin?.let { lettersOfWords(input, it) }
        return GraphEdges.build(input) { at, edges ->
            walk(input, at, edges, slips)
            if (letters != null && letters[at]) edges.add(at, 1, Kind.LETTER, LETTERS[input[at] - 'a'])
        }
    }

    // where a Latin word is typed, each of its letters: true
    private fun lettersOfWords(input: String, latin: LatinWords): BooleanArray? {
        var marks: BooleanArray? = null
        for (at in input.indices) {
            latin.forEachAt(input, at) { length ->
                val m = marks ?: BooleanArray(input.length).also { marks = it }
                for (i in at until at + length) m[i] = true
            }
        }
        return marks
    }

    /** Adds the edges leaving [at]: a walk down the spelling trie along the input. */
    private fun walk(input: String, at: Int, edges: GraphEdges, slips: Boolean) {
        var node = index.root
        var longestSyllable = 0
        var initial = 0
        var initialMatches: SyllableMatches? = null
        var shorter: SyllableMatches? = null
        var shorterLength = 0
        var length = 0
        while (at + length < input.length) {
            node = index.child(node, input[at + length])
            if (node < 0) break
            length++
            val full = index.matches(node)
            if (full != null) {
                edges.add(at, length, Kind.SYLLABLE, full)
                longestSyllable = length
            }
            val alone = index.initial(node)
            val more = index.extensions(node)
            // no syllable for the rules below: what an initial stands for is read beside a slip.
            // Nor where the text may still become one: `zho` on the way to zhong is no zhi, nor
            // `zh` before `o`.
            if (slips && !growing(input, at + length, node, alone != null || more != null)) {
                index.slip(node)?.let { edges.add(at, length, Kind.SYLLABLE, it) }
            }
            if (alone != null) {
                shorter = initialMatches
                shorterLength = initial
                initial = length
                initialMatches = alone
            } else if (more != null && mayGoOn(input, at + length, full == null)) {
                edges.add(at, length, if (full != null && full.anyWhole()) Kind.EXTENDED else Kind.PARTIAL, more)
            }
        }
        if (initialMatches != null && (longestSyllable <= initial || beforeConsonant(input, at, initial))) {
            edges.add(at, initial, Kind.INITIAL, initialMatches)
            if (shorter != null && longestSyllable <= initial && !startsSyllable(input, at + initial)) {
                edges.add(at, shorterLength, Kind.INITIAL, shorter)
            }
        }
    }

    /** Whether text spelled up to [node], ending at [end], [spells] the start of a syllable still being typed. */
    private fun growing(input: String, end: Int, node: Int, spells: Boolean): Boolean {
        if (!spells) return false
        if (mayGoOn(input, end, true)) return true
        val next = index.child(node, input[end])
        return next >= 0 && (index.matches(next) != null || index.initial(next) != null || index.extensions(next) != null)
    }

    private companion object {
        const val VOWELS = "aeiouv"

        val LETTERS = Array(26) { SyllableMatches(intArrayOf(LatinWords.syllable('a' + it)), intArrayOf(0)) }

        /** A vowel at [at]: what comes before it is the start of a syllable, not an initial alone. */
        fun startsSyllable(input: String, at: Int) = at < input.length && input[at] in VOWELS

        /** A consonant initial of [length] at [at], with a consonant after it. */
        fun beforeConsonant(input: String, at: Int, length: Int) =
            input[at] !in VOWELS && input[at + length] !in VOWELS

        /** Whether one of these is a syllable as typed, or its fuzzy partner: not only a slip (`zhon` for zhong). */
        fun SyllableMatches.anyWhole() = (0 until size).any { flags(it) and SyllableMatches.TYPO == 0 }

        /**
         * Whether text ending at [at] may still become a longer syllable: at the end of the input,
         * or before a separator if it is [unfinished]; a separator after a whole syllable closes it.
         */
        fun mayGoOn(input: String, at: Int, unfinished: Boolean) =
            at == input.length || unfinished && input[at] == SEPARATOR
    }
}
