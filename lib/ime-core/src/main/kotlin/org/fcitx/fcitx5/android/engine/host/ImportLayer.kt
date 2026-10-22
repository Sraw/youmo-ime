/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.user.UserModel

/**
 * The imported words for [Engines] (U-101, U-102, PLAN §2.9): batches written to the learning
 * record ([log]), which the user model reads them from (`HabitCounts.attach`), listed and undone
 * for the settings. [user] and [blocked] tell which are the user's or blocked by now, not
 * imported any more as they see it. A change the sessions must see drops them ([dropSessions]).
 * Run on the fcitx thread, as [Engines] is.
 */
class ImportLayer internal constructor(
    private val log: () -> LearnLog,
    private val user: () -> UserModel,
    private val blocked: (String) -> Boolean,
    private val dropSessions: () -> Unit,
    private val clock: LearnClock = LearnClock.System,
) {
    /** A batch: the [file] it came from, the calendar day it came in on (days since the epoch), its words, those of them not the user's yet. */
    data class Batch(val id: Int, val file: String, val localDay: Int, val count: Int, val left: Int)

    /** A word only imported, not the user's yet: its [pinyin] syllables apart by spaces, the [batch] it came in. */
    data class Word(val text: String, val pinyin: String, val batch: Int)

    /**
     * Writes [words] of [file] as a new batch, but those [known] says are words already, and
     * those the user owns: how many went in.
     */
    internal fun import(file: String, words: List<ImportedWords.Incoming>, known: (String, String) -> Boolean): Int {
        val log = log()
        val state = log.state
        val day = LearnClock.localDay(clock, LearnClock.floorDiv(clock.now(), LearnClock.MINUTE).toInt())
        val records = ImportedWords.plan(state.imports.lastId + 1, file, day, words) { text, reading ->
            text in state.mine || state.imports.has(text, reading) || known(text, reading)
        }
        if (records.isEmpty()) return 0
        log.imports(records)
        // the words, not the batch's head before them
        return records.count { it is LearnRecord.Imported }
    }

    /** The batches, oldest first. */
    fun batches(): List<Batch> {
        val left = HashMap<Int, Int>()
        for (p in only(null)) left.merge(log().state.imports.batch(p), 1, Int::plus)
        return log().state.imports.batches.map { Batch(it.id, it.file, it.localDay, it.count, left[it.id] ?: 0) }
    }

    /**
     * The words only imported, of [batch] if given, in the order of their import: what 来自导入
     * filters (U-62, U-80, 「还不是我的词」): not the user's own, added by hand, listed or blocked.
     */
    fun words(batch: Int? = null): List<Word> {
        val imports = log().state.imports
        return only(batch).sortedBy(imports::order).map { Word(imports.text(it), imports.reading(it), imports.batch(it)) }
    }

    // the places of the words imported and not the user's, nor blocked
    private fun only(batch: Int?): List<Int> {
        val state = log().state
        val imports = state.imports
        val model = user()
        return imports.places(batch).filter { p ->
            val text = imports.text(p)
            text !in state.mine && !blocked(text) && model.isImported(UserModel.Entry(text, imports.syllables(p)))
        }
    }

    /**
     * Undoes [batch] (U-102): its words go, those the user owns staying theirs. How many went that
     * were not the user's, what they see go; null if there is no such batch.
     */
    fun drop(batch: Int): Int? {
        val log = log()
        if (log.state.imports.batches.none { it.id == batch }) return null
        val count = only(batch).size
        log.imports(listOf(LearnRecord.ImportUndo(batch)))
        dropSessions()
        return count
    }

    /** Puts back what [drop] took of [batch] (撤销): false if it cannot be, the record read afresh or rewritten since (a fold, a start, 全部清空: F2811). */
    fun restore(batch: Int): Boolean {
        val records = log().state.imports.restore(batch)
        if (records.isEmpty()) return false
        log().imports(records)
        dropSessions()
        return true
    }
}
