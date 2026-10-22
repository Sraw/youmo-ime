/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.learn.CommitRows
import org.fcitx.fcitx5.android.engine.learn.Han
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.learn.Word
import org.fcitx.fcitx5.android.engine.learn.Words
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph
import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Kind
import org.fcitx.fcitx5.android.engine.pinyin.Syllables

/**
 * A commit typed again for those deleted before it (U-03, impl/design-1c.md §2): each deleted one
 * in turn takes the next of the commit's units (pinyin's syllables, a table's characters), as its
 * kind's [Matcher] reads them; what it took is learned under its code and word before, where the
 * word before is the same (dl typed 打了, deleted; dianlema typed: 点了 corrected to under dl).
 */
internal object Retype {

    /**
     * A commit as units, a character each: their [texts], what each was read as ([syllables]: a
     * syllable each, for pinyin's matcher; else empty), and the word before those that start a
     * word of it ([wordBefore]). [whole]: all of the commit is units, so it may go on to the next deleted.
     */
    class Units(val texts: List<String>, val syllables: IntArray, val wordBefore: Map<Int, String>, val whole: Boolean) {
        val size get() = texts.size
    }

    /**
     * How a deleted commit is typed again: how many of [Units] from a place it takes, or null if
     * it does not take them. Pinyin's reads syllables ([byReading]); a table's would compare keys,
     * handwriting's the candidates the commit was picked from (U-29), each session its own.
     */
    fun interface Matcher {
        fun consume(units: Units, from: Int): Int?
    }

    /** The rows a commit adds for the deleted it typed again, how many it [typedAgain], and whether the next of them waits for the commit after it ([goesOn]). */
    class Match(val rows: List<Row>, val typedAgain: Int, val goesOn: Boolean)

    /**
     * [units], the commit after [prev], typed again for [deleted] (in the order written), each by
     * [matcherOf] its whole row's (null: none is, a prediction picked, which ends the run). A row
     * for each whose word before is the same, unless the commit's own [rows] have its code, word
     * before and text read so: that is this commit's use there alone (review-design-1c S6).
     */
    fun match(deleted: List<Commit>, prev: String, units: Units, rows: List<Row>, matcherOf: (Row) -> Matcher?, origin: (Words) -> Int): Match {
        val out = ArrayList<Row>()
        var at = 0
        var matched = 0
        var last = prev
        for (k in deleted) {
            if (at == units.size) return Match(out, matched, goesOn = units.whole)
            val whole = k.rows.firstOrNull { it.whole }
            val n = whole?.let(matcherOf)?.consume(units, at)?.takeIf { it > 0 && at + it <= units.size } ?: break
            val text = units.texts.subList(at, at + n).joinToString("")
            val reading = if (units.syllables.isEmpty()) "" else (at until at + n).joinToString(" ") { Syllables.spelling(units.syllables[it]) }
            val own = rows.any { it.code == whole.code && it.prev == whole.prev && it.words.text == text && it.words.reading == reading }
            if ((units.wordBefore[at] ?: last) == whole.prev && !own) out += row(whole, Words(listOf(Word(text, reading))), origin)
            last = text
            at += n
            matched++
        }
        return Match(out, matched, goesOn = false)
    }

    // [words] typed again for [whole]'s commit: corrected as against the first shown then, what it
    // was weighed against (PLAN §2.4), or, not known (several picks), what was committed. Not WHOLE:
    // a use and a correction there, no second use of the word nor a pair
    private fun row(whole: Row, words: Words, origin: (Words) -> Int): Row {
        val reference = whole.shown.ifEmpty { whole.words.text }
        val corrected = if (words.text != reference) Row.CORRECTED else 0
        return Row(Row.PICK or corrected, whole.code, whole.prev, words, 'B', origin(words), reference)
    }

    /**
     * A handwriting commit's characters [units] typed again for [deleted] (in the order written),
     * a character for each of their pick rows in turn, the first [skip] of the first typed again by
     * a commit before (U-03, U-29, F2655): each taken where [inCandidates] has it among what the ink
     * of that row's character was read as, the run ending at the first it does not. No word before is
     * compared (keyed rows have none). A row under the deleted pick's code for each, corrected where
     * the first shown then was another, unless the commit's own [rows] have that code and text. The
     * units ending inside a deleted commit, the rest of it waits for the next commit ([Keyed.skip]).
     * [written]: how many characters a deleted commit wrote, its first pick rows; those after are
     * its corrections of a commit before it (review F3110), no character of its own.
     */
    fun matchKeyed(
        deleted: List<Commit>,
        skip: Int,
        units: List<String>,
        rows: List<Row>,
        inCandidates: (Commit, Int, String) -> Boolean,
        written: (Commit) -> Int = { Int.MAX_VALUE },
    ): Keyed {
        val out = ArrayList<Row>()
        var at = 0
        var matched = 0
        var from = skip
        for (k in deleted) {
            // a prediction picked (one whole row) has none: the run ends there
            val picks = k.rows.filter { it.flags and Row.PICK != 0 && !it.whole }.take(written(k))
            if (picks.isEmpty()) break
            for (i in from until picks.size) {
                if (at == units.size) return Keyed(Match(out, matched, goesOn = true), i)
                val text = units[at]
                if (!inCandidates(k, i, text)) return Keyed(Match(out, matched, goesOn = false), 0)
                val p = picks[i]
                val own = rows.any { it.code == p.code && !it.whole && it.words.text == text }
                if (!own) out += Row(Row.PICK or (if (text != p.shown) Row.CORRECTED else 0), p.code, "", Words.of(text), 'B', Row.DICTIONARY, p.shown)
                at++
            }
            from = 0
            matched++
        }
        return Keyed(Match(out, matched, goesOn = at == units.size), 0)
    }

    /** What [matchKeyed] typed again, and how many pick rows of the next deleted were typed already ([skip]): where the next commit goes on from. */
    class Keyed(val match: Match, val skip: Int)

    /** Pinyin's matcher: [graph], the deleted commit's keys read as syllables, a syllable an edge, taking the units' syllables from its start to its end. */
    fun byReading(graph: SyllableGraph) = Matcher { units, from ->
        fun go(place: Int, k: Int): Int? {
            if (place == graph.end) return (k - from).takeIf { it > 0 }
            if (k == units.syllables.size) return null
            for (e in graph.edges(place)) {
                if (graph.kind(e) == Kind.RAW) continue
                val m = graph.matches(e)
                if ((0 until m.size).any { m.syllable(it) == units.syllables[k] }) go(graph.to(e), k + 1)?.let { return it }
            }
            return null
        }
        go(graph.start, from)
    }

    /** A pinyin commit's [picks] after [prev] as units: its characters, a syllable each, as far as its words are Han read so. */
    fun syllables(prev: String, picks: List<CommitRows.Pick>): Units {
        val syllables = ArrayList<Int>()
        val chars = ArrayList<String>()
        val wordBefore = HashMap<Int, String>()
        var before = prev
        for (w in picks.flatMap { it.words }) {
            val ids = syllablesOf(w) ?: return Units(chars, syllables.toIntArray(), wordBefore, whole = false)
            wordBefore[syllables.size] = before
            var c = 0
            repeat(ids.size) {
                val next = w.text.offsetByCodePoints(c, 1)
                chars += w.text.substring(c, next)
                c = next
            }
            syllables += ids
            before = w.text
        }
        return Units(chars, syllables.toIntArray(), wordBefore, whole = true)
    }

    // [w]'s syllables, a Han character each; null if it is not so read
    private fun syllablesOf(w: Word): List<Int>? {
        if (w.reading.isEmpty() || !Han.all(w.text)) return null
        val ids = w.reading.split(' ').map(Syllables::id)
        return ids.takeIf { ids.none { it < 0 } && ids.size == w.text.codePointCount(0, w.text.length) }
    }
}
