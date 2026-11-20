/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.TableWordRow
import org.fcitx.fcitx5.android.engine.learn.LearnState
import java.util.TreeMap

/** The words [im]'s table remembered (or an older build saved), as the record keeps them: those the table lacks, in saved order; reread as [LearnState.tableWordsVersion] changes. [add] writes one, [remove] drops rows. */
class SavedPhrases(
    private val table: TableDictionary,
    private val im: String,
    private val state: () -> LearnState?,
    private val remove: (List<TableWordRow>) -> Unit = {},
    private val add: (code: String, text: String) -> Unit = { _, _ -> },
) {
    /** A tap's word and the commit that typed it. */
    class Tapped(val text: String, val code: String, val commit: Commit?)

    /** The last [Tapped], kept here past its session: the notice's 撤销 may reach one made anew (an input method switched and back). */
    var tapped: Tapped? = null

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

    /** Those under [code] exactly: a prefix shows none, so typing on it is as before. */
    fun saved(code: String): List<Pair<String, String>> = saved()[code]?.map { code to it }.orEmpty()

    /** Every one, in code order. */
    fun all(): List<Pair<String, String>> = saved().flatMap { (code, texts) -> texts.map { code to it } }

    /** Whether [text] is saved under any code. */
    fun hasText(text: String): Boolean = saved().values.any { text in it }

    fun leadsAnywhere(prefix: String) = savedUnder(prefix).isNotEmpty()

    private fun savedUnder(prefix: String): Map<String, List<String>> =
        saved().let { if (prefix.isEmpty()) it else it.subMap(prefix, true, prefix + Char.MAX_VALUE, false) }

    /** The row it dropped, null if none. */
    fun forget(code: String, text: String): TableWordRow? {
        val s = state() ?: return null
        val since = s.tableWords[Triple(im, code, text)] ?: return null
        return TableWordRow(im, code, text, since).also { remove(listOf(it)) }
    }

    /** Saves [text] under [code]; false with no record (habits off). */
    fun remember(code: String, text: String): Boolean {
        if (state() == null) return false
        add(code, text)
        return true
    }

    private companion object {
        val EMPTY = TreeMap<String, MutableList<String>>()
    }
}
