/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.input.keys.First
import org.fcitx.fcitx5.android.input.keys.ItemMark
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.UserList

/**
 * A key's long press as the settings edit it (060-row-unlock §1): its whole list, every item (a key's own letters
 * and digit too) moved, deleted and added to alike; one item selected (or none, -1), and its [first], what a
 * swipe's 长按首选 types (null: none), and the [marks] of its items (使用变量, 不转中文标点: 061-text-variables §2.5,
 * 061-no-cn-punct §2.3), one at most an item, going with it as it moves. Each action keeps the selection on the item
 * it moved, or on the one that took the place of the one removed.
 */
class PopupEditor(items: List<String>, first: String?, marks: Map<String, ItemMark> = emptyMap()) {

    private val list = items.distinct().toMutableList()

    private val marked = HashMap(marks.filterKeys { it in list })

    /** The items as they are now: what is stored. */
    val items: List<String> get() = list

    /** The marks of [items] by their text: what is stored. */
    val marks: Map<String, ItemMark> get() = marked

    /** The mark of the item selected, null with none (or nothing selected). */
    val selectedMark: ItemMark? get() = list.getOrNull(selected)?.let(marked::get)

    var first: String? = first?.takeIf { it in list }
        private set

    var selected = -1
        private set

    /** Selects [index], or nothing if it was selected already. */
    fun tap(index: Int) {
        selected = if (selected == index || index !in list.indices) -1 else index
    }

    val canMakeFirst get() = selected in list.indices && list[selected] != first
    val canMoveLeft get() = selected in 1..list.lastIndex
    val canMoveRight get() = selected in 0 until list.lastIndex
    val canDelete get() = selected in list.indices

    /**
     * Whether the item selected can have [mark] turned on or off: one of the other mark has none, and 不转中文标点 is
     * only an English mark's that a Chinese punctuation table of [keys] converts ([canKeepAsIs]). An item of variables
     * longer than a plain item may be ([KeyLists.MAX_ITEM]) keeps its mark: turned off, it would be cut where stored
     * and the first of it lost.
     */
    fun canMark(mark: ItemMark, keys: Set<String>): Boolean {
        val item = list.getOrNull(selected) ?: return false
        if (marked[item].let { it != null && it != mark }) return false
        if (marked[item] == ItemMark.Variables && item.codePointCount(0, item.length) > KeyLists.MAX_ITEM) return false
        return mark != ItemMark.AsIs || canKeepAsIs(item, keys)
    }

    /** [mark] turned on for the item selected, or off where it has it. */
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

    /** The selected item removed; the first, if it was, falls back to the list's first item now (none with none). */
    fun delete() {
        if (!canDelete) return
        val gone = list.removeAt(selected)
        marked.remove(gone)
        if (gone == first) first = UserList.firstOf(First.Default, list)
        // the next item: none left, nothing selected
        selected = if (list.isEmpty()) -1 else minOf(selected, list.lastIndex)
    }

    /**
     * Adds the items of [text] (as [PopupOverrides.tokens] splits it) not there yet, at the end, up to
     * [KeyLists.MAX_ITEMS]; to a list of none, the first of them first. False when some did not fit. With [mark]
     * [ItemMark.Variables], [text] is one item ([template]); with [ItemMark.AsIs], those of them [canKeepAsIs] of
     * [keys] marked, the others added as ever (061-no-cn-punct Q3). One there already with no mark takes [mark] as
     * one added would.
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
        /**
         * Whether [text] can be marked 不转中文标点 (061-no-cn-punct §2.1): one character a Chinese punctuation table
         * converts, [keys] the keys of the built-in tables and of the user's (the app's to read).
         */
        fun canKeepAsIs(text: String, keys: Set<String>): Boolean = text.codePointCount(0, text.length) == 1 && text in keys

        /**
         * [text] typed as one item of variables (061-text-variables §2.5, S3): its line breaks as spaces (the
         * field is one line; a list's item never holds one), blanks round it gone, [KeyLists.MAX_TEMPLATE] code
         * points at most.
         */
        fun template(text: String): String = KeyLists.clip(text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim(), ItemMark.Variables)
    }
}
