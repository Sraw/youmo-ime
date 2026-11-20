/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Companion.SEPARATOR
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind

/**
 * Cuts full pinyin into a [SyllableGraph]. Unlike libime's longest match with special cases, it keeps
 * every cut and lets the language model choose; edges only go forward and syllables are at most six
 * letters, so the graph stays small. Per position an edge for: every syllable spelt there (`xian` and
 * `xi`); with `neighbours`, syllables spelt with one letter slipped ([SlipCost]); an initial alone (简拼),
 * also before a consonant (`ng` for 那个) but not a vowel; syllables still being typed at the end or
 * before a separator; failing all, one character as typed.
 *
 * Upper-case letters are Latin-letter syllables, so lower-case auto-capitalised input first. With
 * [latin] / [userLatin], letters of a Latin word typed (`iphone`) also get [Kind.LETTER] edges.
 */
class PinyinSegmenter(
    fuzzy: Set<Fuzzy> = emptySet(),
    typos: Boolean = true,
    neighbours: Boolean = false,
    private val latin: LatinWords? = null,
    private val userLatin: UserLatinWords? = null,
    private val slipCost: SlipCost = SLIP,
) : Segmenter {

    // the settings' own, shared by sessions made with them (SettingsMemo)
    private val index = SpellingIndex.of(fuzzy, typos, neighbours)

    /** A slip's cost with no touch place ([SlipCost.fixed]); printed by ime-eval's tune. */
    val slipFixed: Float get() = slipCost.fixed

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

    // true at each letter of a typed Latin word
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
            // no syllable for the rules below: an initial's reading goes beside a slip; nor where the text may still
            // become one (`zho` toward zhong is no zhi)
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

    /** Adds an edge of [m], syllables the keys at [at] spell with one slipped that [full] lacks, priced by tap place ([SlipCost]); without a place, only QWERTY neighbours of keys that spell nothing. */
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

    // the likeliest slip of the keys at [at] onto [spelling], added to [slips]; false if none
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

    private fun growing(input: String, end: Int, node: Int, spells: Boolean): Boolean {
        if (!spells) return false
        if (mayGoOn(input, end, true)) return true
        val next = index.child(node, input[end])
        return next >= 0 && (index.matches(next) != null || index.initial(next) != null || index.extensions(next) != null)
    }

    private companion object {
        const val VOWELS = "aeiouv"

        // the loosest floor that turns no on-target sentence wrong (σ 0.15); over -2 `deo` read 得. A slip that also
        // spells as typed costs -2 more, so none is read
        val SLIP = SlipCost(floor = -1.75f)

        val ONE = booleanArrayOf(false)
        val BOTH = booleanArrayOf(false, true)

        /** The one place where the keys at [at] differ from [spelling] (ve as ue where [ue]); -1 if none or more. */
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

        /** What precedes a vowel starts a syllable, not an initial alone. */
        fun startsSyllable(input: String, at: Int) = at < input.length && input[at] in VOWELS

        fun beforeConsonant(input: String, at: Int, length: Int) =
            input[at] !in VOWELS && input[at + length] !in VOWELS

        /** Not only a slip: a syllable as typed, or its fuzzy partner. */
        fun SyllableMatches.anyWhole() = (0 until size).any { flags(it) and SyllableMatches.TYPO == 0 }

        /** Text ending at [at] may still grow: at the input's end, or before a separator if [unfinished]. */
        fun mayGoOn(input: String, at: Int, unfinished: Boolean) =
            at == input.length || unfinished && input[at] == SEPARATOR
    }
}
