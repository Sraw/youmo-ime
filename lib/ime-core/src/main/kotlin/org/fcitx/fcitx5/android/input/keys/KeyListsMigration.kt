/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.scene.keys.DefaultList
import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant

/**
 * 0.5's and 0.6.0's betas' long presses as `key_lists` (060-keys-unify §4.2): `popup_overrides` (by a key's
 * character), `key_popups` (by its name, beta2) and `t9_symbols` (九键's 1 to 9). A pure function of the three
 * texts: the same in, the same out, so it can run again on the old keys (a backup of 0.5 imported, the device
 * still locked) and agree. What none of `key_lists` can hold is named in [Migrated.lost].
 */
object KeyListsMigration {

    /** The lists ([text], `@1` first) and what could not be kept, a line each (L1–L4, §4.6). */
    data class Migrated(val text: String, val lost: List<String>)

    private val NINE = KeyboardVariant(KeyboardVariant.Form.Nine)

    // 0.5's characters of the keys that are on every keyboard now, or on 注音九键's (D4)
    private val BY_CHARACTER = mapOf(
        "," to KeyId.function("comma"),
        "." to KeyId.function("period"),
        "?" to KeyId("zy9/question"),
        "!" to KeyId("zy9/exclam"),
        "~" to KeyId("zy9/tilde"),
    )

    fun migrate(popupOverrides: String?, keyPopups: String?, t9Symbols: String?): Migrated {
        val lists = LinkedHashMap<KeyId, UserList>()
        val lost = ArrayList<String>()
        val byName = PopupOverrides.parse(keyPopups.orEmpty())
        val named = byName.labels.map(KeyId::of).toSet()
        fromCharacters(PopupOverrides.parse(popupOverrides.orEmpty()), named, lists, lost)
        for (label in byName.labels) {
            val id = KeyId.of(label)
            fromName(id, byName[label].orEmpty(), lists, lost)
        }
        for ((d, symbols) in nineSymbols(t9Symbols.orEmpty())) {
            val id = KeyId("t9/$d")
            // a key set by its name had its symbols there: t9_symbols no longer counted for it (I17)
            if (id in named) continue
            // what the user set (the old defaults, 1's full width and as is, are none: its default now is the one list
            // through the punctuation)
            lists[id] = UserList(off = false, items = symbols)
        }
        val kept = lists.filterNot { (id, list) -> same(list, defaultOf(id)) }
        // `@1`'s: a key's symbols above its row, upgraded as any `@1` (upgrade)
        return Migrated(KeyLists.HEADER_1 + KeyLists.of(kept).serialize().removePrefix(KeyLists.HEADER), lost)
    }

    private fun fromCharacters(o: PopupOverrides, named: Set<KeyId>, lists: MutableMap<KeyId, UserList>, lost: MutableList<String>) {
        for (label in o.labels) {
            val items = o[label].orEmpty()
            val c = label.singleOrNull()
            when {
                c != null && c in 'a'..'z' -> lists[KeyId("full/$c")] = plain(items)
                // Shift showed the lower case's in capitals: the same said twice; another list of its own is lost (L2)
                c != null && c in 'A'..'Z' -> if (o[label.lowercase()]?.let(::upper) != items) lost += "L2 $label ${items.joinToString(" ")}"
                label in BY_CHARACTER -> {
                    val id = BY_CHARACTER.getValue(label)
                    // the name's is the later setting
                    if (id !in named) lists[id] = plain(items)
                }
                else -> lost += "L1 $label ${items.joinToString(" ")}"
            }
        }
    }

    private fun fromName(id: KeyId, items: List<String>, lists: MutableMap<KeyId, UserList>, lost: MutableList<String>) {
        if (items.isEmpty()) {
            lists[id] = UserList.Off
            return
        }
        val row = rowOf(id)
        if (row.isEmpty()) {
            lists[id] = plain(items)
            return
        }
        val default = defaultOf(id)
        val first = items.first()
        // the row the keyboard makes, not stored: a letter moved or taken out of it is lost (L4); the betas'
        // editor put the key's corner first in front of it, which is no move
        val own = default?.row?.map { it.text }.orEmpty().filter { it != default?.first?.text }
        if (items.filter { it in own } != own) lost += "L4 ${id.name} ${items.joinToString(" ")}"
        val f = if (first == default?.first?.text) First.Default else First.Literal(first)
        val symbols = items.filterNot { it in row }
        // beta2's 九键 key as 0.5's default: none set, as t9_symbols' (its editor stored a key on OK, changed or not)
        if (id.form == "t9" && f == First.Default && symbols == NINE_DEFAULTS[id.key.singleOrNull()]) {
            lists.remove(id)
            return
        }
        lists[id] = UserList(off = false, items = symbols, first = f)
    }

    // a 0.5 list: the first item the first, as it was there
    private fun plain(items: List<String>): UserList = if (items.isEmpty()) UserList.Off else UserList(off = false, items = items)

    // the capitals Shift showed of a lower case list (PopupOverrides.shiftedOverride)
    private fun upper(items: List<String>): List<String> = items.map { s -> s.uppercase().takeIf { it.length == s.length } ?: s }.distinct()

    /**
     * Every item a row of [id]'s may hold on any keyboard it is on (the letters as 拼音, 双拼 and English put them,
     * a group's `;` and `:`, every digit and its corner): none of it a symbol.
     */
    fun rowOf(id: KeyId): Set<String> = when (id.form) {
        "t9" -> id.key.singleOrNull()?.let { d ->
            val sp = NineKeyLetters.NINE_SP.let { l -> l.codes.getOrNull(d - '2')?.let(l::letters) }.orEmpty()
            val letters = KeyboardVariant.STANDARD_NINE[d].orEmpty() + sp
            (letters.map { it.toString() } + letters.uppercase().map { it.toString() } + d.toString()).toSet()
        }.orEmpty()
        "g14", "g18" -> (id.key + id.key.uppercase() + ":" + ('0'..'9').joinToString("")).map { it.toString() }.toSet() +
            listOfNotNull(cornerOf(id))
        "zy9" -> id.key.toIntOrNull()?.let { ZhuyinKeys.GROUP_SYMBOLS.getOrNull(it - 1) }?.map { it.toString() }?.toSet().orEmpty()
        else -> emptySet()
    }

    /** [id]'s default as 拼音's keyboard has it, the one 0.5 and the betas showed (§4.2.4); null for none. */
    fun defaultOf(id: KeyId): DefaultList? = when (id.form) {
        KeyId.FUNCTION -> KeyDefaults.function(id.key)
        "full" -> KeyDefaults.preset(id.key)
        "t9" -> id.key.singleOrNull()?.takeIf { it in '1'..'9' }?.let { KeyDefaults.nine(it, NINE) }
        "g14", "g18" -> KeyDefaults.grouped(id.key, cornerOf(id))
        "zy9" -> when (id.key) {
            // function keys: their symbols, no first (KeyDefaults.typed)
            "question" -> KeyDefaults.preset("?")?.let { DefaultList(it.row, it.symbols, null) }
            "exclam" -> KeyDefaults.preset("!")?.let { DefaultList(it.row, it.symbols, null) }
            "tilde" -> KeyDefaults.preset("~")
            else -> id.key.toIntOrNull()?.let { ZhuyinKeys.GROUP_SYMBOLS.getOrNull(it - 1) }?.let(KeyDefaults::zhuyinGroup)
        }
        else -> null
    }

    // a built-in group's corner, by its place among its keyboard's groups; a group's `;` is not in its name (18 键's
    // L is `l;`, named `g18/l`)
    private fun cornerOf(id: KeyId): String? {
        val (letters, form) = if (id.form == "g14") NineKeyLetters.G14 to KeyboardVariant.Form.Grouped14 else NineKeyLetters.G18 to KeyboardVariant.Form.Grouped18
        val place = letters.codes.indexOfFirst { letters.letters(it).replace(";", "") == id.key }
        return KeyDefaults.corner(form, place)
    }

    // a built-in group's letters, `;` too (as the editor shows 18 键's L, 微软双拼's): the key's name where none is found
    private fun groupOf(id: KeyId): String {
        val letters = if (id.form == "g14") NineKeyLetters.G14 else NineKeyLetters.G18
        return letters.codes.map(letters::letters).firstOrNull { it.replace(";", "") == id.key } ?: id.key
    }

    /**
     * `@1`'s [text] as `@2`'s (060-row-unlock §5), any other as it is (so it can run again and agree). A key's `set`
     * of a built-in keyboard's row (九键's digits by the user's [nineLetters], the 14 and 18 keys' groups, 注音九键's)
     * is its row as the editor shows it, then its symbols not in it; any other key's list as it was. Its first as
     * `@1` resolved it against the built-in default (none where that has none), then written: none `!`, the new
     * list's first item `*`, else the item.
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
        // @1's first: its default's where in its row (and the list), none where it has none, else the first symbol
        val byDefault = when {
            d == null -> symbols.firstOrNull()
            d.first == null -> null
            d.first?.text in row -> d.first?.text?.takeIf { it in items }
            else -> symbols.firstOrNull()
        }
        val first = when (val f = l.first) {
            First.None -> null
            is First.Literal -> f.text.takeIf { it in items } ?: byDefault
            First.Default -> byDefault
        }
        // a key of no row and no symbols: nothing to show, off as KeyLists.parse reads it
        if (items.isEmpty()) return UserList.Off
        return UserList(off = false, items = items, first = KeyEdit.firstToStore(first, items))
    }

    // the default a key upgrades against: the editor's for a row of a built-in keyboard, else 拼音's
    private fun upgradeDefault(id: KeyId, nineLetters: Map<Char, String>): DefaultList? = when (id.form) {
        "t9" -> id.key.singleOrNull()?.takeIf { it in '1'..'9' }?.let { KeyDefaults.nine(it, KeyboardVariant(KeyboardVariant.Form.Nine, nineLetters)) }
        "g14", "g18" -> KeyDefaults.grouped(groupOf(id), cornerOf(id))
        else -> defaultOf(id)
    }

    private fun same(list: UserList, default: DefaultList?): Boolean = when {
        list.off -> default == null
        default == null -> false
        else -> list.first == First.Default && list.items == default.symbols.map { it.text }
    }

    /**
     * 0.5's and the betas' `t9_symbols`: a line a key 1 to 9 set otherwise than its old default ([NINE_DEFAULTS]), its
     * digit then its symbols apart by spaces; each kept as that version kept it (no digit or letter alone, no key code,
     * each once, ten at most), a key set back to its old default none; a later line of a key wins.
     */
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

    // the old defaults of t9_symbols, a key set to them none set
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
