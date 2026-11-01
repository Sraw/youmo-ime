/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableWordRow
import org.fcitx.fcitx5.android.engine.learn.LearnState
import java.util.TreeMap

/**
 * The phrases [im]'s table saved in an older build (自动造词 is gone), as the learning record
 * keeps them ([LearnState.tableWords], [state]'s; null: none, habits off): those the table lacks,
 * its own entry standing for one a later table has, in the order they were saved. 0.3.0's picks of
 * what the table has not, which 0.4 converted to counts alone (StatRow) but offered from its table's
 * log, were put in from that log once (Engines.keepOlderTablePhrases). Read again as the record
 * changes them ([LearnState.tableWordsVersion]).
 * [forget] takes one out through [remove], which writes it to the record.
 */
class SavedPhrases(
    private val table: TableDictionary,
    private val im: String,
    private val state: () -> LearnState?,
    private val remove: (List<TableWordRow>) -> Unit = {},
) {
    // in code order, so that the codes a prefix starts are a range of them
    private var saved = TreeMap<String, MutableList<String>>()

    // the state and its version [saved] was read from: by its id, not holding it (F3216)
    // two fields, not a pair: asked at every word a code key shows
    private var readId = -1L
    private var readVersion = -1L

    private fun saved(): TreeMap<String, MutableList<String>> {
        val s = state() ?: return EMPTY
        if (s.id == readId && s.tableWordsVersion == readVersion) return saved
        readId = s.id
        readVersion = s.tableWordsVersion
        saved = TreeMap()
        // in the record's order, the order saved: within a code as a table's log had them
        for ((im, code, text) in s.tableWords.keys) {
            if (im != this.im || code.isEmpty() || text.isEmpty()) continue
            if (table.indexOf(code, text) < 0) saved.getOrPut(code) { ArrayList() } += text
        }
        return saved
    }

    fun isSaved(code: String, text: String): Boolean = saved()[code]?.contains(text) == true

    /** The saved phrases whose code starts with [prefix], as code and text, in code order. */
    fun saved(prefix: String): List<Pair<String, String>> =
        savedUnder(prefix).flatMap { (code, texts) -> texts.map { code to it } }

    /** Whether some saved phrase has a code starting with [prefix]. */
    fun leadsAnywhere(prefix: String) = savedUnder(prefix).isNotEmpty()

    private fun savedUnder(prefix: String): Map<String, List<String>> =
        saved().let { if (prefix.isEmpty()) it else it.subMap(prefix, true, prefix + Char.MAX_VALUE, false) }

    /** Forgets the phrase [text] under [code], as the user asked. */
    fun forget(code: String, text: String) {
        val s = state() ?: return
        val since = s.tableWords[Triple(im, code, text)] ?: return
        remove(listOf(TableWordRow(im, code, text, since)))
    }

    private companion object {
        val EMPTY = TreeMap<String, MutableList<String>>()
    }
}
