/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.picker

/**
 * The symbol panel, after Sogou's: the categories down its side and one shown at a time, so a
 * symbol is where it was the last time; a symbol picked takes the keyboard back, unless the
 * panel is locked for a run of them. The one list scrolled through all categories, with tabs
 * above, read worse: nothing stayed put, and three short rows were all one saw.
 *
 * @param categories how many, [RECENT] the first
 * @param first where the panel opens until another is picked: the common symbols
 */
class SymbolPanel(private val categories: Int, private val first: Int) {

    init {
        require(first in 0 until categories) { "first $first of $categories" }
    }

    var selected = first
        private set

    /** Opened again, on the category shown last: not the recently used, if there are none now. */
    fun open(recentEmpty: Boolean) {
        if (!shows(selected, recentEmpty)) selected = first
    }

    /** Whether [category] is listed: the recently used, only once something was used. */
    fun shows(category: Int, recentEmpty: Boolean) = category != RECENT || !recentEmpty

    fun select(category: Int) {
        require(category in 0 until categories) { "category $category of $categories" }
        selected = category
    }

    /** Whether a symbol picked takes the keyboard back: one symbol is what is wanted most often. */
    fun returnsAfterPick(locked: Boolean) = !locked

    companion object {
        const val RECENT = 0
    }
}
