/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.data.NgramModel.Companion.NO_WORD
import org.fcitx.fcitx5.android.engine.lattice.Predictor
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.Word
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry

/**
 * The counts [user]'s scores and 联想 go by, from the learning record: a word's uses in the last 30 typing days; a
 * pair's count capped at the word before's uses; 联想's pairs; the whole phrases used often enough. Keyed by model
 * ids. A word of no reading (a table's, handwriting's) is not counted, so those never change pinyin's order, but
 * it still counts as the word before a pair, for 联想; a pair to it is 联想's alone.
 *
 * [rebuild] remakes all (a forget, an undo, a new typing day: [settle]); [committed] adds one commit. [attach] and
 * [apply] keep the user's own words in [user] as the record has them.
 */
class HabitCounts(private val user: UserModel) {

    // ids by text and reading while the model's words are the same: finding them was most of a rebuild's time
    private var idsAt = -1
    private val ids = HashMap<String, HashMap<String, Int>>()
    private val textIds = HashMap<String, List<Int>>()

    // 联想's counts by a pair's texts, each reading apart: 联想 offers a text whatever its reading, so they are summed
    private val predictedCounts = HashMap<Pair<String, String>, HashMap<String, Int>>()

    private fun idsNow() {
        if (idsAt == user.idsChanged) return
        val changed = user.textsChangedSince(idsAt)
        if (changed == null) {
            ids.clear()
            textIds.clear()
        } else {
            for (text in changed) {
                ids.remove(text)
                textIds.remove(text)
            }
        }
        idsAt = user.idsChanged
    }

    fun attach(state: LearnState) {
        // imports first: a word the user owns by now is owned as imported, one id
        user.attachImports(state.imports)
        for ((text, mine) in state.mine) entry(text, mine.reading)?.let(user::own)
        rebuild(state)
    }

    fun apply(state: LearnState, changes: LearnState.Changes) {
        if (changes.imports) user.importsChanged()
        for ((text, reading) in changes.removed) entry(text, reading)?.let(user::disown)
        for ((text, reading) in changes.formed) entry(text, reading)?.let(user::own)
        if (changes.recount || changes.removed.isNotEmpty()) return rebuild(state)
        if (changes.pins) pinned(state)
        // a new typing day: counts are remade as a field starts ([settle]), not on the key that began the day (4–10 ms
        // on a desktop for a heavy user, more on a phone)
        if (changes.window) stale = true
        changes.commits.forEach { committed(state, it) }
        // a word formed has an id now, and its uses before were counted under none
        if (changes.formed.isNotEmpty()) formed(state, changes.formed.keys)
    }

    // forming a word changes no uses: only its own count and its pairs move
    private fun formed(state: LearnState, texts: Set<String>) {
        val from = from(state)
        for (text in texts) count(state, text, from)
        for ((key, c) in state.pairs) if (key.prev in texts || key.next in texts) pair(state, key, c.count)
    }

    var stale = false
        private set

    fun settle(state: LearnState) {
        if (stale) rebuild(state)
    }

    fun rebuild(state: LearnState) {
        stale = false
        user.clearCounts()
        predictedCounts.clear()
        val from = from(state)
        val counted = HashMap<Int, Float>()
        val read = HashMap<Int, Entry>()
        // 简拼 in the middle of 双拼 reads a word typed at each of its readings ([TypedNodes.alsoRead])
        val also = ArrayList<Pair<Int, Entry>>()
        for ((text, readings) in state.wordUses) {
            for ((reading, uses) in readings) {
                // a table's or handwriting's, of no reading: never pinyin's to count
                val n = if (reading.isEmpty()) 0 else uses.sumFrom(from)
                val id = if (n > 0) id(state, text, reading) else NO_WORD
                if (id == NO_WORD) continue
                counted[id] = (counted[id] ?: 0f) + n
                if (id !in read) entry(text, reading)?.let { read[id] = it } else entry(text, reading)?.let { also += id to it }
            }
        }
        for ((id, n) in counted) user.setCount(id, n, read[id])
        for ((id, entry) in also) user.typedNodes.alsoRead(id, entry.syllables)
        for ((key, c) in state.pairs) pair(state, key, c.count)
        user.continuations.clear()
        for ((text, c) in state.phrases) phrase(text, c)
        pinned(state)
    }

    // pinned words count as typed at their pinned readings for good
    private fun pinned(state: LearnState) {
        val words = HashMap<Int, ArrayList<IntArray>>()
        for (pin in state.pins.values) {
            val entry = entry(pin.text, pin.reading) ?: continue
            val id = id(state, pin.text, pin.reading)
            if (id != NO_WORD) words.getOrPut(id) { ArrayList(1) } += entry.syllables
        }
        user.setPinned(words)
    }

    private fun phrase(text: String, c: LearnState.Counted?) = user.continuations.set(text, c?.count ?: 0, c?.lastDay ?: 0, Predictor.PAIRS)

    /** What [commit] used, as [rebuild] would count it; a pair held to its word's old count stays so till the next rebuild (the count only grew). */
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
        for (row in commit.rows) if (row.whole) phrase(row.words.text, state.phrases[row.words.text])
        if (commit.continued.isNotEmpty()) phrase(commit.continued, state.phrases[commit.continued])
    }

    private fun count(state: LearnState, text: String, from: Int) {
        val readings = state.wordUses[text] ?: return
        val counted = HashMap<Int, Float>()
        val read = HashMap<Int, Entry>()
        val also = ArrayList<Pair<Int, Entry>>()
        for ((reading, uses) in readings) {
            // a table's or handwriting's, of no reading: never pinyin's to count
            val id = if (reading.isEmpty()) NO_WORD else id(state, text, reading)
            if (id == NO_WORD) continue
            val n = uses.sumFrom(from)
            counted[id] = (counted[id] ?: 0f) + n
            // a reading not used since is none the word was typed at: 了 typed as le is not read liao by a key alone
            if (n > 0) entry(text, reading)?.let { if (id !in read) read[id] = it else also += id to it }
        }
        for ((id, n) in counted) user.setCount(id, n, read[id])
        for ((id, entry) in also) user.typedNodes.alsoRead(id, entry.syllables)
    }

    private fun from(state: LearnState) = state.day - LearnState.WINDOW + 1

    // a pair to each id of the word before: 联想's as counted; the decoder's held to that word's count. To a word of
    // no reading it is 联想's alone
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

    /** The model's id of [text] read [reading]; for no reading, the model's word of its text else the user's; [NO_WORD] for none. */
    private fun id(state: LearnState, text: String, reading: String): Int {
        idsNow()
        val byReading = ids.getOrPut(text) { HashMap(2) }
        // no reading: the user's own words count too, which change as [UserModel.idsChanged] goes up
        return byReading[reading] ?: find(state, text, reading).also { byReading[reading] = it }
    }

    private fun find(state: LearnState, text: String, reading: String): Int {
        if (reading.isNotEmpty()) return entry(text, reading)?.let(user::known) ?: NO_WORD
        user.idsOfText(text).firstOrNull()?.let { return it }
        return state.mine[text]?.let { entry(text, it.reading) }?.let(user::known) ?: NO_WORD
    }

    companion object {
        /** [text] read [reading] as the model has words; null if a syllable is none of this build's. */
        fun entry(text: String, reading: String): Entry? {
            if (text.isEmpty() || reading.isEmpty()) return null
            val syllables = reading.split(' ').map(Syllables::id)
            return if (syllables.any { it < 0 }) null else Entry(text, syllables.toIntArray())
        }

        fun entry(word: Word): Entry? = entry(word.text, word.reading)
    }
}
