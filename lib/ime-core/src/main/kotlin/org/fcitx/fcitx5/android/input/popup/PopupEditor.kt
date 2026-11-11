/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

/**
 * A key's long press as the settings edit it (060-keys-unify S5): its keyboard's [row] first, fixed (never
 * stored: it can only be chosen as the [first]), then its symbols, which can be moved, deleted and added to;
 * one item selected (or none, -1), and its [first], what a swipe's 长按首选 types (null: none). Each action
 * keeps the selection on the item it moved, or on the one that took the place of the one removed.
 */
class PopupEditor(val row: List<String>, symbols: List<String>, first: String?) {

    private val list = symbols.filter { it !in row }.distinct().toMutableList()

    val items: List<String> get() = row + list

    /** The symbols as they are now: what is stored. */
    val symbols: List<String> get() = list

    var first: String? = first?.takeIf { it in items }
        private set

    var selected = -1
        private set

    /** Whether the item at [index] is of the row: greyed, only ever chosen as the first. */
    fun isRow(index: Int) = index in row.indices

    /** Selects [index], or nothing if it was selected already. */
    fun tap(index: Int) {
        selected = if (selected == index || index !in items.indices) -1 else index
    }

    val canMakeFirst get() = selected in items.indices && items[selected] != first
    val canMoveLeft get() = selected > row.size
    val canMoveRight get() = selected >= row.size && selected < items.lastIndex
    val canDelete get() = selected >= row.size && selected in items.indices

    fun makeFirst() {
        if (selected in items.indices) first = items[selected]
    }

    fun moveLeft() = move(selected - 1)

    fun moveRight() = move(selected + 1)

    private fun move(to: Int) {
        if (!canDelete || to !in row.size..items.lastIndex) return
        val from = selected - row.size
        list.add(to - row.size, list.removeAt(from))
        selected = to
    }

    /** The selected symbol removed; the first, if it was, passed to the first item left (none with none). */
    fun delete() {
        if (!canDelete) return
        val gone = list.removeAt(selected - row.size)
        if (gone == first) first = items.firstOrNull()
        selected = if (items.isEmpty()) -1 else minOf(selected, items.lastIndex)
    }

    /** Adds the items of [text] (as [PopupOverrides.tokens] splits it) not there yet, at the end; to a list of none, the first of them first. */
    fun add(text: String) {
        val wasEmpty = items.isEmpty()
        PopupOverrides.tokens(text).distinct().filterTo(list) { it !in items }
        if (wasEmpty) first = list.firstOrNull()
    }
}
