/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.scene.keys.ScrollItem

/**
 * A scroll column's long press menu on [item], [index] of [list] when it opened: what it offers and what a pick does.
 * Every pick checks [item] is still at [index]: the menu stays open after the finger lifts, and the list may change
 * meanwhile (another field's list, settings saved elsewhere).
 */
data class ScrollListMenu(val list: String, val index: Int, val item: ScrollItem) {
    /** In the order shown: removing last, as the clipboard's menu has it. */
    enum class Op { ToFront, Edit, Remove }

    /** [enabled] false: shown faded, where nothing may be written. */
    data class Entry(val op: Op, val enabled: Boolean)

    /**
     * One doing nothing left out: no [Op.ToFront] for the first, no [Op.Remove] for the only one. [readOnly] (a later
     * version's lists, or not yet unlocked): all three, none enabled, or the user would not know why no menu came.
     * Null where [item] is no longer at [index].
     */
    fun entries(store: ScrollListStore, readOnly: Boolean): List<Entry>? {
        val items = store[list]
        if (items.getOrNull(index) != item) return null
        if (readOnly) return Op.entries.map { Entry(it, enabled = false) }
        return Op.entries.filter { (it != Op.ToFront || index > 0) && (it != Op.Remove || items.size > 1) }.map { Entry(it, enabled = true) }
    }

    /**
     * [store] with [op] done and how to undo it; null, nothing done, where the column no longer [shows] [list], [item]
     * moved, or [op] is not one [entries] offers. [Op.Edit] changes nothing here.
     */
    fun change(store: ScrollListStore, shows: String?, op: Op, readOnly: Boolean): Change? {
        if (shows != list || readOnly || store.newer) return null
        if (entries(store, readOnly = false)?.any { it.op == op } != true) return null
        val changed = when (op) {
            Op.ToFront -> store.toFront(list, index)
            Op.Remove -> store.removed(list, index)
            Op.Edit -> return null
        }
        return Change(changed, Undo(list, before = store.stored(list), after = changed.stored(list)))
    }

    data class Change(val store: ScrollListStore, val undo: Undo)

    /** [list]'s stored items, hidden commands included, [before] and [after] a change. */
    data class Undo(val list: String, val before: List<ScrollItem>, val after: List<ScrollItem>) {
        /** [before] again, every item where it was; null, nothing done, where the list changed since. */
        fun undone(store: ScrollListStore, readOnly: Boolean): ScrollListStore? =
            if (readOnly || store.newer || store.stored(list) != after) null else store.restored(list, before)
    }
}
