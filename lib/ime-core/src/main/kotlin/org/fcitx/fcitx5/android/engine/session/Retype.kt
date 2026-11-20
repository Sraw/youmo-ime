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
 * A commit typed again for those deleted before it: each deleted one takes the next of the commit's units as its
 * kind's [Matcher] reads them, learned under its code where the word before is the same (dl typed 打了, deleted,
 * then dianlema typed: 点了 corrected to under dl).
 */
internal object Retype {

    /** A commit as units, a character each, with its syllable (pinyin only) and the word before word starts; [whole]: all of it is units. */
    class Units(val texts: List<String>, val syllables: IntArray, val wordBefore: Map<Int, String>, val whole: Boolean) {
        val size get() = texts.size
    }

    /** How many of [Units] from a place a deleted commit's retyping takes, or null. */
    fun interface Matcher {
        fun consume(units: Units, from: Int): Int?
    }

    /** The rows added, how many deleted commits were typed again, and whether the next waits for the commit after. */
    class Match(val rows: List<Row>, val typedAgain: Int, val goesOn: Boolean)

    /** [units] typed again for [deleted], each by its matcher (none ends the run); a row for each with the same word before, unless the commit's own [rows] already have it. */
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
            val reading = if (units.syllables.isEmpty()) "" else Syllables.spelled(units.syllables, at, at + n)
            val own = rows.any { it.code == whole.code && it.prev == whole.prev && it.words.text == text && it.words.reading == reading }
            if ((units.wordBefore[at] ?: last) == whole.prev && !own) out += row(whole, Words(listOf(Word(text, reading))), origin)
            last = text
            at += n
            matched++
        }
        return Match(out, matched, goesOn = false)
    }

    // corrected as against the first shown then, else what was committed; not WHOLE: no second use nor a pair
    private fun row(whole: Row, words: Words, origin: (Words) -> Int): Row {
        val reference = whole.shown.ifEmpty { whole.words.text }
        val corrected = if (words.text != reference) Row.CORRECTED else 0
        return Row(Row.PICK or corrected, whole.code, whole.prev, words, 'B', origin(words), reference)
    }

    /**
     * A handwriting commit's [units] typed again for [deleted]: each pick row's character taken where [inCandidates]
     * has it among its ink's readings, the run ending at the first it does not; no word before is compared. A unit
     * run ending inside a deleted commit leaves its rest for the next commit ([Keyed.skip]). [written]: a deleted
     * commit's own characters, the pick rows after are its corrections of an earlier commit.
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

    /** What [matchKeyed] typed again, and how many pick rows of the next deleted were typed already. */
    class Keyed(val match: Match, val skip: Int)

    /**
     * Pinyin's matcher over the deleted commit's keys: whole syllables are not read by 简拼 in the middle (`xm` takes
     * 先, not 下面) nor slipped (`ta` takes 他, not 呀), as HabitOrder keeps there. So a delete and the same keys again
     * never corrects to such a word; it is learned when picked, as any pick.
     */
    fun byReading(graph: SyllableGraph): Matcher {
        val whole = graph.hasOtherReadings && graph.readsWhole(graph.start, graph.end)
        return Matcher { units, from ->
            fun go(place: Int, k: Int): Int? {
                if (place == graph.end) return (k - from).takeIf { it > 0 }
                if (k == units.syllables.size) return null
                for (e in graph.edges(place)) {
                    if (graph.kind(e) == Kind.RAW || whole && graph.readsOtherwise(e)) continue
                    val m = graph.matches(e)
                    if ((0 until m.size).any { m.syllable(it) == units.syllables[k] }) go(graph.to(e), k + 1)?.let { return it }
                }
                return null
            }
            go(graph.start, from)
        }
    }

    /** A pinyin commit's [picks] as units: its characters, a syllable each, as far as its words are Han read so. */
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

    private fun syllablesOf(w: Word): List<Int>? {
        if (w.reading.isEmpty() || !Han.all(w.text)) return null
        val ids = w.reading.split(' ').map(Syllables::id)
        return ids.takeIf { ids.none { it < 0 } && ids.size == w.text.codePointCount(0, w.text.length) }
    }
}
