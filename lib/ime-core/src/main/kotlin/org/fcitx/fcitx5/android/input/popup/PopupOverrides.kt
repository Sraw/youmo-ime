/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.host.KeyCodes

/**
 * The user's long-press characters: an override *replaces* one label's candidates (no merge, so the editor shows
 * what the popup shows); none switches it off; the first is the one under the finger. Stored one line per key,
 * whitespace-separated (full-width too); a later line wins. A keyboard's own code is never offered ([rejects]).
 */
class PopupOverrides private constructor(private val table: Map<String, List<String>>) {

    val labels: Set<String> get() = table.keys

    val isEmpty: Boolean get() = table.isEmpty()

    /** No fallback. */
    operator fun get(label: String): List<String>? = table[label]

    /** Null when there is nothing to show. */
    fun resolve(label: String, preset: Array<String>?): Array<String>? {
        // a code kept by an older build is not offered, but stays in the text as written
        val stored = table[label] ?: shiftedOverride(label) ?: return preset
        val items = stored.filterNot(::rejects)
        // emptied only by codes left out: the user switched nothing off, so the built-in ones show
        if (items.isEmpty() && stored.isNotEmpty()) return preset
        return items.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    /** Shift shows "Q"'s popup; the user edited "q": carry it over rather than fall back silently. */
    private fun shiftedOverride(label: String): List<String>? {
        if (label.length != 1 || !label[0].isUpperCase()) return null
        return table[label.lowercase()]?.map(::upperCase)?.distinct()
    }

    // "ß".uppercase() is "SS": keep such a candidate as the user typed it
    private fun upperCase(item: String): String = item.uppercase().takeIf { it.length == item.length } ?: item

    fun with(label: String, items: List<String>): PopupOverrides {
        val clean = items.filter { it.isNotEmpty() && !rejects(it) }
        return PopupOverrides(LinkedHashMap(table).also { it[label] = clean })
    }

    fun without(label: String): PopupOverrides =
        if (label in table) PopupOverrides(LinkedHashMap(table).also { it.remove(label) }) else this

    fun serialize(): String = table.entries.joinToString("\n") { (label, items) ->
        (listOf(label) + items).joinToString(" ")
    }

    override fun equals(other: Any?): Boolean = other is PopupOverrides && other.table == table

    override fun hashCode(): Int = table.hashCode()

    override fun toString(): String = "PopupOverrides($table)"

    companion object {
        val Empty = PopupOverrides(emptyMap())

        // \p{Z} for the full-width and no-break spaces an IME's own users type by accident
        private val whitespace = Regex("[\\s\\p{Z}]+")

        /** As the stored format does. */
        fun tokens(text: String): List<String> = text.split(whitespace).filter { it.isNotEmpty() }

        /** A popup sends a single code point as a key, so a lone private use character would act as one: not kept by [with] nor offered; an older build's stays in the text. */
        fun rejects(item: String): Boolean =
            item.isNotEmpty() && item.codePointCount(0, item.length) == 1 && KeyCodes.isInternal(item.codePointAt(0))

        /** Never throws: a line that does not parse is skipped, so a bad edit cannot break typing. */
        fun parse(text: String): PopupOverrides {
            val table = LinkedHashMap<String, List<String>>()
            for (line in text.lineSequence()) {
                val tokens = tokens(line)
                val label = tokens.firstOrNull() ?: continue
                table[label] = tokens.drop(1)
            }
            return if (table.isEmpty()) Empty else PopupOverrides(table)
        }
    }
}
