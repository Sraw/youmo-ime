/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.Word
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry

/**
 * The counts [user]'s scores and 联想 go by, as the learning record has them (impl/design-1b.md
 * §4): a word's, the whole commits' uses of it in the last 30 typing days ([LearnState.wordUses]);
 * a pair's, its count ([LearnState.pairs]) held to the uses of the word before it, so a pair
 * never says more of a word than the word's own count does; 联想's pairs as counted
 * ([UserModel.setPredicted]). Keyed by the model's ids: a word with
 * none (pending, not a word yet) is not counted. A word of no reading (a table's, handwriting's)
 * is not counted at all: what those choose does not change pinyin's order (U-28 D28, U-29); it
 * still says how often the word before a pair was used, so what follows it is learned for 联想; a
 * pair to it is 联想's alone, never the decoder's ([UserModel.decoderProbability]).
 * The word a pair goes on from and a word of no reading a pair goes to are found by their text:
 * the model's word of it, else the user's word of it; a word before of several readings gives its
 * pairs to each.
 *
 * [rebuild] makes them all again (a forget, an undo; a new typing day as a field starts: [settle]);
 * [committed] adds what one commit used, and words formed alone are counted with the pairs they
 * are in (review-1b S1). [attach] and [apply] keep the user's own words in [user] too, as the
 * record has them: one function for what every record changed (impl/design-1b.md §3, NIT 1).
 */
class HabitCounts(private val user: UserModel) {

    // the model's ids by text and reading ("" for a text's), while its words are the same
    // ([UserModel.idsChanged]): finding them was most of a rebuild's time (review-1b S1)
    private var idsAt = -1
    private val ids = HashMap<String, HashMap<String, Int>>()
    private val textIds = HashMap<String, List<Int>>()

    // 联想's counts by the texts of a pair, each reading's apart ("" for none): 联想 offers a text,
    // whatever it was read as, so they are summed
    private val predictedCounts = HashMap<Pair<String, String>, HashMap<String, Int>>()

    private fun idsNow() {
        if (idsAt == user.idsChanged) return
        val changed = user.textsChangedSince(idsAt)
        if (changed == null) {
            ids.clear()
            textIds.clear()
        } else {
            // a word's id changes what its text alone finds
            for (text in changed) {
                ids.remove(text)
                textIds.remove(text)
            }
        }
        idsAt = user.idsChanged
    }

    /** [state]'s words of the user's owned by [user], and the counts made: as the model is made. */
    fun attach(state: LearnState) {
        // the words imported first: one the user owns by now is owned as imported, one id (review-G M5)
        user.attachImports(state.imports)
        for ((text, mine) in state.mine) entry(text, mine.reading)?.let(user::own)
        rebuild(state)
    }

    /** What applying records to [state] [changed][LearnState.Changes]: words owned and disowned, the counts. */
    fun apply(state: LearnState, changes: LearnState.Changes) {
        if (changes.imports) user.importsChanged()
        for ((text, reading) in changes.removed) entry(text, reading)?.let(user::disown)
        for ((text, reading) in changes.formed) entry(text, reading)?.let(user::own)
        if (changes.recount || changes.removed.isNotEmpty()) return rebuild(state)
        // a new typing day: the counts of the day before a while yet, made anew as a field starts
        // ([settle]), not on the key that began the day (review-1b S1: 4 to 10 ms on a desktop's
        // JVM for a heavy user, several times that on a phone); a commit's words are counted anew still
        if (changes.window) stale = true
        changes.commits.forEach { committed(state, it) }
        // a word formed has an id now, and its uses before were counted under none
        if (changes.formed.isNotEmpty()) formed(state, changes.formed.keys)
    }

    // [texts], words formed, counted as [rebuild] would count them, and the pairs they are in: no
    // other count moves, as forming one changes no uses
    private fun formed(state: LearnState, texts: Set<String>) {
        val from = from(state)
        for (text in texts) count(state, text, from)
        for ((key, c) in state.pairs) if (key.prev in texts || key.next in texts) pair(state, key, c.count)
    }

    /** Whether the counts wait to be made anew: the typing days moved on ([LearnState.Changes.window]). */
    var stale = false
        private set

    /** The counts anew from [state] if they wait to be: as a field starts, before its first key. */
    fun settle(state: LearnState) {
        if (stale) rebuild(state)
    }

    /** Every count anew from [state]. */
    fun rebuild(state: LearnState) {
        stale = false
        user.clearCounts()
        predictedCounts.clear()
        val from = from(state)
        val counted = HashMap<Int, Float>()
        val read = HashMap<Int, Entry>()
        for ((text, readings) in state.wordUses) {
            for ((reading, uses) in readings) {
                // a table's or handwriting's, of no reading: never pinyin's to count (D28)
                val n = if (reading.isEmpty()) 0 else uses.sumFrom(from)
                val id = if (n > 0) id(state, text, reading) else NO_WORD
                if (id == NO_WORD) continue
                counted[id] = (counted[id] ?: 0f) + n
                if (id !in read) entry(text, reading)?.let { read[id] = it }
            }
        }
        for ((id, n) in counted) user.setCount(id, n, read[id])
        for ((key, c) in state.pairs) pair(state, key, c.count)
    }

    /**
     * What [commit], applied to [state], used: its words' counts and its pairs, as [rebuild] would
     * have them. A pair after one of its words held to that word's count before stays so till the
     * next rebuild: the word's count only grew, and seldom past the pair's.
     */
    fun committed(state: LearnState, commit: Commit) {
        val from = from(state)
        val touched = LinkedHashSet<String>()
        val pairs = LinkedHashSet<LearnState.PairKey>()
        for (row in commit.rows) {
            if (!row.whole) continue
            state.countedWords(row, commit).forEach { touched += it.text }
            var prev = row.prev
            for (w in LearnState.heard(row)) {
                if (prev.isNotEmpty()) pairs += LearnState.PairKey(prev, w.text, w.reading)
                prev = w.text
            }
        }
        for (text in touched) count(state, text, from)
        for (key in pairs) state.pairs[key]?.let { pair(state, key, it.count) }
    }

    // [text]'s count, its uses since [from] at each of its readings
    private fun count(state: LearnState, text: String, from: Int) {
        val readings = state.wordUses[text] ?: return
        val counted = HashMap<Int, Float>()
        val read = HashMap<Int, Entry>()
        for ((reading, uses) in readings) {
            // a table's or handwriting's, of no reading: never pinyin's to count (D28)
            val id = if (reading.isEmpty()) NO_WORD else id(state, text, reading)
            if (id == NO_WORD) continue
            counted[id] = (counted[id] ?: 0f) + uses.sumFrom(from)
            if (id !in read) entry(text, reading)?.let { read[id] = it }
        }
        for ((id, n) in counted) user.setCount(id, n, read[id])
    }

    private fun from(state: LearnState) = state.day - LearnState.WINDOW + 1

    // a pair to each id of the word before: 联想's as counted, whatever put it in (U-71), to the word
    // of its text; the decoder's held to that word's count, its uses in a table or handwriting too:
    // counted for no word, they still count as its uses for what follows it. To a word of no reading
    // (a table's, handwriting's, 联想's) it is 联想's alone, never the decoder's (PLAN §2.7, review-C S2)
    private fun pair(state: LearnState, key: LearnState.PairKey, count: Int) {
        val predicted = id(state, key.next, "")
        val next = id(state, key.next, key.nextReading)
        if (predicted == NO_WORD && next == NO_WORD) return
        val readings = predictedCounts.getOrPut(key.prev to key.next) { HashMap(2) }
        readings[key.nextReading] = count
        val byText = state.wordUses[key.prev]?.get("")?.sumFrom(from(state)) ?: 0
        val forPrediction = key.nextReading.isEmpty()
        idsNow()
        for (prev in textIds.getOrPut(key.prev) { user.idsOfText(key.prev) }) {
            if (predicted != NO_WORD) user.setPredicted(prev, predicted, readings.values.sum().toFloat())
            if (next != NO_WORD) user.setPair(prev, next, minOf(count.toFloat(), maxOf(user.count(prev), byText.toFloat())), forPrediction)
        }
    }

    /** The model's id of [text] read [reading]; for no reading, the model's word of its text, else the user's own; [NO_WORD] for none. */
    private fun id(state: LearnState, text: String, reading: String): Int {
        idsNow()
        val byReading = ids.getOrPut(text) { HashMap(2) }
        // no reading: by the user's own words too, which change as state.mine does, when [UserModel.idsChanged] goes up
        return byReading[reading] ?: find(state, text, reading).also { byReading[reading] = it }
    }

    private fun find(state: LearnState, text: String, reading: String): Int {
        if (reading.isNotEmpty()) return entry(text, reading)?.let(user::known) ?: NO_WORD
        user.idsOfText(text).firstOrNull()?.let { return it }
        return state.mine[text]?.let { entry(text, it.reading) }?.let(user::known) ?: NO_WORD
    }

    companion object {
        /** [text] read [reading] (syllables apart by spaces) as the model has words; null if a syllable is none of this build's. */
        fun entry(text: String, reading: String): Entry? {
            if (text.isEmpty() || reading.isEmpty()) return null
            val syllables = reading.split(' ').map(Syllables::id)
            return if (syllables.any { it < 0 }) null else Entry(text, syllables.toIntArray())
        }

        /** [word] as an entry: see [entry]. */
        fun entry(word: Word): Entry? = entry(word.text, word.reading)
    }
}
