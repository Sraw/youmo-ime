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
 *   (`hap` for hao), costing as [SlipCost] has it by where the key was tapped; with no place (a
 *   hardware key), only where it spells nothing as typed, nor is consonants alone, 简拼 (`kw` is
 *   no slip for ke there: 你看完了 typed `nikwl`); none in input of consonants alone (`wxhn` is no
 *   slip for wxun; 061-neighbour-correction §4.5, §4.6);
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
 * [userLatin], the user's (English's learned words, U-31 §3), the same way: the user model has them.
 */
class PinyinSegmenter(
    fuzzy: Set<Fuzzy> = emptySet(),
    typos: Boolean = true,
    neighbours: Boolean = false,
    private val latin: LatinWords? = null,
    private val userLatin: UserLatinWords? = null,
    private val slipCost: SlipCost = SlipCost(),
) : Segmenter {

    // the settings' own, shared by the sessions made with them (SettingsMemo)
    private val index = SpellingIndex.of(fuzzy, typos, neighbours)

    override fun segment(input: String): SyllableGraph = segment(input, null, null)

    override fun segment(input: String, taps: FloatArray?, touches: Array<Touch?>?): SyllableGraph {
        // read as slips, 简拼 turns into anything: 我喜欢你 into 网讯
        val slips = input.any { it in VOWELS }
        val letters = if (latin == null && userLatin == null) null else lettersOfWords(input)
        return GraphEdges.build(input) { at, edges ->
            walk(input, at, edges, slips, touches)
            if (letters != null && letters[at]) edges.add(at, 1, Kind.LETTER, LETTERS[input[at] - 'a'])
        }
    }

    // where a Latin word is typed, each of its letters: true
    private fun lettersOfWords(input: String): BooleanArray? {
        var marks: BooleanArray? = null
        val mark = { at: Int, length: Int ->
            val m = marks ?: BooleanArray(input.length).also { marks = it }
            for (i in at until at + length) m[i] = true
        }
        for (at in input.indices) {
            latin?.forEachAt(input, at) { mark(at, it) }
            userLatin?.forEachAt(input, at) { mark(at, it) }
        }
        return marks
    }

    /** Adds the edges leaving [at]: a walk down the spelling trie along the input. */
    private fun walk(input: String, at: Int, edges: GraphEdges, slips: Boolean, touches: Array<Touch?>?) {
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
                index.slip(node)?.let { slipped(input, at, length, it, full, touches, edges) }
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

    /**
     * Adds the edge of [m], the syllables the [length] keys at [at] spell with one of them slipped,
     * those [full] (what they spell as typed) has left out: each as [SlipCost] weighs where its
     * slipped key was tapped, the key it is read as one of those around it; with no place, the
     * 0.6.0 rule (a key next to it on QWERTY, [KeyNeighbours], and only keys that spell nothing).
     * Keys that spell a syllable as typed, or are consonants alone (简拼), are legal (§4.5).
     */
    private fun slipped(input: String, at: Int, length: Int, m: SyllableMatches, full: SyllableMatches?, touches: Array<Touch?>?, edges: GraphEdges) {
        var legal = full != null
        if (!legal) legal = (at until at + length).none { input[it] in VOWELS }
        var ids: IntArray? = null
        var flags: IntArray? = null
        var n = 0
        val slips = Slips.Builder()
        for (i in 0 until m.size) {
            val s = m.syllable(i)
            if (full != null && full.indexOf(s) >= 0 || !slip(input, at, length, Syllables.spelling(s), legal, touches, slips)) continue
            if (ids == null || flags == null) {
                ids = IntArray(m.size)
                flags = IntArray(m.size)
            }
            ids[n] = s
            flags[n++] = m.flags(i)
        }
        if (ids == null || flags == null) return
        edges.add(at, length, Kind.SYLLABLE, SyllableMatches(ids.copyOf(n), flags.copyOf(n)), slip = slips.build())
    }

    // the likeliest slip by which the [length] keys at [at] spell [spelling], added to [slips]; false where none is read
    private fun slip(input: String, at: Int, length: Int, spelling: String, legal: Boolean, touches: Array<Touch?>?, slips: Slips.Builder): Boolean {
        var best = Float.NaN
        var bestAt = -1
        var bestMeant = ' '
        // lüe is spelt lve and lue alike (SpellingIndex.finals)
        for (ue in if (spelling.endsWith("ve")) BOTH else ONE) {
            val k = oneOff(input, at, length, spelling, ue)
            if (k < 0) continue
            val meant = if (ue && k == length - 2) 'u' else spelling[k]
            val touch = touches?.getOrNull(at + k)
            val c = when {
                touch != null -> touch.near(meant)?.let { slipCost.of(touch, it, legal) } ?: Float.NaN
                legal || meant !in KeyNeighbours.of(input[at + k]) -> Float.NaN
                else -> slipCost.fixed
            }
            if (!c.isNaN() && (best.isNaN() || c > best)) {
                best = c
                bestAt = k
                bestMeant = meant
            }
        }
        if (!best.isNaN()) slips.add(best, bestAt, bestMeant)
        return !best.isNaN()
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

        val ONE = booleanArrayOf(false)
        val BOTH = booleanArrayOf(false, true)

        /**
         * Where the [length] keys of [input] at [at] differ from [spelling] (its final ve as ue
         * where [ue]) at one letter alone: that place; -1 where they differ at none or more.
         */
        fun oneOff(input: String, at: Int, length: Int, spelling: String, ue: Boolean): Int {
            if (spelling.length != length) return -1
            var diff = -1
            for (k in 0 until length) {
                val want = if (ue && k == length - 2) 'u' else spelling[k]
                if (input[at + k] == want) continue
                if (diff >= 0) return -1
                diff = k
            }
            return diff
        }

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
