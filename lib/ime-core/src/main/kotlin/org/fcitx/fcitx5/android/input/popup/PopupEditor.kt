/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

/**
 * A key's long-press characters as the settings edit them: one selected (or none, -1), and what
 * can be done to it. Each action keeps the selection on the character it moved, or on the one
 * that took the place of the one removed.
 */
class PopupEditor(items: List<String>) {

    private val list = items.toMutableList()

    val items: List<String> get() = list

    var selected = -1
        private set

    /** Selects [index], or nothing if it was selected already. */
    fun tap(index: Int) {
        selected = if (selected == index || index !in list.indices) -1 else index
    }

    val canMakeFirst get() = selected > 0
    val canMoveLeft get() = selected > 0
    val canMoveRight get() = selected in 0 until list.lastIndex
    val canDelete get() = selected >= 0

    fun makeFirst() = move(0)

    fun moveLeft() = move(selected - 1)

    fun moveRight() = move(selected + 1)

    private fun move(to: Int) {
        if (selected !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(selected))
        selected = to
    }

    fun delete() {
        if (selected !in list.indices) return
        list.removeAt(selected)
        selected = if (list.isEmpty()) -1 else minOf(selected, list.lastIndex)
    }

    /** Adds the characters of [text] (as [PopupOverrides.tokens] splits it) not there yet, at the end. */
    fun add(text: String) {
        PopupOverrides.tokens(text).distinct().filterTo(list) { it !in list }
    }
}
