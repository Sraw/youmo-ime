/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/** One line of a punctuation table: a key's mapping and, for a pair, its altMapping (empty when none). */
typealias PunctuationEntry = PunctuationTable.Entry

/**
 * A punctuation table, fcitx's `PunctuationProfile` (`ca/punctuation/punctuation.cpp:74-210`): each
 * key character's entries in table order. [entries] is the table as the edit page lists and saves it.
 */
class PunctuationProfile private constructor(val entries: List<PunctuationEntry>) {
    private val map: Map<Int, List<PunctuationEntry>> = entries.groupBy { it.key.codePointAt(0) }

    /** `getPunctuation` (`:175-182`): the key's first entry, or an empty pair. */
    fun punctuation(unicode: Int): Pair<String, String> =
        map[unicode]?.first()?.let { it.mapping to it.altMapping } ?: ("" to "")

    /**
     * `getPunctuations` (`:184-203`): every mark the key offers, a pair's halves apart; a key of one
     * entry gives only its first mark, so a lone pair still alternates.
     */
    fun punctuations(unicode: Int): List<String> {
        val list = map[unicode] ?: return emptyList()
        if (list.size == 1) return listOf(list[0].mapping)
        return list.flatMap { if (it.altMapping.isEmpty()) listOf(it.mapping) else listOf(it.mapping, it.altMapping) }
    }

    companion object {
        /**
         * `PunctuationProfile::load` (`:101-129`) through [PunctuationTable.parse], the one reader of
         * the file the app uses too, so a replay reads a table as the device does.
         */
        fun parse(text: String): PunctuationProfile = fromEntries(PunctuationTable.parse(text.encodeToByteArray()))

        /**
         * The table in use (`Punctuation::loadProfiles`, `:380-432`): the built-in [system] one, and a
         * [user] file, when there is one, **replaces it wholesale** (`load` clears the map first, `:102-104`).
         */
        fun load(system: String, user: String?): PunctuationProfile = parse(user ?: system)

        /**
         * The edit page's table (`PunctuationProfile::set`, `:131-152`): entries with an empty key or
         * mapping, or a key of more than one character, are dropped.
         */
        fun fromEntries(entries: List<PunctuationEntry>): PunctuationProfile = PunctuationProfile(PunctuationTable.accepted(entries))
    }
}
