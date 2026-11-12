/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ZhuyinKeys
import org.fcitx.fcitx5.android.input.keyboard.NineKeySymbols
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
        val nine = NineKeySymbols.parse(t9Symbols.orEmpty())
        for (d in '1'..'9') {
            val id = KeyId("t9/$d")
            // a key set by its name had its symbols there: t9_symbols no longer counted for it (I17)
            if (id in named) continue
            // what the user set (the old defaults, 1's full width and as is, are none: its default now is the one list
            // through the punctuation)
            if (!nine.isDefault(d)) lists[id] = UserList(off = false, symbols = nine.symbols(d))
        }
        val kept = lists.filterNot { (id, list) -> same(list, defaultOf(id)) }
        return Migrated(KeyLists.of(kept).serialize(), lost)
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
        // editor put the first in front of it (OwnLongPress.cornerFirst), which is no move
        val own = default?.row?.map { it.text }.orEmpty().filter { it != default?.first?.text }
        if (items.filter { it in own } != own) lost += "L4 ${id.name} ${items.joinToString(" ")}"
        val f = if (first == default?.first?.text) First.Default else First.Literal(first)
        lists[id] = UserList(off = false, symbols = items.filterNot { it in row }, first = f)
    }

    // a 0.5 list: the first item the first, as it was there
    private fun plain(items: List<String>): UserList = if (items.isEmpty()) UserList.Off else UserList(off = false, symbols = items)

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
            "question" -> KeyDefaults.preset("?")
            "exclam" -> KeyDefaults.preset("!")
            "tilde" -> KeyDefaults.preset("~")
            else -> id.key.toIntOrNull()?.let { ZhuyinKeys.GROUP_SYMBOLS.getOrNull(it - 1) }?.let(KeyDefaults::zhuyinGroup)
        }
        else -> null
    }

    // a built-in group's corner, by its place among its keyboard's groups
    private fun cornerOf(id: KeyId): String? {
        val (letters, form) = if (id.form == "g14") NineKeyLetters.G14 to KeyboardVariant.Form.Grouped14 else NineKeyLetters.G18 to KeyboardVariant.Form.Grouped18
        val place = letters.codes.indexOfFirst { letters.letters(it) == id.key }
        return KeyDefaults.corner(form, place)
    }

    private fun same(list: UserList, default: DefaultList?): Boolean = when {
        list.off -> default == null
        default == null -> false
        else -> list.first == First.Default && list.symbols == default.symbols.map { it.text }
    }
}
