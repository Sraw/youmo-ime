/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableWordRow
import org.fcitx.fcitx5.android.engine.learn.LearnState
import java.util.TreeMap

/** The phrases [im]'s table saved in an older build (自动造词 is gone), as the record keeps them: those the table lacks, in saved order; reread as [LearnState.tableWordsVersion] changes. */
class SavedPhrases(
    private val table: TableDictionary,
    private val im: String,
    private val state: () -> LearnState?,
    private val remove: (List<TableWordRow>) -> Unit = {},
) {
    // in code order, so the codes a prefix starts are a range
    private var saved = TreeMap<String, MutableList<String>>()

    // by id, not holding the state; two fields, not a pair: asked at every word a code key shows
    private var readId = -1L
    private var readVersion = -1L

    private fun saved(): TreeMap<String, MutableList<String>> {
        val s = state() ?: return EMPTY
        if (s.id == readId && s.tableWordsVersion == readVersion) return saved
        readId = s.id
        readVersion = s.tableWordsVersion
        saved = TreeMap()
        for ((im, code, text) in s.tableWords.keys) {
            if (im != this.im || code.isEmpty() || text.isEmpty()) continue
            if (table.indexOf(code, text) < 0) saved.getOrPut(code) { ArrayList() } += text
        }
        return saved
    }

    fun isSaved(code: String, text: String): Boolean = saved()[code]?.contains(text) == true

    fun saved(prefix: String): List<Pair<String, String>> =
        savedUnder(prefix).flatMap { (code, texts) -> texts.map { code to it } }

    fun leadsAnywhere(prefix: String) = savedUnder(prefix).isNotEmpty()

    private fun savedUnder(prefix: String): Map<String, List<String>> =
        saved().let { if (prefix.isEmpty()) it else it.subMap(prefix, true, prefix + Char.MAX_VALUE, false) }

    fun forget(code: String, text: String) {
        val s = state() ?: return
        val since = s.tableWords[Triple(im, code, text)] ?: return
        remove(listOf(TableWordRow(im, code, text, since)))
    }

    private companion object {
        val EMPTY = TreeMap<String, MutableList<String>>()
    }
}
