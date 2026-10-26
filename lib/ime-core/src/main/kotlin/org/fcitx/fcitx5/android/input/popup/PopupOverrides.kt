/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.host.KeyCodes

/**
 * What the user changed about the characters a key offers on long press.
 *
 * The built-in table maps a key label to its candidates; an override *replaces* the candidates
 * of that one label (a partial edit is not a merge, so what the editor shows is what the popup
 * shows). An override with no candidates switches the popup off for that key. The first
 * candidate is the one under the finger, i.e. what a long press committed without sliding.
 *
 * Stored as text, one line per key: the label, then its candidates, all separated by
 * whitespace (including full-width spaces). So neither can contain whitespace, which no real key
 * label or candidate does. A candidate longer than one character is committed as text.
 * A later line for the same label wins. A keyboard's own code is never offered ([rejects]).
 */
class PopupOverrides private constructor(private val table: Map<String, List<String>>) {

    val labels: Set<String> get() = table.keys

    val isEmpty: Boolean get() = table.isEmpty()

    /** The override for exactly [label], without any fallback. */
    operator fun get(label: String): List<String>? = table[label]

    /**
     * @param preset the built-in candidates for [label]
     * @return what to show on long press; null when there is nothing to show
     */
    fun resolve(label: String, preset: Array<String>?): Array<String>? {
        // a code kept by an older build (0.3.x, a backup) is not offered, but stays in the text as written
        val stored = table[label] ?: shiftedOverride(label) ?: return preset
        val items = stored.filterNot(::rejects)
        // emptied only by codes left out: the user switched nothing off, so the built-in ones show
        if (items.isEmpty() && stored.isNotEmpty()) return preset
        return items.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    /**
     * Shift shows the popup of the upper-case label ("Q"); the user edited "q". Carry the edit
     * over rather than silently falling back to the built-in table.
     */
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

        /** Splits [text] into words the way the stored format does. */
        fun tokens(text: String): List<String> = text.split(whitespace).filter { it.isNotEmpty() }

        /**
         * Whether [item] is a keyboard's own code alone, refused ([KeyCodes.isInternal]): the grouped
         * keyboards' codes (NineKeyLetters.CODES) and any other private use character (Keyboard's SYLLABLES, TAPS, INK…, some past the BMP: one
         * character, two chars). A popup sends any single code point as a key, read as that key;
         * text of more characters is committed as text, never read so. Not
         * kept by [with] (the settings page says why) nor offered by [resolve]; what an older build
         * stored stays in the text, so a save of another key does not rewrite it.
         */
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
