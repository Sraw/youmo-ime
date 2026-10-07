/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.popup.PopupOverrides

/**
 * What the user chose for swiping a letter key (down or up, as set): the small character in
 * the key's corner, `1` on q and `@` on a as built in. Keyed by the letter in lower case; the
 * upper case one (Shift, or a keyboard drawing its letters upper case) is the same key.
 *
 * Stored as text, one line per key: the letter, then what the swipe types, separated by
 * whitespace (full-width spaces too, as [PopupOverrides] has it). A line without the second word,
 * or with more, does not parse and is skipped; a later line for the same letter wins.
 */
class SwipeOverrides private constructor(private val table: Map<String, String>) {

    val isEmpty: Boolean get() = table.isEmpty()

    /** The override for [label]'s key, upper case or lower; null where the user has none. */
    operator fun get(label: String): String? = table[label.lowercase()]

    /** What swiping [label]'s key types: the user's choice, else [preset]. */
    fun resolve(label: String, preset: String): String = get(label) ?: preset

    /** With [text] for [label]'s key; the [preset] back, or nothing, is no override. */
    fun with(label: String, text: String, preset: String): SwipeOverrides {
        val clean = PopupOverrides.tokens(text).singleOrNull()
        if (clean == null || clean == preset) return without(label)
        return SwipeOverrides(LinkedHashMap(table).also { it[label.lowercase()] = clean })
    }

    fun without(label: String): SwipeOverrides {
        val key = label.lowercase()
        return if (key in table) SwipeOverrides(LinkedHashMap(table).also { it.remove(key) }) else this
    }

    fun serialize(): String = table.entries.joinToString("\n") { (label, text) -> "$label $text" }

    override fun equals(other: Any?): Boolean = other is SwipeOverrides && other.table == table

    override fun hashCode(): Int = table.hashCode()

    override fun toString(): String = "SwipeOverrides($table)"

    companion object {
        val Empty = SwipeOverrides(emptyMap())

        /** Never throws: a line that does not parse is skipped, so a bad edit cannot break typing. */
        fun parse(text: String): SwipeOverrides {
            val table = LinkedHashMap<String, String>()
            for (line in text.lineSequence()) {
                val tokens = PopupOverrides.tokens(line)
                if (tokens.size == 2) table[tokens[0].lowercase()] = tokens[1]
            }
            return if (table.isEmpty()) Empty else SwipeOverrides(table)
        }
    }
}
