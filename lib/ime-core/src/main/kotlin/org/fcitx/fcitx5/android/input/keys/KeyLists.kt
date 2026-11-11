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
 * What the user set of one key's long press (060-keys-unify §2): [off], none at all; else its [symbols] (the
 * part above the row its keyboard makes, which is never stored) and its [first].
 */
data class UserList(val off: Boolean, val symbols: List<String> = emptyList(), val first: First = First.Default) {
    companion object {
        val Off = UserList(off = true)
    }
}

/**
 * Every key's long press the user set, by the key's one name (`key_lists`, §2.1): a first line `@1`, then a
 * line a key: its name, a tab, `off` or `set`, a tab, its first (`*` the default's, `!` none, `:` and an item)
 * and its symbols, tab apart, each escaped as [KeyShortcuts] escapes. [parse] never throws: a line it cannot
 * read is skipped, an item it refuses left out.
 */
class KeyLists private constructor(val lists: Map<KeyId, UserList>) {

    operator fun get(id: KeyId): UserList? = lists[id]

    /**
     * [id] set to [list]; none, or the same as its default on every keyboard the key is on ([defaults], one a
     * keyboard: 九键 1's pinyin and English), is no setting and not kept. A list of no symbols where none of
     * [defaults] has a row is [UserList.Off] (m1).
     */
    fun with(id: KeyId, list: UserList?, defaults: List<DefaultList?>): KeyLists {
        val t = LinkedHashMap(lists)
        val clean = list?.let { normal(it, defaults) }
        if (clean == null || isDefault(clean, defaults)) t.remove(id) else t[id] = clean
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
            for (s in list.symbols) append('\t').append(KeyShortcuts.escape(s))
        }
    }

    override fun equals(other: Any?): Boolean = other is KeyLists && other.lists == lists

    override fun hashCode(): Int = lists.hashCode()

    override fun toString(): String = "KeyLists($lists)"

    companion object {
        const val HEADER = "@1"

        /** A key's symbols at most, and an item's code points. */
        const val MAX_SYMBOLS = 32
        const val MAX_ITEM = 16

        private const val OFF = "off"
        private const val SET = "set"
        private val FORM = Regex("[a-z0-9-]+")

        val Empty = KeyLists(emptyMap())

        /** [lists] as given, each cleaned as [parse] cleans a line (the migration's, a test's). */
        fun of(lists: Map<KeyId, UserList>): KeyLists = KeyLists(lists.mapValues { (_, l) -> l.copy(symbols = items(l.symbols)) })

        /** [text] as [serialize] wrote it; null where it is not of this format (none, 0.5's, an `@2` of a later one): migrate. */
        fun parse(text: String?): KeyLists? {
            if (text == null) return null
            val lines = text.lines()
            if (lines.first().trimEnd('\r') != HEADER) return null
            val t = LinkedHashMap<KeyId, UserList>()
            for (line in lines.drop(1)) {
                val parts = line.trimEnd('\r').split('\t')
                val id = idOf(parts[0])
                val list = when {
                    parts.size == 2 && parts[1] == OFF -> UserList.Off
                    parts.size >= 3 && parts[1] == SET -> set(parts[2], parts.drop(3))
                    else -> null
                }
                // a later line of the same key wins, as PopupOverrides' does
                if (id != null && list != null) t[id] = list
            }
            return KeyLists(t)
        }

        /** A stored name as a key's: `<form>/<key>`, the form lower case letters, digits and `-`. */
        fun idOf(stored: String): KeyId? {
            val name = KeyShortcuts.unescape(stored)
            val slash = name.indexOf('/')
            if (slash <= 0 || slash == name.length - 1) return null
            return if (FORM.matches(name.substring(0, slash))) KeyId(name) else null
        }

        /**
         * [typed] as a key keeps its symbols: each cut to [MAX_ITEM] code points; none empty or blank, none a
         * keyboard's own code ([PopupOverrides.rejects]), each once; [MAX_SYMBOLS] at most.
         */
        fun items(typed: List<String>): List<String> =
            typed.map(::clip).filter { it.isNotBlank() && !PopupOverrides.rejects(it) }.distinct().take(MAX_SYMBOLS)

        fun clip(item: String): String =
            if (item.codePointCount(0, item.length) <= MAX_ITEM) item else item.substring(0, item.offsetByCodePoints(0, MAX_ITEM))

        private fun set(first: String, symbols: List<String>): UserList? {
            val f = when {
                first == "*" -> First.Default
                first == "!" -> First.None
                first.startsWith(":") && first.length > 1 -> First.Literal(clip(KeyShortcuts.unescape(first.substring(1))))
                else -> return null
            }
            return UserList(off = false, symbols = items(symbols.map(KeyShortcuts::unescape)), first = f)
        }

        // no symbols and no row on any keyboard: nothing to show, as off is
        private fun normal(list: UserList, defaults: List<DefaultList?>): UserList {
            if (list.off) return UserList.Off
            val l = list.copy(symbols = items(list.symbols))
            return if (l.symbols.isEmpty() && defaults.all { it == null || it.row.isEmpty() }) UserList.Off else l
        }

        private fun isDefault(list: UserList, defaults: List<DefaultList?>): Boolean = when {
            // off where it has none anyway
            list.off -> defaults.all { it == null }
            list.first != First.Default || defaults.isEmpty() -> false
            else -> defaults.all { d -> d != null && d.symbols.map { it.text } == list.symbols }
        }
    }
}
