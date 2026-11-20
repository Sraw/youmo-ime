/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.scene.keys.DefaultList
import org.fcitx.fcitx5.android.scene.keys.KeyId

/** The default's, none, or the item of [Literal]'s text. */
sealed interface First {
    data object Default : First

    data object None : First

    data class Literal(val text: String) : First
}

/** Stored as [letter]; at most one an item (one of variables is typed as it is already). */
enum class ItemMark(val letter: Char) {
    /** 使用变量: `$year` and the like filled in when shown and typed. */
    Variables('v'),

    /** 不转中文标点. */
    AsIs('r'),
}

/** [off]: no long press at all; else the whole list in the user's order, [marks] by item text. */
data class UserList(
    val off: Boolean,
    val items: List<String> = emptyList(),
    val first: First = First.Default,
    val marks: Map<String, ItemMark> = emptyMap(),
) {

    /** Of [items] as shown (Shift's capitals, a skipped item gone). */
    fun firstOf(items: List<String>): String? = firstOf(first, items)

    companion object {
        val Off = UserList(off = true)

        /** The one rule, for the resolver and the editor alike: none for [First.None]; [First.Literal]'s item where present; else the first. */
        fun firstOf(first: First, items: List<String>): String? = when (first) {
            First.None -> null
            is First.Literal -> first.text.takeIf { it in items } ?: items.firstOrNull()
            First.Default -> items.firstOrNull()
        }
    }
}

/**
 * Every key's user long press, stored as `@2` then `name<TAB>off|set<TAB>first<TAB>items…` lines, escaped as
 * [KeyShortcuts] does; with any [ItemMark], `@3` and each item `marks|item`, so 0.6.0 still reads unmarked
 * lists. [parse] never throws: an unreadable line is skipped.
 */
class KeyLists private constructor(val lists: Map<KeyId, UserList>) {

    operator fun get(id: KeyId): UserList? = lists[id]

    /** The same as [default] (as resolved) is no setting and not kept; no items is [UserList.Off]. */
    fun with(id: KeyId, list: UserList?, default: DefaultList?): KeyLists {
        val t = LinkedHashMap(lists)
        val clean = list?.let(::normal)
        if (clean == null || isDefault(clean, default)) t.remove(id) else t[id] = clean
        return KeyLists(t)
    }

    /** 恢复此键默认. */
    fun without(ids: Collection<KeyId>): KeyLists = KeyLists(lists.filterKeys { it !in ids })

    fun serialize(): String = buildString {
        val marked = lists.values.any { it.marks.isNotEmpty() }
        append(if (marked) HEADER_3 else HEADER)
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
            for (s in list.items) {
                append('\t')
                if (marked) append(list.marks[s]?.letter ?: "").append(MARKS_END)
                append(KeyShortcuts.escape(s))
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is KeyLists && other.lists == lists

    override fun hashCode(): Int = lists.hashCode()

    override fun toString(): String = "KeyLists($lists)"

    companion object {
        /** What 0.6.0 reads; the default and what migrations write. */
        const val HEADER = "@2"

        /** 0.6.0 reads it as a later version's: its default lists then. */
        const val HEADER_3 = "@3"

        /** Its `set` held only the symbols above the row; migrated again where stored. */
        const val HEADER_1 = "@1"

        /** Every built-in default fits and a popup stays within 4 rows; also an item's code points. */
        const val MAX_ITEMS = 16
        const val MAX_ITEM = 16

        /** And a first's, which may be one. */
        const val MAX_TEMPLATE = 64

        private const val MARKS_END = '|'
        private val MARK_LETTERS = Regex("[a-z]*")

        private const val OFF = "off"
        private const val SET = "set"
        private val FORM = Regex("[a-z0-9-]+")
        private val VERSION = Regex("@[0-9]+")

        val Empty = KeyLists(emptyMap())

        /** Each cleaned as [parse] cleans a line. */
        fun of(lists: Map<KeyId, UserList>): KeyLists = KeyLists(lists.mapValues { (_, l) -> cleaned(l) })

        /** No header (0.5's or a beta's) or `@1`; a later version's is left alone (read as [Empty]); `@` alone migrates. */
        fun needsMigration(text: String?): Boolean = text == null || header(text).let { !VERSION.matches(it) || it == HEADER_1 }

        /** Whether [text] is `@1`'s, the one [KeyListsMigration.upgrade] changes. */
        fun isVersion1(text: String?): Boolean = text != null && header(text) == HEADER_1

        /** Null where not this format. A `set` of no items is off ([emptyIsOff]; `@1`'s upgrade reads it as is). */
        fun parse(text: String?, emptyIsOff: Boolean = true): KeyLists? {
            if (text == null) return null
            val lines = trimmed(text).lines()
            val marked = when (lines.first().trimEnd()) {
                HEADER -> false
                HEADER_3 -> true
                else -> return null
            }
            val t = LinkedHashMap<KeyId, UserList>()
            for (line in lines.drop(1)) {
                val parts = line.trimEnd('\r').split('\t')
                val id = idOf(parts[0])
                val list = when {
                    parts.size == 2 && parts[1] == OFF -> UserList.Off
                    parts.size >= 3 && parts[1] == SET -> set(parts[2], parts.drop(3), marked)?.let { if (emptyIsOff && it.items.isEmpty()) UserList.Off else it }
                    else -> null
                }
                // a later line of the same key wins, as PopupOverrides' does
                if (id != null && list != null) t[id] = list
            }
            return KeyLists(t)
        }

        // an editor or a backup transfer may add blanks or a byte order mark
        private fun trimmed(text: String): String = text.trimStart { it.isWhitespace() || it == '\uFEFF' }

        private fun header(text: String): String = trimmed(text).lineSequence().first().trimEnd()

        /** `<form>/<key>`, the form lower case letters, digits and `-`. */
        fun idOf(stored: String): KeyId? {
            val name = KeyShortcuts.unescape(stored)
            val slash = name.indexOf('/')
            if (slash <= 0 || slash == name.length - 1) return null
            return if (FORM.matches(name.substring(0, slash))) KeyId(name) else null
        }

        /** Each item clipped, none blank, none a keyboard's own code ([PopupOverrides.rejects]), each once, [MAX_ITEMS] at most. */
        private fun cleaned(list: UserList): UserList {
            val marks = HashMap<String, ItemMark>()
            val items = list.items.asSequence()
                .map { t -> list.marks[t].let { m -> clip(t, m).also { c -> if (m != null) marks.putIfAbsent(c, m) } } }
                .filter { it.isNotBlank() && !PopupOverrides.rejects(it) }.distinct().take(MAX_ITEMS).toList()
            val first = (list.first as? First.Literal)?.let { First.Literal(clip(it.text, ItemMark.Variables)) } ?: list.first
            return list.copy(items = items, first = first, marks = items.mapNotNull { t -> marks[t]?.let { t to it } }.toMap())
        }

        fun clip(item: String, mark: ItemMark? = null): String {
            val most = if (mark == ItemMark.Variables) MAX_TEMPLATE else MAX_ITEM
            return if (item.codePointCount(0, item.length) <= most) item else item.substring(0, item.offsetByCodePoints(0, most))
        }

        private fun set(first: String, stored: List<String>, marked: Boolean): UserList? {
            val f = when {
                first == "*" -> First.Default
                first == "!" -> First.None
                first.startsWith(":") && first.length > 1 -> First.Literal(KeyShortcuts.unescape(first.substring(1)))
                else -> return null
            }
            val items = ArrayList<String>()
            val marks = HashMap<String, ItemMark>()
            for (s in stored) {
                // marks up to the first `|`; an unknown letter (a later version's) as none
                val bar = if (marked) s.indexOf(MARKS_END) else -1
                if (marked && (bar < 0 || !MARK_LETTERS.matches(s.substring(0, bar)))) continue
                val item = KeyShortcuts.unescape(s.substring(bar + 1))
                items += item
                if (marked) markOf(s.substring(0, bar))?.let { marks.putIfAbsent(item, it) }
            }
            return cleaned(UserList(off = false, items = items, first = f, marks = marks))
        }

        // one mark an item: of variables, typed as it is already, over as-is
        private fun markOf(letters: String): ItemMark? =
            ItemMark.entries.firstOrNull { it.letter in letters }

        // no items: nothing to show, as off is
        private fun normal(list: UserList): UserList {
            if (list.off) return UserList.Off
            val l = cleaned(list)
            return if (l.items.isEmpty()) UserList.Off else l
        }

        // the first compared as resolved, not as stored; a marked item is never the default's
        private fun isDefault(list: UserList, default: DefaultList?): Boolean = when {
            list.off -> default == null
            default == null -> false
            list.marks.isNotEmpty() -> false
            else -> default.items.map { it.text } == list.items && list.firstOf(list.items) == default.first?.text
        }
    }
}
