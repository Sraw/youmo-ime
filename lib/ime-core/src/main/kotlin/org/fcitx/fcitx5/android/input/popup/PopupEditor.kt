/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import org.fcitx.fcitx5.android.input.keys.First
import org.fcitx.fcitx5.android.input.keys.KeyLists
import org.fcitx.fcitx5.android.input.keys.UserList

/**
 * A key's long press as the settings edit it (060-row-unlock §1): its whole list, every item (a key's own letters
 * and digit too) moved, deleted and added to alike; one item selected (or none, -1), and its [first], what a
 * swipe's 长按首选 types (null: none). Each action keeps the selection on the item it moved, or on the one that
 * took the place of the one removed.
 */
class PopupEditor(items: List<String>, first: String?) {

    private val list = items.distinct().toMutableList()

    /** The items as they are now: what is stored. */
    val items: List<String> get() = list

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
        if (gone == first) first = UserList.firstOf(First.Default, list)
        // the next item: none left, nothing selected
        selected = if (list.isEmpty()) -1 else minOf(selected, list.lastIndex)
    }

    /**
     * Adds the items of [text] (as [PopupOverrides.tokens] splits it) not there yet, at the end, up to
     * [KeyLists.MAX_ITEMS]; to a list of none, the first of them first. False when some did not fit.
     */
    fun add(text: String): Boolean {
        val wasEmpty = list.isEmpty()
        val new = PopupOverrides.tokens(text).distinct().filter { it !in list }
        val room = (KeyLists.MAX_ITEMS - list.size).coerceAtLeast(0)
        list += new.take(room)
        if (wasEmpty) first = list.firstOrNull()
        return new.size <= room
    }
}
