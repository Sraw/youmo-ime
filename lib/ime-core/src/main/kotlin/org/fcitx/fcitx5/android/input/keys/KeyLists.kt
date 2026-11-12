/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.scene.keys.DefaultList
import org.fcitx.fcitx5.android.scene.keys.KeyId

/** A key's first as the user set it: the default's, none, or the item of [Literal]'s text. */
sealed interface First {
    data object Default : First

    data object None : First

    data class Literal(val text: String) : First
}

/**
 * What the user set of one key's long press (060-row-unlock §1): [off], none at all; else its whole list, [items]
 * in the user's order (a key's own letters and digit as any other item), and its [first].
 */
data class UserList(val off: Boolean, val items: List<String> = emptyList(), val first: First = First.Default) {

    /** The first [items] (the list as shown: Shift's capitals, a skipped item gone) open on: see the companion's. */
    fun firstOf(items: List<String>): String? = firstOf(first, items)

    companion object {
        val Off = UserList(off = true)

        /**
         * The one rule of a list's first (060-row-unlock §1.2), as the resolver, the editor's store and its delete
         * read it: none for [First.None]; the item of [First.Literal]'s text where [items] has it; else the first item.
         */
        fun firstOf(first: First, items: List<String>): String? = when (first) {
            First.None -> null
            is First.Literal -> first.text.takeIf { it in items } ?: items.firstOrNull()
            First.Default -> items.firstOrNull()
        }
    }
}

/**
 * Every key's long press the user set, by the key's one name (`key_lists`, 060-row-unlock §1.2): a first line `@2`,
 * then a line a key: its name, a tab, `off` or `set`, a tab, its first (`*` the list's first item, `!` none, `:` and
 * an item) and its whole list, tab apart, each escaped as [KeyShortcuts] escapes. [parse] never throws: a line it cannot
 * read is skipped, an item it refuses left out.
 */
class KeyLists private constructor(val lists: Map<KeyId, UserList>) {

    operator fun get(id: KeyId): UserList? = lists[id]

    /**
     * [id] set to [list]; none, or the same as [default] (the editor's: its items' texts, and its first as the list
     * resolves it), is no setting and not kept. A list of no items is [UserList.Off], every key alike.
     */
    fun with(id: KeyId, list: UserList?, default: DefaultList?): KeyLists {
        val t = LinkedHashMap(lists)
        val clean = list?.let(::normal)
        if (clean == null || isDefault(clean, default)) t.remove(id) else t[id] = clean
        return KeyLists(t)
    }

    /** [ids] back to their defaults (恢复此键默认). */
    fun without(ids: Collection<KeyId>): KeyLists = KeyLists(lists.filterKeys { it !in ids })

    /** As stored, its lines by the key's name. */
    fun serialize(): String = buildString {
        append(HEADER)
        for ((id, list) in lists.entries.sortedBy { it.key.name }) {
            append('\n').append(KeyShortcuts.escape(id.name)).append('\t')
            if (list.off) {
                append(OFF)
                continue
            }
            append(SET).append('\t').append(
                when (val f = list.first) {
                    First.Default -> "*"
                    First.None -> "!"
                    is First.Literal -> ":" + KeyShortcuts.escape(f.text)
                },
            )
            for (s in list.items) append('\t').append(KeyShortcuts.escape(s))
        }
    }

    override fun equals(other: Any?): Boolean = other is KeyLists && other.lists == lists

    override fun hashCode(): Int = lists.hashCode()

    override fun toString(): String = "KeyLists($lists)"

    companion object {
        const val HEADER = "@2"

        /** The version before, whose `set` held only the symbols above the row (KeyListsMigration.upgrade). */
        const val HEADER_1 = "@1"

        /**
         * A key's items at most (every built-in default fits, the longest 13, and a popup stays within 4 rows; an
         * upgrade keeps the first ones), and an item's code points.
         */
        const val MAX_ITEMS = 16
        const val MAX_ITEM = 16

        private const val OFF = "off"
        private const val SET = "set"
        private val FORM = Regex("[a-z0-9-]+")
        private val VERSION = Regex("@[0-9]+")

        val Empty = KeyLists(emptyMap())

        /** [lists] as given, each cleaned as [parse] cleans a line (the migration's, a test's). */
        fun of(lists: Map<KeyId, UserList>): KeyLists = KeyLists(lists.mapValues { (_, l) -> l.copy(items = items(l.items)) })

        /**
         * Whether [text] stored has no header (never stored, empty, 0.5's or a beta's, which never start with `@`) or
         * `@1`'s: migrated (and upgraded) then. A later version's (`@3`) is left alone, read as [Empty]; a header of
         * `@` and no number (cut short, written by hand) is no version's and migrates.
         */
        fun needsMigration(text: String?): Boolean = text == null || header(text).let { !VERSION.matches(it) || it == HEADER_1 }

        /** Whether [text] stored is `@1`'s, upgraded alone (KeyListsMigration.upgrade). */
        fun isVersion1(text: String?): Boolean = text != null && header(text) == HEADER_1

        /**
         * [text] as [serialize] wrote it; null where it is not of this format (none, 0.5's, `@1`'s, an `@3` of a later one).
         * A `set` of no items is off, as [with] stores it ([emptyIsOff]; `@1`'s upgrade reads it as it is, its
         * items being only the symbols above a row, so none there is the row alone).
         */
        fun parse(text: String?, emptyIsOff: Boolean = true): KeyLists? {
            if (text == null) return null
            val lines = trimmed(text).lines()
            if (lines.first().trimEnd() != HEADER) return null
            val t = LinkedHashMap<KeyId, UserList>()
            for (line in lines.drop(1)) {
                val parts = line.trimEnd('\r').split('\t')
                val id = idOf(parts[0])
                val list = when {
                    parts.size == 2 && parts[1] == OFF -> UserList.Off
                    parts.size >= 3 && parts[1] == SET -> set(parts[2], parts.drop(3))?.let { if (emptyIsOff && it.items.isEmpty()) UserList.Off else it }
                    else -> null
                }
                // a later line of the same key wins, as PopupOverrides' does
                if (id != null && list != null) t[id] = list
            }
            return KeyLists(t)
        }

        // the first line, around it what an editor or a backup's transfer may have added: blanks, a byte order mark
        private fun trimmed(text: String): String = text.trimStart { it.isWhitespace() || it == '\uFEFF' }

        private fun header(text: String): String = trimmed(text).lineSequence().first().trimEnd()

        /** A stored name as a key's: `<form>/<key>`, the form lower case letters, digits and `-`. */
        fun idOf(stored: String): KeyId? {
            val name = KeyShortcuts.unescape(stored)
            val slash = name.indexOf('/')
            if (slash <= 0 || slash == name.length - 1) return null
            return if (FORM.matches(name.substring(0, slash))) KeyId(name) else null
        }

        /**
         * [typed] as a key keeps its items: each cut to [MAX_ITEM] code points; none empty or blank, none a
         * keyboard's own code ([PopupOverrides.rejects]), each once; [MAX_ITEMS] at most.
         */
        fun items(typed: List<String>): List<String> =
            typed.map(::clip).filter { it.isNotBlank() && !PopupOverrides.rejects(it) }.distinct().take(MAX_ITEMS)

        fun clip(item: String): String =
            if (item.codePointCount(0, item.length) <= MAX_ITEM) item else item.substring(0, item.offsetByCodePoints(0, MAX_ITEM))

        private fun set(first: String, typed: List<String>): UserList? {
            val f = when {
                first == "*" -> First.Default
                first == "!" -> First.None
                first.startsWith(":") && first.length > 1 -> First.Literal(clip(KeyShortcuts.unescape(first.substring(1))))
                else -> return null
            }
            return UserList(off = false, items = items(typed.map(KeyShortcuts::unescape)), first = f)
        }

        // no items: nothing to show, as off is
        private fun normal(list: UserList): UserList {
            if (list.off) return UserList.Off
            val l = list.copy(items = items(list.items))
            return if (l.items.isEmpty()) UserList.Off else l
        }

        // the first compared as resolved, not as stored: the default nine 2 stores `:2`, a 注音 group `!`
        private fun isDefault(list: UserList, default: DefaultList?): Boolean = when {
            // off where it has none anyway
            list.off -> default == null
            default == null -> false
            else -> default.items.map { it.text } == list.items && list.firstOf(list.items) == default.first?.text
        }
    }
}
