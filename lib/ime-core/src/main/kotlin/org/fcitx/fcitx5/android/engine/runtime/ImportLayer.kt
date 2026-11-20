/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.learn.ImportedWords
import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmClock
import org.fcitx.fcitx5.android.engine.user.UserModel

/** Imported word batches in the learning record ([log]), read by the user model (`HabitCounts.attach`), listed and undone for the settings; [user] and [blocked] tell which are no longer only imported. Changes drop the sessions. On the fcitx thread. */
class ImportLayer internal constructor(
    private val log: () -> LearnLog,
    private val user: () -> UserModel,
    private val blocked: (String) -> Boolean,
    private val dropSessions: () -> Unit,
    private val clock: LearnClock = JvmClock,
) {
    /** [localDay]: days since the epoch; [left]: words not the user's yet. */
    data class Batch(val id: Int, val file: String, val localDay: Int, val count: Int, val left: Int)

    /** A word only imported; [pinyin] space-separated. */
    data class Word(val text: String, val pinyin: String, val batch: Int)

    /** Writes a new batch, skipping words [known] already and the user's own: how many went in. */
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

    /** Oldest first. */
    fun batches(): List<Batch> {
        val left = HashMap<Int, Int>()
        for (p in only(null)) left.merge(log().state.imports.batch(p), 1, Int::plus)
        return log().state.imports.batches.map { Batch(it.id, it.file, it.localDay, it.count, left[it.id] ?: 0) }
    }

    /** Words only imported, of [batch] if given, in import order (来自导入, 「还不是我的词」): not added, listed or blocked by the user. */
    fun words(batch: Int? = null): List<Word> {
        val imports = log().state.imports
        return only(batch).sortedBy(imports::order).map { Word(imports.text(it), imports.reading(it), imports.batch(it)) }
    }

    // positions of imported words not the user's nor blocked
    private fun only(batch: Int?): List<Int> {
        val state = log().state
        val imports = state.imports
        val model = user()
        return imports.places(batch).filter { p ->
            val text = imports.text(p)
            text !in state.mine && !blocked(text) && model.isImported(UserModel.Entry(text, imports.syllables(p)))
        }
    }

    /** Undoes [batch], the user's own words staying: how many non-user words went; null for no such batch. */
    fun drop(batch: Int): Int? {
        val log = log()
        if (log.state.imports.batches.none { it.id == batch }) return null
        val count = only(batch).size
        log.imports(listOf(LearnRecord.ImportUndo(batch)))
        dropSessions()
        return count
    }

    /** 撤销 of [drop]: false if the record was reread or rewritten since (a fold, a start, 全部清空). */
    fun restore(batch: Int): Boolean {
        val records = log().state.imports.restore(batch)
        if (records.isEmpty()) return false
        log().imports(records)
        dropSessions()
        return true
    }
}
