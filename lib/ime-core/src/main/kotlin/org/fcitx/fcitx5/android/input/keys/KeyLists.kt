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
 * What one item of a list the user set is marked with (061-text-variables §3, 061-no-cn-punct), stored as its
 * [letter]: at most one, as the two never go together (an item of variables is typed as it is already).
 */
enum class ItemMark(val letter: Char) {
    /** 使用变量: its `$year` and the like filled in (TextVariables) when shown and when typed, typed as it is. */
    Variables('v'),

    /** 不转中文标点: typed as it is, never as the Chinese punctuation maps it. */
    AsIs('r'),
}

/**
 * What the user set of one key's long press (060-row-unlock §1): [off], none at all; else its whole list, [items]
 * in the user's order (a key's own letters and digit as any other item), its [first], and the [marks] of its items
 * by their text (an item of none not there).
 */
data class UserList(
    val off: Boolean,
    val items: List<String> = emptyList(),
    val first: First = First.Default,
    val marks: Map<String, ItemMark> = emptyMap(),
) {

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
 * an item) and its whole list, tab apart, each escaped as [KeyShortcuts] escapes. Where an item is marked
 * ([ItemMark], 061-text-variables §4.1) the first line is `@3` and every item is its marks' letters, `|` and the item
 * escaped (`v|$year年`, `|。`; the first after `:` as ever, never cut at a `|`); with none, `@2` as before, so 0.6.0
 * reads it. [parse] never throws: a line it cannot read is skipped, an item it refuses left out.
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

    /** As stored, its lines by the key's name: `@3` where an item is marked, else `@2`. */
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
        /** The version 0.6.0 reads: no item marked. Kept as the default and what the migrations write. */
        const val HEADER = "@2"

        /** The version of items marked (061-text-variables §4.1): 0.6.0 reads it as a later version's, its default lists then. */
        const val HEADER_3 = "@3"

        /** The version before, whose `set` held only the symbols above the row (KeyListsMigration.upgrade). */
        const val HEADER_1 = "@1"

        /**
         * A key's items at most (every built-in default fits, the longest 13, and a popup stays within 4 rows; an
         * upgrade keeps the first ones), and an item's code points.
         */
        const val MAX_ITEMS = 16
        const val MAX_ITEM = 16

        /** An item of variables' code points at most (`$year年$month月$day日 星期$weekday_cn`, Q2), and a first's, which may be one. */
        const val MAX_TEMPLATE = 64

        private const val MARKS_END = '|'
        private val MARK_LETTERS = Regex("[a-z]*")

        private const val OFF = "off"
        private const val SET = "set"
        private val FORM = Regex("[a-z0-9-]+")
        private val VERSION = Regex("@[0-9]+")

        val Empty = KeyLists(emptyMap())

        /** [lists] as given, each cleaned as [parse] cleans a line (the migration's, a test's). */
        fun of(lists: Map<KeyId, UserList>): KeyLists = KeyLists(lists.mapValues { (_, l) -> cleaned(l) })

        /**
         * Whether [text] stored has no header (never stored, empty, 0.5's or a beta's, which never start with `@`) or
         * `@1`'s: migrated (and upgraded) then. A later version's (`@4`) is left alone, read as [Empty]; a header of
         * `@` and no number (cut short, written by hand) is no version's and migrates.
         */
        fun needsMigration(text: String?): Boolean = text == null || header(text).let { !VERSION.matches(it) || it == HEADER_1 }

        /** Whether [text] stored is `@1`'s, upgraded alone (KeyListsMigration.upgrade). */
        fun isVersion1(text: String?): Boolean = text != null && header(text) == HEADER_1

        /**
         * [text] as [serialize] wrote it, `@2` or `@3`; null where it is not of this format (none, 0.5's, `@1`'s, an `@4` of a later one).
         * A `set` of no items is off, as [with] stores it ([emptyIsOff]; `@1`'s upgrade reads it as it is, its
         * items being only the symbols above a row, so none there is the row alone).
         */
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
         * [list] as a key keeps it: each item cut to [MAX_ITEM] code points ([MAX_TEMPLATE] one of variables); none
         * empty or blank, none a keyboard's own code ([PopupOverrides.rejects]), each once; [MAX_ITEMS] at most; the
         * marks of those kept alone, by their text as cut.
         */
        private fun cleaned(list: UserList): UserList {
            val marks = HashMap<String, ItemMark>()
            val items = list.items.asSequence()
                .map { t -> list.marks[t].let { m -> clip(t, m).also { c -> if (m != null) marks.putIfAbsent(c, m) } } }
                .filter { it.isNotBlank() && !PopupOverrides.rejects(it) }.distinct().take(MAX_ITEMS).toList()
            val first = (list.first as? First.Literal)?.let { First.Literal(clip(it.text, ItemMark.Variables)) } ?: list.first
            return list.copy(items = items, first = first, marks = items.mapNotNull { t -> marks[t]?.let { t to it } }.toMap())
        }

        /** [item] cut to the code points an item of [mark] keeps. */
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
                // the marks up to the first `|`, the item after it (a `|` of its own too); an item of none, or of marks
                // not letters, left out; a letter not known (a later version's) as none
                val bar = if (marked) s.indexOf(MARKS_END) else -1
                if (marked && (bar < 0 || !MARK_LETTERS.matches(s.substring(0, bar)))) continue
                val item = KeyShortcuts.unescape(s.substring(bar + 1))
                items += item
                if (marked) markOf(s.substring(0, bar))?.let { marks.putIfAbsent(item, it) }
            }
            return cleaned(UserList(off = false, items = items, first = f, marks = marks))
        }

        // one mark an item: of variables, typed as it is already, over as-is (S6)
        private fun markOf(letters: String): ItemMark? =
            ItemMark.entries.firstOrNull { it.letter in letters }

        // no items: nothing to show, as off is
        private fun normal(list: UserList): UserList {
            if (list.off) return UserList.Off
            val l = cleaned(list)
            return if (l.items.isEmpty()) UserList.Off else l
        }

        // the first compared as resolved, not as stored: the default nine 2 stores `:2`, a 注音 group `!`; an item
        // marked is never the default's (061-text-variables M3: a default list with its `,` as-is kept)
        private fun isDefault(list: UserList, default: DefaultList?): Boolean = when {
            // off where it has none anyway
            list.off -> default == null
            default == null -> false
            list.marks.isNotEmpty() -> false
            else -> default.items.map { it.text } == list.items && list.firstOf(list.items) == default.first?.text
        }
    }
}
