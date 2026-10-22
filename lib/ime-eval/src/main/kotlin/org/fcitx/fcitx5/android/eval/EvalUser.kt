/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.LogHabits
import org.fcitx.fcitx5.android.engine.learn.Row
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.user.HabitCounts
import org.fcitx.fcitx5.android.engine.user.UserModel
import java.io.File

/**
 * A user learning as the app's does (impl/design-1b.md §5): a learning record ([log], in memory
 * or in [file]), its [habits] the sessions record into and rank by, and the user [model] the
 * decoder reads, its own words and counts kept from the record as `Engines` keeps them. Its
 * [clock] is the eval's: a typing day passes when [nextDay] is called, never by itself.
 */
class EvalUser(data: PinyinData, file: File? = null) {
    val clock = StepClock()
    val model = UserModel(data.dictionary, data.vocabulary, index = data.wordIndex)
    val log = LearnLog(file, clock, foldDaily = false).apply { open() }
    val habits = LogHabits({ log })
    private val counts = HabitCounts(model).also { it.attach(log.state) }

    init {
        log.listener = { counts.apply(log.state, it) }
    }

    /** Imports [words] as a batch of their own (U-101), as `Engines` does a list: those the model or the user has left out. How many went in. */
    fun import(words: List<ImportedWords.Incoming>): Int {
        val state = log.state
        // as ImportLayer.import and UserLexicon.kept leave words out: imported already, a word already
        // (nothing is blocked here: no step blocks a word)
        fun known(text: String, reading: String): Boolean {
            if (text in state.mine || state.imports.has(text, reading) || model.knowsText(text)) return true
            // a reading not of syllables cannot be typed: left out as a known one is
            val entry = HabitCounts.entry(text, reading) ?: return true
            return model.origin(entry) != Row.NONE || model.knows(entry)
        }
        val records = ImportedWords.plan(state.imports.lastId + 1, "learn-ux", state.localDay, words, ::known)
        log.imports(records)
        return (records.size - 1).coerceAtLeast(0)
    }

    /** Undoes the last batch imported (U-102); false if there is none. */
    fun unimport(): Boolean {
        val last = log.state.imports.batches.lastOrNull() ?: return false
        log.imports(listOf(LearnRecord.ImportUndo(last.id)))
        return true
    }

    /** The words added by hand, in the order added: the app's word list (UserLexicon.addedTexts). */
    val added = LinkedHashSet<String>()

    /** [text], read [reading] (syllables apart by spaces), added by hand as the word list adds it. */
    fun add(text: String, reading: String) {
        model.listByHand(UserModel.Entry(text, reading.split(' ').map(Syllables::id).toIntArray()))
        added += text
    }

    /** How a session of [kind] learns into [habits]. */
    fun learnTo(kind: String = Kinds.FULL) = LearnTo(habits, kind)

    /** A typing day passes: the next commit is on the next. */
    fun nextDay() = clock.nextDay()
}

/** A clock standing still till [nextDay] moves it a calendar day on, in UTC. */
class StepClock(private var at: Long = START) : LearnClock {
    override fun now() = at
    override fun offset(at: Long) = 0

    fun nextDay() {
        at += LearnClock.DAY
    }

    companion object {
        /** 2026-10-01 12:00 UTC: midday, so no stamp is near a calendar day's end. */
        const val START = 1_790_856_000_000L
    }
}
