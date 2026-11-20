/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/** A key's mapping and, for a pair, its altMapping (empty when none). */
typealias PunctuationEntry = PunctuationTable.Entry

/** fcitx's `PunctuationProfile`; [entries] as the edit page lists and saves it. */
class PunctuationProfile private constructor(val entries: List<PunctuationEntry>) {
    private val map: Map<Int, List<PunctuationEntry>> = entries.groupBy { it.key.codePointAt(0) }

    /** `getPunctuation`: the first entry, or an empty pair. */
    fun punctuation(unicode: Int): Pair<String, String> =
        map[unicode]?.first()?.let { it.mapping to it.altMapping } ?: ("" to "")

    /** `getPunctuations`: a pair's halves apart; a key of one entry gives only its first, so a lone pair still alternates. */
    fun punctuations(unicode: Int): List<String> {
        val list = map[unicode] ?: return emptyList()
        if (list.size == 1) return listOf(list[0].mapping)
        return list.flatMap { if (it.altMapping.isEmpty()) listOf(it.mapping) else listOf(it.mapping, it.altMapping) }
    }

    companion object {
        /** `PunctuationProfile::load` through the app's own reader, so a replay reads a table as the device does. */
        fun parse(text: String): PunctuationProfile = fromEntries(PunctuationTable.parse(text.encodeToByteArray()))

        /** `Punctuation::loadProfiles`: a [user] file replaces [system] wholesale. */
        fun load(system: String, user: String?): PunctuationProfile = parse(user ?: system)

        /** `PunctuationProfile::set`: an empty key or mapping, or a key of more than one character, is dropped. */
        fun fromEntries(entries: List<PunctuationEntry>): PunctuationProfile = PunctuationProfile(PunctuationTable.accepted(entries))
    }
}
