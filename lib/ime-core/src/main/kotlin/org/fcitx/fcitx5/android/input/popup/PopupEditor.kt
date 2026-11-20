/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.input.keys.First
import org.fcitx.fcitx5.android.input.keys.ItemMark
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.UserList

/** A key's long press as settings edit it: every item alike; one selected (-1 none), its [first] (null none), [marks] moving with items. */
class PopupEditor(items: List<String>, first: String?, marks: Map<String, ItemMark> = emptyMap()) {

    private val list = items.distinct().toMutableList()

    private val marked = HashMap(marks.filterKeys { it in list })

    val items: List<String> get() = list

    val marks: Map<String, ItemMark> get() = marked

    /** Null with none (or nothing selected). */
    val selectedMark: ItemMark? get() = list.getOrNull(selected)?.let(marked::get)

    var first: String? = first?.takeIf { it in list }
        private set

    var selected = -1
        private set

    /** Or nothing if it was selected already. */
    fun tap(index: Int) {
        selected = if (selected == index || index !in list.indices) -1 else index
    }

    val canMakeFirst get() = selected in list.indices && list[selected] != first
    val canMoveLeft get() = selected in 1..list.lastIndex
    val canMoveRight get() = selected in 0 until list.lastIndex
    val canDelete get() = selected in list.indices

    /** One mark per item; 不转中文标点 only on an English mark [keys] convert. A variables item longer than [KeyLists.MAX_ITEM] keeps its mark, or it would be cut. */
    fun canMark(mark: ItemMark, keys: Set<String>): Boolean {
        val item = list.getOrNull(selected) ?: return false
        if (marked[item].let { it != null && it != mark }) return false
        if (marked[item] == ItemMark.Variables && item.codePointCount(0, item.length) > KeyLists.MAX_ITEM) return false
        return mark != ItemMark.AsIs || canKeepAsIs(item, keys)
    }

    /** Or off where it has it. */
    fun toggle(mark: ItemMark) {
        val item = list.getOrNull(selected) ?: return
        if (marked[item] == mark) marked.remove(item) else if (marked[item] == null) marked[item] = mark
    }

    fun makeFirst() {
        if (selected in list.indices) first = list[selected]
    }

    fun moveLeft() = move(selected - 1)

    fun moveRight() = move(selected + 1)

    private fun move(to: Int) {
        if (!canDelete || to !in list.indices) return
        list.add(to, list.removeAt(selected))
        selected = to
    }

    /** The first, if it was, falls back to the list's first (none with none). */
    fun delete() {
        if (!canDelete) return
        val gone = list.removeAt(selected)
        marked.remove(gone)
        if (gone == first) first = UserList.firstOf(First.Default, list)
        // the next item: none left, nothing selected
        selected = if (list.isEmpty()) -1 else minOf(selected, list.lastIndex)
    }

    /**
     * Adds [text]'s items not there yet, up to [KeyLists.MAX_ITEMS]; false when some did not fit. [ItemMark.Variables]:
     * one item ([template]), marked whether it holds a variable or not (the user's mark, drawn as 「变量」); [ItemMark.AsIs]:
     * those [canKeepAsIs]. An unmarked item already there takes [mark].
     */
    fun add(text: String, mark: ItemMark? = null, keys: Set<String> = emptySet()): Boolean {
        val wasEmpty = list.isEmpty()
        val typed = if (mark == ItemMark.Variables) listOfNotNull(template(text).takeIf { it.isNotEmpty() }) else PopupOverrides.tokens(text)
        val new = typed.distinct().filter { it !in list }
        val room = (KeyLists.MAX_ITEMS - list.size).coerceAtLeast(0)
        val added = new.take(room)
        list += added
        for (item in typed.distinct().filter { it in list && it !in marked }) {
            if (mark == ItemMark.Variables || mark == ItemMark.AsIs && canKeepAsIs(item, keys)) marked[item] = mark
        }
        if (wasEmpty) first = list.firstOrNull()
        return new.size <= room
    }

    companion object {
        /** One character a Chinese punctuation table of [keys] converts. */
        fun canKeepAsIs(text: String, keys: Set<String>): Boolean = text.codePointCount(0, text.length) == 1 && text in keys

        /** Line breaks as spaces (the field is one line), trimmed, [KeyLists.MAX_TEMPLATE] code points at most. */
        fun template(text: String): String = KeyLists.clip(text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim(), ItemMark.Variables)
    }
}
