/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.scene.keys.DefaultList
import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant

/** 0.5's `popup_overrides` and `t9_symbols` as `key_lists`; pure, so it can run again on the old keys and agree. */
object KeyListsMigration {

    /** [text] `@1` first; what could not be kept, a line each. */
    data class Migrated(val text: String, val lost: List<String>)

    private val NINE = KeyboardVariant(KeyboardVariant.Form.Nine)

    // 0.5's characters of the keys that are on every keyboard now, or on 注音九键's
    private val BY_CHARACTER = mapOf(
        "," to KeyId.function("comma"),
        "." to KeyId.function("period"),
        "?" to KeyId("zy9/question"),
        "!" to KeyId("zy9/exclam"),
        "~" to KeyId("zy9/tilde"),
    )

    fun migrate(popupOverrides: String?, t9Symbols: String?): Migrated {
        val lists = LinkedHashMap<KeyId, UserList>()
        val lost = ArrayList<String>()
        fromCharacters(PopupOverrides.parse(popupOverrides.orEmpty()), lists, lost)
        for ((d, symbols) in nineSymbols(t9Symbols.orEmpty())) {
            val id = KeyId("t9/$d")
            // what the user set (the old defaults, 1's full width and as is, are none: its default is the punctuation's list now)
            lists[id] = UserList(off = false, items = symbols)
        }
        val kept = lists.filterNot { (id, list) -> same(list, defaultOf(id)) }
        // `@1`'s: made `@2`'s by [upgrade]; its own first line dropped whichever it is
        return Migrated(KeyLists.HEADER_1 + KeyLists.of(kept).serialize().dropWhile { it != '\n' }, lost)
    }

    private fun fromCharacters(o: PopupOverrides, lists: MutableMap<KeyId, UserList>, lost: MutableList<String>) {
        for (label in o.labels) {
            val items = o[label].orEmpty()
            val c = label.singleOrNull()
            when {
                c != null && c in 'a'..'z' -> lists[KeyId("full/$c")] = plain(items)
                // Shift showed the lower case's in capitals: the same said twice; another list of its own is lost
                c != null && c in 'A'..'Z' -> if (o[label.lowercase()]?.let(::upper) != items) lost += "L2 $label ${items.joinToString(" ")}"
                label in BY_CHARACTER -> lists[BY_CHARACTER.getValue(label)] = plain(items)
                else -> lost += "L1 $label ${items.joinToString(" ")}"
            }
        }
    }

    // a 0.5 list: the first item the first, as it was there
    private fun plain(items: List<String>): UserList = if (items.isEmpty()) UserList.Off else UserList(off = false, items = items)

    // the capitals Shift showed of a lower case list (PopupOverrides.shiftedOverride)
    private fun upper(items: List<String>): List<String> = items.map { s -> s.uppercase().takeIf { it.length == s.length } ?: s }.distinct()

    /** As 拼音's keyboard has it, the one 0.5 showed; only the keys [migrate] makes. */
    private fun defaultOf(id: KeyId): DefaultList? = when (id.form) {
        KeyId.FUNCTION -> KeyDefaults.function(id.key)
        "full" -> KeyDefaults.preset(id.key)
        "t9" -> id.key.singleOrNull()?.takeIf { it in '1'..'9' }?.let { KeyDefaults.nine(it, NINE) }
        "zy9" -> when (id.key) {
            // function keys: their symbols, no first (KeyDefaults.typed)
            "question" -> KeyDefaults.preset("?")?.let { DefaultList(it.row, it.symbols, null) }
            "exclam" -> KeyDefaults.preset("!")?.let { DefaultList(it.row, it.symbols, null) }
            "tilde" -> KeyDefaults.preset("~")
            else -> null
        }
        else -> null
    }

    /**
     * `@1`'s [text] as `@2`'s, any other as it is, so it can run again and agree. 九键's digits' `set` is its row as
     * the editor shows it, then its symbols not in it; first written as none `!`, the list's first `*`, else the item.
     */
    fun upgrade(text: String, nineLetters: Map<Char, String>): String {
        if (!KeyLists.isVersion1(text)) return text
        val body = text.trimStart { it.isWhitespace() || it == '\uFEFF' }.substringAfter('\n', "")
        val old = KeyLists.parse(KeyLists.HEADER + "\n" + body, emptyIsOff = false) ?: return text
        val lists = old.lists.mapValues { (id, l) -> if (l.off) l else upgraded(id, l, nineLetters) }
        return KeyLists.of(lists).serialize()
    }

    private fun upgraded(id: KeyId, l: UserList, nineLetters: Map<Char, String>): UserList {
        val d = upgradeDefault(id, nineLetters)
        val row = d?.row.orEmpty().map { it.text }
        val symbols = l.items.filterNot { it in row }
        val items = (row + symbols).take(KeyLists.MAX_ITEMS)
        // migrate's lists all have their default's first: its default's if in its row, none where it has none, else the first symbol
        val first = when {
            d == null -> symbols.firstOrNull()
            d.first == null -> null
            d.first?.text in row -> d.first?.text?.takeIf { it in items }
            else -> symbols.firstOrNull()
        }
        return UserList(off = false, items = items, first = KeyEdit.firstToStore(first, items))
    }

    // the default a key upgrades against: the editor's for 九键's row, else 拼音's
    private fun upgradeDefault(id: KeyId, nineLetters: Map<Char, String>): DefaultList? = when (id.form) {
        "t9" -> id.key.singleOrNull()?.takeIf { it in '1'..'9' }?.let { KeyDefaults.nine(it, KeyboardVariant(KeyboardVariant.Form.Nine, nineLetters)) }
        else -> defaultOf(id)
    }

    private fun same(list: UserList, default: DefaultList?): Boolean = when {
        list.off -> default == null
        default == null -> false
        else -> list.first == First.Default && list.items == default.symbols.map { it.text }
    }

    /** Lines of keys 1 to 9 set otherwise than [NINE_DEFAULTS], kept as that version kept them; a later line wins. */
    private fun nineSymbols(text: String): Map<Char, List<String>> {
        val edited = LinkedHashMap<Char, List<String>>()
        for (line in text.lines()) {
            val parts = line.split(' ').filter { it.isNotEmpty() }
            val digit = parts.firstOrNull()?.singleOrNull()?.takeIf { it in '1'..'9' } ?: continue
            val kept = parts.drop(1).filter(::nineAllowed).distinct().take(NINE_MAX)
            if (kept == NINE_DEFAULTS[digit]) edited.remove(digit) else edited[digit] = kept
        }
        return edited
    }

    private fun nineAllowed(symbol: String): Boolean {
        if (symbol.isEmpty() || symbol.any { it.isWhitespace() } || PopupOverrides.rejects(symbol)) return false
        val c = symbol.singleOrNull() ?: return true
        return c !in '0'..'9' && c.lowercaseChar() !in 'a'..'z'
    }

    private const val NINE_MAX = 10

    private val NINE_DEFAULTS: Map<Char, List<String>> = mapOf(
        '1' to listOf("…", "：", "；", "~", ".", "-", "@", "、", "“", "”"),
        '2' to listOf("@", "!", "\""),
        '3' to listOf("+", "-"),
        '4' to listOf("=", "/"),
        '5' to listOf("#", "(", ")"),
        '6' to listOf("\\", "~"),
        '7' to listOf("*"),
        '8' to listOf("?"),
        '9' to listOf(":", "'"),
    )
}
