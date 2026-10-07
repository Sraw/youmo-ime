/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.search

/**
 * Finds settings by what the user types: every word of the query somewhere in a setting's
 * title, its summary or the pages it is under, in any case. Titles that match come first, those
 * that start with the query before them; otherwise the settings keep their order, which is the
 * order of the pages.
 *
 * @param T where a setting is: what opening a result needs
 */
class SettingsSearch<T>(entries: List<Entry<T>>) {

    /** A setting: what it is called, what it says under that, and the pages it is on, outermost first. */
    data class Entry<T>(val title: String, val summary: String, val path: List<String>, val target: T)

    // the same setting reached twice (a page listed under two others) is offered once
    private val entries = entries.filter { it.title.isNotBlank() }.distinctBy { it.title to it.path }

    fun find(query: String, limit: Int = LIMIT): List<Entry<T>> {
        val terms = query.lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val whole = terms.joinToString(" ")
        return entries.asSequence()
            .mapNotNull { e -> rank(e, terms, whole)?.let { e to it } }
            .sortedBy { it.second } // stable: equal ranks keep the pages' order
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun rank(e: Entry<T>, terms: List<String>, whole: String): Int? {
        val title = e.title.lowercase()
        val all = listOf(title, e.summary.lowercase(), e.path.joinToString(" ").lowercase()).joinToString("\n")
        if (terms.any { it !in all }) return null
        return when {
            title.startsWith(whole) -> TITLE_START
            terms.all { it in title } -> TITLE
            else -> ELSEWHERE
        }
    }

    private companion object {
        const val LIMIT = 50
        const val TITLE_START = 0
        const val TITLE = 1
        const val ELSEWHERE = 2

        // \p{Z} for the full-width space a Chinese keyboard types
        val WHITESPACE = Regex("[\\s\\p{Z}]+")
    }
}
